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

## 5. Geração do dataset de treino (Fase 2)

O script `ml/build_dataset.py` é um job **batch offline** (não faz parte do caminho
em tempo real): lê o OHLCV gravado em `pool_metrics`, recalcula features *causais*
e gera labels *forward-looking* (janela de range, bandas high/low, `label_in_range`),
exportando um Parquet por timeframe pronto para treino (LightGBM/XGBoost).

### Rodar local com túnel SSH para o banco da VM (GCP)

A aplicação e o TimescaleDB rodam numa VM do Compute Engine. Para extrair o dataset
no seu notebook, abra um túnel SSH mapeando a porta do Postgres da VM (`5432`) para
uma porta local (`5433`):

```bash
# Terminal 1 — mantém o túnel aberto (-N não abre shell; Ctrl-C encerra):
gcloud compute ssh NOME_DA_INSTANCIA --zone SUA_ZONA -- -N -L 5433:localhost:5432
```

```bash
# Terminal 2 — com o túnel ativo, o banco da VM aparece em localhost:5433:
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy pyarrow psycopg2-binary

export DB_DSN='postgresql://defi:defi@localhost:5433/defi_timeseries'

# 5m, horizonte de 24 candles (2h), banda LP de 1%:
python3 ml/build_dataset.py --timeframe 5m --horizon 24 --range-pct 1.0

# todas as pools/timeframes, com CSV de debug:
python3 ml/build_dataset.py --timeframe 1m --timeframe 5m --timeframe 1h --csv
```

> Saída em `ml/datasets/training_<timeframe>.parquet`. Os parâmetros `--horizon`
> (tempo de permanência da posição, em candles) e `--range-pct` (largura do range LP)
> definem o alvo — vale testar valores diferentes.

**Alternativa — rodar direto na VM:** como o Timescale escuta em `localhost:5432`
na própria VM, dá para dispensar o túnel: `gcloud compute ssh NOME_DA_INSTANCIA`,
instalar as dependências lá e usar `DB_DSN=postgresql://defi:defi@localhost:5432/defi_timeseries`.

> Não é um serviço contínuo: roda **uma vez** para criar o dataset inicial e depois
> **periodicamente** (semanal/mensal), antes de cada re-treino, para incorporar os
> dados novos coletados ao vivo.

## 6. Testes

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
