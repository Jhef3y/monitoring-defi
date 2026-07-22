# monitoring-defi

Pipeline ETL de alta performance e resiliência para monitorar liquidez concentrada na **Orca (Whirlpools) na Solana** e popular um banco de séries temporais (TimescaleDB), voltado ao treino de um agente de IA para abertura/fechamento de posições de range curto (~1%) em períodos de lateralização.

Pools monitoradas (Orca Whirlpools): **SOL/USDC**, **cbBTC/USDC** e **SPYx/USDC**.

**Stack:** Java 25 (LTS) · Spring Boot 4.0 (Spring Framework 7) · Virtual Threads (Loom) · Structured Concurrency (JEP 505, preview) · TimescaleDB.

> Spring Boot 4.0 é a primeira linha com suporte de primeira classe ao Java 25 (ASM atualizado no `repackage` e proxies compatíveis com bytecode major 69). Versões 3.4/3.5 falham ao empacotar/rodar em Java 25.

---

## 1. Arquitetura do sistema

Quatro camadas, separando o **caminho rápido** (eventos em tempo real via WebSocket) do **caminho lento** (consultas agendadas a cada hora).

```
                        ┌──────────────────────────────────────────────────────────┐
                        │                    FONTES EXTERNAS                          │
                        │  Solana RPC/WS (Alchemy)   on-chain (Orca)   Calendário Macro│
                        └───────┬───────────────────┬───────────────────┬────────────┘
                                │ account updates   │ reservas/sqrtPrice│ eventos 24h
   ┌────────────────────────────┼───────────────────┼───────────────────┼───────────┐
   │ CAMADA DE INGESTÃO          │ (caminho rápido)  │  (caminho lento — cron 1h)    │
   │                            ▼                    ▼                   ▼            │
   │  OrcaWhirlpoolService        snapshotFundamentals  MacroCalendarService          │
   │  (accountSubscribe:          (TVL/Vol-TVL/fees    (faireconomy / Forex Factory)  │
   │   whirlpool + 2 vaults)       on-chain)             │                            │
   │       │ WhirlpoolDecoder          └──────────┬──────────┘                        │
   │       │ (Borsh, sqrtPrice Q64.64)            ▼                                   │
   │       │                          EnrichmentOrchestrator                          │
   │       │ PriceTick                (Structured Concurrency: 2 forks paralelos)     │
   └───────┼───────────────────────────────────┼──────────────────────────────────────┘
           │                                    │ publica
   ┌────────┼────────────────────────────────────┼──────────────────────────────────┐
   │ CAMADA DE PROCESSAMENTO                      ▼                                   │
   │  PoolMonitorWorker (1/pool)  ◄────────  EnrichmentCache (fundamentos + macro)    │
   │     └─ CandleAggregator (1m/5m/1h) → OHLCV incremental                           │
   └────────┼─────────────────────────────────────────────────────────────────────────┘
           │ candle fechado
   ┌────────┼─────────────────────────────────────────────────────────────────────────┐
   │ CAMADA DE CÁLCULO DE INDICADORES                                                  │
   │  IndicatorEngine: ATR(14) · Bollinger(20,2σ) + Bandwidth/Squeeze · VolumeProfile/PoC│
   │  MarketClassifier: switch pattern-matching → LATERAL/TENDENCIA/VOLATILIDADE_EXTREMA│
   └────────┼─────────────────────────────────────────────────────────────────────────┘
           │ PoolMetric (Record imutável)
   ┌────────┼─────────────────────────────────────────────────────────────────────────┐
   │ CAMADA DE PERSISTÊNCIA                                                            │
   │  MetricRepository → buffer + batch UPSERT → TimescaleDB (hypertable pool_metrics) │
   │  Flyway (migração V1) · compressão · retenção · continuous aggregate 1h           │
   └───────────────────────────────────────────────────────────────────────────────────┘
```

### Mapa de pacotes

| Pacote | Responsabilidade |
|---|---|
| `config` | Properties tipadas, Virtual Threads, WebClient, ObjectMapper |
| `dto` | Records imutáveis (`PriceTick`, `PoolFundamentals`, `SolanaDtos`, `MacroDtos`, `PoolMetric`) |
| `solana` | WebSocket Orca (accountSubscribe), decoder Borsh da Whirlpool, Base58, RPC client |
| `macro` | Calendário econômico macro |
| `processing` | Workers por pool, cache de enriquecimento, orquestrador (Structured Concurrency) |
| `indicators` | Agregador OHLCV, motor de indicadores, classificador de regime |
| `persistence` | Sink/repositório batch para a hypertable |

---

## 2. Recursos do Java 25 em uso

- **Records** — todos os DTOs de payload (`PriceTick`, `PoolFundamentals`, `SolanaDtos`, `MacroDtos`, `WhirlpoolAccount`) e o `PoolMetric` final são imutáveis.
- **Virtual Threads** — `spring.threads.virtual.enabled=true` + `Executors.newVirtualThreadPerTaskExecutor()`; a conexão WebSocket Solana e o processamento rodam sobre virtual threads, sem bloquear threads de plataforma.
- **Pattern Matching for switch** — `MarketClassifier` usa `switch` com desconstrução de record e cláusulas `when` para classificar o regime.
- **Structured Concurrency (JEP 505, preview)** — `EnrichmentOrchestrator` coordena fundamentos on-chain + Macro em paralelo com `StructuredTaskScope.open()`, propagando falha/cancelamento.

> Structured Concurrency ainda é *preview* no Java 25 → o build usa `--enable-preview` (ver `pom.xml`).

---

## 3. Indicadores calculados

| Indicador | Janela | Uso para o agente de IA |
|---|---|---|
| Rácio Volume/TVL | 1h | Eficiência de geração de taxas |
| ATR | 14 | Volatilidade absoluta / dimensionar o range |
| Bollinger Bands + Bandwidth | 20, 2σ | **Squeeze** = compressão que antecede lateralização |
| Volume Profile + PoC | 24h | Preço de maior consenso (centro do range) |
| Flag Macro | próximas 24h | Alerta de possível rompimento de range |

### Fonte do calendário macro

A flag macro é alimentada por uma **fonte real e gratuita** (sem API key): o feed JSON
semanal **faireconomy / Forex Factory** (`ff_calendar_thisweek.json` + `nextweek`),
que traz título, moeda, horário (ISO-8601) e nível de impacto (High/Medium/Low).

- `ForexFactoryCalendarSource` busca os dois feeds, filtra pelas moedas em
  `defi.macro.currencies` (padrão `USD`) e normaliza para o domínio.
- `MacroCalendarService` aplica a janela de 24h e marca `highImpactNext24h` quando
  há evento `High` (ex.: *Federal Funds Rate*, *CPI*, *FOMC Statement*).
- Para rodar offline (testes/sem rede), defina `defi.macro.mock-enabled=true` →
  `MockMacroCalendarSource` assume no lugar. A troca é por `@ConditionalOnProperty`,
  sem alterar a lógica de negócio.

---

## 4. Como executar

### Opção A — Docker Compose (recomendado)

Sobe TimescaleDB + a aplicação juntos:

```bash
cp .env.example .env        # preencha ALCHEMY_API_KEY (app Solana na Alchemy)
docker compose up --build
```

Serviços disponíveis após `up`:

- **App (pipeline):** `http://localhost:8080` (actuator em `/actuator/health`)
- **Dashboard de análise:** `http://localhost:3000` (frontend Node/Express + Chart.js)
- **TimescaleDB:** `localhost:5432`

O `app` e o `frontend` só iniciam após o healthcheck do TimescaleDB passar.

### Dashboard (`/frontend`)

Frontend Node/Express (sem build) que lê o TimescaleDB e oferece: gráfico de preço com Bandas de Bollinger e PoC (squeeze destacado), ATR e Bollinger Bandwidth, volume por candle, rácio Volume/TVL, um **scanner de squeeze/regime de mercado** por pool/timeframe e a tabela de fundamentos. Auto-atualiza a cada 30s. Rodar isolado: `cd frontend && npm install && npm start` (configura o banco via variáveis `DB_*`).

### Opção B — Local

```bash
# 1. Subir só o TimescaleDB
docker compose up -d timescaledb

# 2. Variáveis de ambiente
export ALCHEMY_API_KEY=...     # nó RPC
# (o mesmo ALCHEMY_API_KEY atende RPC HTTP + WebSocket da Solana)

# 3. Build + run (Java 25, --enable-preview já configurado)
./mvnw spring-boot:run
```

Flyway aplica as migrações `V1`/`V2` automaticamente na primeira execução.

### Ingestão on-chain (Solana/Orca)

No startup, o `OrcaWhirlpoolService` descobre via RPC (`getAccountInfo`) os mints, cofres, decimais e a orientação do par de cada Whirlpool. A partir daí há dois modos de ingestão (`defi.solana.use-websocket`):

- **Polling (padrão, `false`)** — a cada `poll-interval-ms` lê o estado de todas as contas (whirlpool + 2 cofres por pool) numa única chamada `getMultipleAccounts`. Confiável e independente de notificações push do provedor.
- **WebSocket (`true`)** — `accountSubscribe` nas mesmas contas, menor latência, mas depende do suporte/limites de push do provedor.

Em ambos, a Whirlpool fornece preço (`sqrtPrice` Q64.64), tick e liquidez in-range; os cofres fornecem reservas e volume (delta de saldo). Cada atualização emite um `PriceTick` que alimenta a agregação OHLCV e os indicadores; os fundamentos (TVL, Volume/TVL, fees) são calculados on-chain a partir das reservas e do preço. Endereços Solana são case-sensitive (base58) — preservados em todo o pipeline.

> Nota sobre volume no modo polling: o volume é medido pelo delta de saldo dos cofres entre leituras, então representa a variação líquida no intervalo (um limite inferior do volume bruto quando há compras e vendas no mesmo intervalo). Preço e TVL são exatos; para volume bruto swap-a-swap, use o modo WebSocket ou uma fonte de trades.

## 5. Treino do agente de IA (Fase 2 + 3)

Os scripts em `ml/` são jobs **batch offline** — não fazem parte do caminho em tempo
real da aplicação. O fluxo tem duas etapas:

1. **`ml/build_dataset.py` (Fase 2)** — lê o OHLCV de `pool_metrics`, recalcula
   features *causais* (só passado) e gera labels *forward-looking* (janela de range,
   bandas high/low, `label_in_range`). Exporta um Parquet por timeframe.
2. **`ml/train.py` (Fase 3)** — treina, com validação temporal *walk-forward* +
   *embargo*, um classificador (`P(entrar)`) e dois regressores quantílicos
   (banda `low` p10 / `high` p90). Salva os modelos e um relatório de métricas.

> Não é serviço contínuo: roda **uma vez** para o dataset/modelo inicial e depois
> **periodicamente** (semanal/mensal), antes de cada re-treino, para incorporar os
> dados novos coletados ao vivo. A geração de sinal ao vivo é uma fase posterior.

### 5.1. Conectar na máquina (GCP Compute Engine)

A aplicação e o TimescaleDB rodam numa VM. Há duas formas de alcançar o banco:

**Opção A — rodar direto na VM (mais simples).** O Timescale escuta em
`localhost:5432` dentro da VM, então nem precisa de túnel:

```bash
gcloud compute ssh monitoring-defi --zone us-central1-a
# já dentro da VM:
export DB_DSN='postgresql://defi:defi@localhost:5432/defi_timeseries'
```

**Opção B — rodar no seu notebook via túnel SSH.** Útil se preferir treinar
localmente. Mapeie a porta `5432` da VM para uma porta local (`5433`):

```bash
# Terminal 1 — mantém o túnel aberto (-N não abre shell; Ctrl-C encerra):
gcloud compute ssh NOME_DA_INSTANCIA --zone SUA_ZONA -- -N -L 5433:localhost:5432
```

```bash
# Terminal 2 — com o túnel ativo, o banco da VM aparece em localhost:5433:
export DB_DSN='postgresql://defi:defi@localhost:5433/defi_timeseries'
```

> Ajuste usuário/senha do DSN conforme o seu `.env` (`DB_USER`/`DB_PASSWORD`).
> Como o dataset é pequeno, qualquer uma serve — extrair na VM e baixar só o
> Parquet para treinar no notebook também é uma combinação boa.

### 5.2. Preparar o ambiente

Traga o código para onde vai rodar (`git pull` na VM, ou já no notebook) e instale
as dependências num ambiente virtual:

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy pyarrow psycopg2-binary lightgbm
```

### 5.3. Conferir os dados antes de gerar o dataset

Verifique que o backfill (6 meses) e os dados ao vivo estão contínuos, sem buraco:

```sql
-- psql "$DB_DSN"
SELECT timeframe, count(*) AS linhas,
       min(bucket_time) AS inicio, max(bucket_time) AS fim
FROM pool_metrics
GROUP BY timeframe
ORDER BY timeframe;
```

### 5.4. Fase 2 — gerar o dataset (`build_dataset.py`)

```bash
# 5m, horizonte de 24 candles (2h), banda LP de 1%:
python3 ml/build_dataset.py --timeframe 5m --horizon 24 --range-pct 1.0

# vários timeframes de uma vez, com CSV de debug:
python3 ml/build_dataset.py --timeframe 1m --timeframe 5m --csv
```

Saída: `ml/datasets/training_<timeframe>.parquet`.

Parâmetros que definem o alvo (vale testar valores):

| Flag | O que é | Padrão |
|---|---|---|
| `--horizon` | nº de candles à frente = tempo de permanência da posição | 24 |
| `--range-pct` | largura da banda LP em % (1.0 ⇒ ±0,5%) | 1.0 |
| `--in-range-min-frac` | tolerância do rótulo "quase sempre em range" | 0.95 |
| `--pool` | restringe a uma pool (repetível) | todas |

**O que observar na saída:** a linha `label_in_range=1 (%)` por pool. Se vier
~0%, a banda está estreita demais para o horizonte — afrouxe (`--range-pct 1.5`)
ou encurte o `--horizon`. O ideal é ter algo entre ~5% e ~15% de positivos.
Foque nos timeframes granulares (`1m`/`5m`); em `1h` há poucas linhas (~4.300
para 6 meses), insuficiente para treino robusto.

### 5.5. Fase 3 — treinar (`train.py`)

```bash
# embargo DEVE ser >= o --horizon usado no build_dataset (evita vazamento pelos labels)
python3 ml/train.py --timeframe 5m --embargo 24
```

Saída em `ml/models/`: `clf_<tf>.txt` (classificador), `q10_<tf>.txt` e
`q90_<tf>.txt` (bandas low/high) e `metrics_<tf>.json` (relatório).

Flags úteis:

| Flag | O que é | Padrão |
|---|---|---|
| `--embargo` | linhas purgadas entre treino e validação (≥ `--horizon`) | 24 |
| `--n-splits` | folds da validação walk-forward | 5 |
| `--test-frac` | fração final (por tempo) reservada como holdout | 0.15 |
| `--label` | alvo do classificador (`label_in_range` / `label_mostly_in_range`) | `label_in_range` |
| `--low-q` / `--high-q` | quantis das bandas inferior/superior | 0.10 / 0.90 |

**O que observar em `metrics_<tf>.json`:**

- `holdout.clf_auc` — qualidade do "entrar ou não"; **> 0,5** já indica sinal real.
- `holdout.interval_coverage` — fração do movimento real que coube na banda
  prevista; para p10–p90 o alvo natural é **≈ 0,80**. Muito abaixo = banda
  estreita demais; muito acima = banda larga demais (posição pouco eficiente).
- nº de positivos do label — se for baixíssimo, volte à Fase 2 e ajuste
  `--range-pct`/`--horizon`.
- `feature_importance_gain` — quais sinais o modelo mais usa (sanidade).

### 5.6. Ciclo de re-treino

Conforme a aplicação acumula mais dados ao vivo, repita 5.4 → 5.5 periodicamente
(semanal/mensal) para reaproveitar o histórico crescente. Cada rodada regenera o
Parquet e retreina os modelos do zero — não há estado a manter entre execuções.

## 6. Agente de execução (Orca Whirlpools)

O serviço `executor/` (Node, container `defi-executor`) fecha o ciclo: consome os
sinais de `pool_signals` e **abre/fecha posições de range LP na Orca**, gravando
cada operação em `agent_positions` para o relatório de performance no dashboard.

### Como decide

- **Abertura:** sinal `enter=true` fresco (últimos 3 candles) com confiança ≥
  mínima, respeitando `max_open_pools` e 1 posição por pool. Banda = [p10, p90]
  do sinal.
- **Fechamento:** rompimento da banda (com preço fresco) OU fim do horizonte
  (~2h) — o que vier primeiro. Fechamento manual pelo dashboard tem precedência.
- **Kill switch:** desabilitar a abertura automática NUNCA abandona posições —
  o gerenciamento/fechamento continua rodando.

### Modos

- **paper (padrão):** simula com matemática CLMM exata (IL incluída; fees
  estimadas pelo modelo do backtest). Zero risco — valida o ciclo completo.
- **live:** envia transações reais via SDK da Orca. Exige `RPC_URL` e o keypair.

### Segurança da carteira

A chave privada fica APENAS na VM: `wallet/keypair.json` (formato `id.json` do
`solana-keygen`), montado read-only no container. Nunca passa pelo navegador nem
pelo banco; o front exibe só o endereço público e o saldo. O diretório `wallet/`
está no `.gitignore` — **nunca commitar**. No modo live a carteira precisa ter
os dois tokens do par (ex.: SOL e USDC).

### Configuração pelo dashboard

Painel "Agente · Execução Automática": liga/desliga a abertura automática, modo
paper/live, capital por pool, máximo de pools simultâneas, confiança mínima e
slippage. Tabela de posições (com fechamento manual) e cards de performance
(PnL total, win rate, rendimento médio) por modo.

### Gerar a carteira do agente (`wallet/keypair.json`)

Use uma carteira **dedicada ao agente** (nunca a principal), com só o capital
que ele vai operar. Na VM:

```bash
# 1. Instalar a CLI da Solana (se ainda não tiver)
sh -c "$(curl -sSfL https://release.anza.xyz/stable/install)"
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"

# 2. Gerar o keypair direto no diretório montado pelo executor
mkdir -p ~/monitoring-defi/wallet
solana-keygen new --outfile ~/monitoring-defi/wallet/keypair.json
# (guarde a seed phrase exibida em local seguro OFFLINE — é o único backup)

# 3. Restringir permissões e conferir o endereço público
chmod 600 ~/monitoring-defi/wallet/keypair.json
solana-keygen pubkey ~/monitoring-defi/wallet/keypair.json
```

Alternativa — usar uma carteira existente (ex.: criada no Phantom): recupere
pela seed phrase, sem colar chave em lugar nenhum:
`solana-keygen recover -o ~/monitoring-defi/wallet/keypair.json` (prompt pede a
seed). Depois `chmod 600` igual.

**Financiar:** transfira para o endereço público: (a) **SOL** para gás + o lado
base das posições e (b) **USDC** para o lado quote. Para operar $100/pool em
SOL/USDC, algo como ~0,05 SOL de gás + ~50% do capital em cada token é um bom
ponto de partida. Confira com `solana balance <PUBKEY>` e no Solscan.

Reinicie o executor (`docker compose restart executor`) — o endereço e o saldo
aparecem no painel do dashboard em ~1 min.

### Subir

```bash
docker compose up -d --build executor frontend
docker compose logs -f executor
```

Recomendação: rode em **paper por 1–2 semanas** e compare o relatório com o
backtest antes de habilitar o live — e comece o live com capital pequeno.

## 7. Testes

Testes unitários em `src/test/java` (JUnit 5 + AssertJ), sem dependência de banco:

```bash
./mvnw test
```

| Classe | Cobre |
|---|---|
| `IndicatorEngineTest` | ATR, Bollinger/Squeeze, Volume Profile/PoC, trendSlope |
| `MarketClassifierTest` | os 5 regimes do switch pattern-matching + precedência |
| `CandleAggregatorTest` | agregação OHLCV e fechamento de candle por bucket |
| `WhirlpoolDecoderTest` | Base58, leitura Borsh (u64/i32/u128), preço via `sqrtPrice` e extração de campos da Whirlpool |
| `MacroCalendarServiceTest` | janela de 24h e mapeamento do feed macro |
