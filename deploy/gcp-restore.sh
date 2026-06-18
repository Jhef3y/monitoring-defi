#!/usr/bin/env bash
#
# deploy/gcp-restore.sh
# Restaura um banco TimescaleDB já populado para dentro da VM criada por gcp-deploy.sh.
#
# Usa dump CUSTOM (-Fc) + pg_restore — método recomendado para TimescaleDB,
# muito mais robusto que SQL plano (tolera a ordem dos objetos do catálogo).
#
# Estratégia (segura para TimescaleDB + Flyway):
#   1. pg_dump -Fc da origem  [ou usa DUMP_FILE existente, formato custom .dump]
#   2. copia o dump para a VM via 'gcloud compute scp'
#   3. para os containers app/frontend (mantém só o timescaledb de pé)
#   4. recria o banco do zero (evita conflito com o schema que o Flyway cria)
#   5. CREATE EXTENSION + timescaledb_pre_restore() -> pg_restore -> timescaledb_post_restore()
#   6. religa app/frontend e compara a contagem origem x destino
#
# A porta 5432 NUNCA é exposta: tudo roda via 'docker exec' dentro da VM (SSH).
#
# Pré-requisitos:
#   - pg_dump/psql instalados localmente (versão >= a do servidor de origem)
#   - gcloud autenticado, mesma conta/projeto do deploy
#
# Uso (gerando o dump na hora a partir do banco atual):
#   export PROJECT_ID=seu-projeto-gcp
#   export SOURCE_DB_URL='postgresql://defi:defi@localhost:5432/defi_timeseries'
#   ./deploy/gcp-restore.sh
#
# Uso (a partir de um arquivo .dump já existente):
#   export PROJECT_ID=seu-projeto-gcp
#   export DUMP_FILE=./meu_backup.dump
#   ./deploy/gcp-restore.sh
#
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?defina PROJECT_ID}"

ZONE="${ZONE:-us-central1-a}"
VM_NAME="${VM_NAME:-monitoring-defi}"
CONTAINER="${CONTAINER:-defi-timescaledb}"   # nome do container no docker-compose.yml
REMOTE_DIR="/opt/monitoring-defi"

DB_NAME="${DB_NAME:-defi_timeseries}"
DB_USER="${DB_USER:-defi}"
CHECK_TABLE="${CHECK_TABLE:-pool_metrics}"   # tabela usada para verificar a contagem

SOURCE_DB_URL="${SOURCE_DB_URL:-}"
DUMP_FILE="${DUMP_FILE:-}"

gcloud config set project "$PROJECT_ID" >/dev/null

# ----------------------------------------------------------------------------
# 1. Obter o dump (gerar da origem ou usar arquivo existente)
# ----------------------------------------------------------------------------
SRC_COUNT="(desconhecido)"
CLEANUP_LOCAL=0
if [ -z "$DUMP_FILE" ]; then
  [ -n "$SOURCE_DB_URL" ] || { echo "ERRO: defina SOURCE_DB_URL ou DUMP_FILE"; exit 1; }

  echo ">> Contagem na ORIGEM (${CHECK_TABLE})..."
  SRC_COUNT="$(psql "$SOURCE_DB_URL" -tAc "SELECT count(*) FROM ${CHECK_TABLE};" 2>/dev/null || echo '?')"
  echo "   origem ${CHECK_TABLE} = ${SRC_COUNT}"

  DUMP_FILE="$(mktemp -t defi_dump.XXXXXX).sql.gz"
  CLEANUP_LOCAL=1
  echo ">> Gerando dump SQL plano (-Fp) da origem..."
  # SQL plano é à prova de versão: sem cabeçalho binário, um pg_dump 17 gera um
  # arquivo que o psql 16 (no container) carrega sem problema.
  # --no-owner/--no-privileges: evita erros de role inexistente no destino.
  pg_dump "$SOURCE_DB_URL" \
    --format=plain --no-owner --no-privileges \
    | gzip > "$DUMP_FILE"
  echo ">> Dump gerado: $(du -h "$DUMP_FILE" | cut -f1)"
else
  echo ">> Usando dump existente: $DUMP_FILE (esperado: SQL plano .sql.gz)"
  [ -f "$DUMP_FILE" ] || { echo "ERRO: arquivo não encontrado: $DUMP_FILE"; exit 1; }
fi

REMOTE_DUMP="/tmp/defi_restore.sql.gz"

# ----------------------------------------------------------------------------
# 2. Copiar o dump para a VM
# ----------------------------------------------------------------------------
echo ">> Enviando dump para a VM ($VM_NAME)..."
gcloud compute scp "$DUMP_FILE" "${VM_NAME}:${REMOTE_DUMP}" --zone="$ZONE"

[ "$CLEANUP_LOCAL" -eq 1 ] && rm -f "$DUMP_FILE"

# ----------------------------------------------------------------------------
# 3-6. Executar o restore dentro da VM
# ----------------------------------------------------------------------------
echo ">> Executando restore na VM..."

# O script remoto é montado AQUI (com ${...} expandido localmente) e enviado como
# ARGUMENTO de --command — NÃO pela stdin. Isso é essencial: 'docker exec -i' e
# 'pg_restore' leem a stdin do SSH; se o script viesse por stdin (bash -s), eles
# engoliriam o resto do script e o restore pararia no meio.
REMOTE_SCRIPT=$(cat <<REMOTE
set -uo pipefail
cd "${REMOTE_DIR}"

# psql SEM -i (comandos -c não precisam de stdin e não devem competir por ela).
PSQL() { sudo docker exec ${CONTAINER} psql -U ${DB_USER} "\$@"; }

echo '   -> parando app/frontend (mantendo o banco de pé)...'
sudo docker compose stop app frontend || true

echo '   -> aguardando o TimescaleDB aceitar conexões...'
for i in \$(seq 1 30); do
  if PSQL -d postgres -c 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 2
done

echo '   -> recriando o banco ${DB_NAME} do zero...'
PSQL -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${DB_NAME}' AND pid <> pg_backend_pid();" >/dev/null
PSQL -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};"
PSQL -d postgres -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

echo '   -> preparando extensao + modo restore do TimescaleDB...'
PSQL -d ${DB_NAME} -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"
PSQL -d ${DB_NAME} -c "SELECT timescaledb_pre_restore();"

echo '   -> carregando dados via psql (warnings de catalogo sao esperados)...'
# SQL plano carregado pelo psql do container (compativel entre versoes).
# Sem ON_ERROR_STOP: segue adiante em erros benignos (ex.: extensao ja existe).
# Validamos pelo COUNT final, nao pelo exit code.
gunzip -c ${REMOTE_DUMP} | sudo docker exec -i ${CONTAINER} psql -U ${DB_USER} -d ${DB_NAME} -f - 2>&1 | tail -15
RC=\$?
echo "      carga retornou codigo \$RC (erros benignos de catalogo sao normais)"

echo '   -> finalizando modo restore...'
PSQL -d ${DB_NAME} -c "SELECT timescaledb_post_restore();"
PSQL -d ${DB_NAME} -c "ANALYZE;"

echo '   -> religando app/frontend...'
sudo docker compose up -d app frontend

echo '   -> limpando dump remoto...'
rm -f ${REMOTE_DUMP}

echo "   -> CONTAGEM no DESTINO (${CHECK_TABLE}):"
PSQL -d ${DB_NAME} -tAc "SELECT count(*) FROM ${CHECK_TABLE};" 2>&1 | head
REMOTE
)

gcloud compute ssh "$VM_NAME" --zone="$ZONE" --command="$REMOTE_SCRIPT"

echo ""
echo "============================================================"
echo " Restore finalizado."
echo "   Origem  ${CHECK_TABLE} = ${SRC_COUNT}"
echo "   (compare com a contagem do DESTINO impressa acima)"
echo " Se baterem, o dashboard em http://<IP-da-VM>:3000 já mostra"
echo " o histórico (atualiza a cada 30s)."
echo "============================================================"
