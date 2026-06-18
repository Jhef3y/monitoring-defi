#!/usr/bin/env bash
#
# deploy/gcp-deploy.sh
# Provisiona uma VM e2-medium no Compute Engine e sobe o stack (app + TimescaleDB + frontend)
# via Docker Compose. Idempotente: pode rodar de novo para criar o que faltar.
#
# Pré-requisitos:
#   - gcloud CLI instalado e autenticado (gcloud auth login)
#   - Um projeto GCP com billing ativo
#   - Sua ALCHEMY_API_KEY (Solana Mainnet)
#
# Uso:
#   export PROJECT_ID=seu-projeto-gcp
#   export ALCHEMY_API_KEY=sua-chave
#   ./deploy/gcp-deploy.sh
#
set -euo pipefail

# ----------------------------------------------------------------------------
# Configuração (sobrescreva via variáveis de ambiente)
# ----------------------------------------------------------------------------
PROJECT_ID="${PROJECT_ID:?defina PROJECT_ID}"
ALCHEMY_API_KEY="${ALCHEMY_API_KEY:?defina ALCHEMY_API_KEY}"

REGION="${REGION:-us-central1}"
ZONE="${ZONE:-us-central1-a}"
VM_NAME="${VM_NAME:-monitoring-defi}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-medium}"        # 2 vCPU compart. / 4GB
BOOT_DISK_SIZE="${BOOT_DISK_SIZE:-20}"           # GB (SO + imagens Docker)
DATA_DISK_SIZE="${DATA_DISK_SIZE:-20}"           # GB (volume do TimescaleDB)
DATA_DISK_TYPE="${DATA_DISK_TYPE:-pd-standard}"  # HDD = mais barato
IMAGE_FAMILY="${IMAGE_FAMILY:-ubuntu-2204-lts}"
IMAGE_PROJECT="${IMAGE_PROJECT:-ubuntu-os-cloud}"

REPO_URL="${REPO_URL:-https://github.com/Jhef3y/monitoring-defi.git}"
REPO_BRANCH="${REPO_BRANCH:-main}"

DB_USER="${DB_USER:-defi}"
DB_PASSWORD="${DB_PASSWORD:-defi}"

NETWORK_TAG="monitoring-defi"

echo ">> Projeto: $PROJECT_ID | Zona: $ZONE | Máquina: $MACHINE_TYPE"
gcloud config set project "$PROJECT_ID" >/dev/null

# ----------------------------------------------------------------------------
# 1. Habilita a API do Compute Engine
# ----------------------------------------------------------------------------
echo ">> Habilitando Compute Engine API..."
gcloud services enable compute.googleapis.com >/dev/null

# ----------------------------------------------------------------------------
# 2. Regras de firewall (SSH + dashboard 3000 + app 8080)
#    Postgres (5432) NÃO é exposto — fica interno à VM.
# ----------------------------------------------------------------------------
create_fw () {
  local name="$1"; local ports="$2"; local src="$3"
  if ! gcloud compute firewall-rules describe "$name" >/dev/null 2>&1; then
    echo ">> Criando firewall $name ($ports)..."
    gcloud compute firewall-rules create "$name" \
      --direction=INGRESS --action=ALLOW \
      --rules="$ports" --source-ranges="$src" \
      --target-tags="$NETWORK_TAG" >/dev/null
  else
    echo ">> Firewall $name já existe."
  fi
}
create_fw "${NETWORK_TAG}-ssh"  "tcp:22"   "0.0.0.0/0"
create_fw "${NETWORK_TAG}-app"  "tcp:8080" "0.0.0.0/0"
create_fw "${NETWORK_TAG}-web"  "tcp:3000" "0.0.0.0/0"

# ----------------------------------------------------------------------------
# 3. Disco de dados persistente (sobrevive à recriação da VM)
# ----------------------------------------------------------------------------
if ! gcloud compute disks describe "${VM_NAME}-data" --zone="$ZONE" >/dev/null 2>&1; then
  echo ">> Criando disco de dados ${VM_NAME}-data (${DATA_DISK_SIZE}GB ${DATA_DISK_TYPE})..."
  gcloud compute disks create "${VM_NAME}-data" \
    --zone="$ZONE" --size="$DATA_DISK_SIZE" --type="$DATA_DISK_TYPE" >/dev/null
else
  echo ">> Disco ${VM_NAME}-data já existe."
fi

# ----------------------------------------------------------------------------
# 4. Startup script (roda na primeira inicialização da VM)
#    - swap de 2GB (rede de segurança contra OOM)
#    - formata/monta o disco de dados em /mnt/data
#    - instala Docker + compose plugin
#    - clona o repo, gera .env e sobe o compose
# ----------------------------------------------------------------------------
STARTUP=$(cat <<STARTUP_EOF
#!/usr/bin/env bash
set -euxo pipefail
exec > /var/log/startup-monitoring-defi.log 2>&1

# --- swap 2GB ---
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# --- disco de dados em /mnt/data ---
DATA_DEV=/dev/disk/by-id/google-${VM_NAME}-data
if ! blkid "\$DATA_DEV" >/dev/null 2>&1; then
  mkfs.ext4 -F "\$DATA_DEV"
fi
mkdir -p /mnt/data
grep -q '/mnt/data' /etc/fstab || echo "\$DATA_DEV /mnt/data ext4 defaults,nofail 0 2" >> /etc/fstab
mount -a

# --- Docker ---
if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  apt-get install -y ca-certificates curl git
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=\$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \$(. /etc/os-release && echo \$VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
  systemctl enable --now docker
fi

# --- código ---
mkdir -p /opt
if [ ! -d /opt/monitoring-defi/.git ]; then
  git clone --branch "${REPO_BRANCH}" "${REPO_URL}" /opt/monitoring-defi
else
  git -C /opt/monitoring-defi pull --ff-only || true
fi
cd /opt/monitoring-defi

# --- .env ---
cat > .env <<ENV
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
ALCHEMY_API_KEY=${ALCHEMY_API_KEY}
ENV

# --- aponta o volume do TimescaleDB para o disco persistente ---
# Override do compose: bind-mount em vez de volume nomeado.
cat > docker-compose.override.yml <<OVR
services:
  timescaledb:
    volumes:
      - /mnt/data/timescale:/var/lib/postgresql/data
OVR
mkdir -p /mnt/data/timescale

# --- sobe o stack ---
docker compose pull || true
docker compose up -d --build
STARTUP_EOF
)

# ----------------------------------------------------------------------------
# 5. Cria (ou atualiza) a VM
# ----------------------------------------------------------------------------
if ! gcloud compute instances describe "$VM_NAME" --zone="$ZONE" >/dev/null 2>&1; then
  echo ">> Criando VM $VM_NAME..."
  TMP_STARTUP=$(mktemp)
  printf '%s' "$STARTUP" > "$TMP_STARTUP"
  gcloud compute instances create "$VM_NAME" \
    --zone="$ZONE" \
    --machine-type="$MACHINE_TYPE" \
    --image-family="$IMAGE_FAMILY" \
    --image-project="$IMAGE_PROJECT" \
    --boot-disk-size="${BOOT_DISK_SIZE}GB" \
    --boot-disk-type=pd-balanced \
    --disk="name=${VM_NAME}-data,device-name=${VM_NAME}-data,mode=rw,boot=no" \
    --tags="$NETWORK_TAG" \
    --metadata-from-file=startup-script="$TMP_STARTUP"
  rm -f "$TMP_STARTUP"
else
  echo ">> VM $VM_NAME já existe (pulando criação)."
fi

# ----------------------------------------------------------------------------
# 6. Saída
# ----------------------------------------------------------------------------
IP=$(gcloud compute instances describe "$VM_NAME" --zone="$ZONE" \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)')

cat <<DONE

============================================================
 Deploy disparado. A VM está executando o startup script
 (instala Docker + sobe o compose). Pode levar 3-8 min.
------------------------------------------------------------
 IP externo:   $IP
 Dashboard:    http://$IP:3000
 App/actuator: http://$IP:8080/actuator/health

 Acompanhar o provisionamento:
   gcloud compute ssh $VM_NAME --zone=$ZONE \\
     --command='sudo tail -f /var/log/startup-monitoring-defi.log'

 Ver containers:
   gcloud compute ssh $VM_NAME --zone=$ZONE \\
     --command='cd /opt/monitoring-defi && sudo docker compose ps'
============================================================
DONE
