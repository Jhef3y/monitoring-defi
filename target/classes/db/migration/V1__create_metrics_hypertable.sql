-- =====================================================================
-- V1 — Esquema de séries temporais para monitoramento de pools Uniswap V3
-- Banco: PostgreSQL + extensão TimescaleDB
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ---------------------------------------------------------------------
-- Dimensão: pools monitoradas (tabela relacional comum)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pool (
    address          TEXT PRIMARY KEY,            -- conta da Whirlpool (base58, case-sensitive)
    symbol           TEXT NOT NULL,               -- ex.: SOL/USDC
    fee_tier_bps     INTEGER,                     -- opcional (Orca usa tick_spacing/fee_rate)
    token0_decimals  SMALLINT,                    -- preenchido na descoberta on-chain
    token1_decimals  SMALLINT,                    -- preenchido na descoberta on-chain
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Hypertable principal: métrica consolidada por candle/timeframe
-- Cada linha = um ponto de série temporal (pool + timeframe + bucket)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pool_metrics (
    bucket_time      TIMESTAMPTZ      NOT NULL,   -- início do candle (chave temporal)
    pool_address     TEXT             NOT NULL,
    timeframe        TEXT             NOT NULL,   -- '1m' | '5m' | '1h'

    -- OHLCV derivado dos eventos Swap
    open             DOUBLE PRECISION NOT NULL,
    high             DOUBLE PRECISION NOT NULL,
    low              DOUBLE PRECISION NOT NULL,
    close            DOUBLE PRECISION NOT NULL,
    volume_token0    DOUBLE PRECISION NOT NULL DEFAULT 0,
    volume_token1    DOUBLE PRECISION NOT NULL DEFAULT 0,
    swap_count       INTEGER          NOT NULL DEFAULT 0,

    -- Métricas fundamentais (The Graph, atualizadas de hora em hora)
    tvl_usd          DOUBLE PRECISION,
    volume_24h_usd   DOUBLE PRECISION,
    fees_24h_usd     DOUBLE PRECISION,
    volume_tvl_ratio DOUBLE PRECISION,            -- eficiência de geração de taxas

    -- Indicadores técnicos calculados em tempo real
    atr_14           DOUBLE PRECISION,
    bb_middle        DOUBLE PRECISION,            -- SMA(20)
    bb_upper         DOUBLE PRECISION,
    bb_lower         DOUBLE PRECISION,
    bb_bandwidth     DOUBLE PRECISION,            -- (upper-lower)/middle
    is_squeeze       BOOLEAN          NOT NULL DEFAULT FALSE,

    -- Volume Profile / Point of Control das últimas 24h
    poc_price        DOUBLE PRECISION,            -- preço de maior consenso
    value_area_high  DOUBLE PRECISION,
    value_area_low   DOUBLE PRECISION,

    -- Contexto macro
    macro_high_impact BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Classificação do regime de mercado (motor pattern-matching)
    market_state     TEXT,                        -- LATERAL | TENDENCIA_ALTA | TENDENCIA_BAIXA | VOLATILIDADE_EXTREMA | INDEFINIDO

    inserted_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),

    CONSTRAINT fk_pool FOREIGN KEY (pool_address) REFERENCES pool(address),
    -- Chave de negócio: a chave primária DEVE conter a coluna de particionamento
    PRIMARY KEY (pool_address, timeframe, bucket_time)
);

-- Converte em hypertable particionada por tempo (chunks de 1 dia)
SELECT create_hypertable(
    'pool_metrics',
    by_range('bucket_time', INTERVAL '1 day'),
    if_not_exists => TRUE
);

-- Particionamento secundário por hash do par (pool, timeframe) → paralelismo de I/O
SELECT add_dimension(
    'pool_metrics',
    by_hash('pool_address', 4),
    if_not_exists => TRUE
);

-- Índice para as consultas mais frequentes do treino do agente de IA
CREATE INDEX IF NOT EXISTS idx_metrics_pool_tf_time
    ON pool_metrics (pool_address, timeframe, bucket_time DESC);

-- Índice parcial para varrer rapidamente os períodos de squeeze (lateralização)
CREATE INDEX IF NOT EXISTS idx_metrics_squeeze
    ON pool_metrics (pool_address, bucket_time DESC)
    WHERE is_squeeze = TRUE;

-- ---------------------------------------------------------------------
-- Compressão nativa do TimescaleDB: segmenta por pool/timeframe e ordena por tempo
-- Reduz drasticamente o armazenamento de dados de candles antigos.
-- ---------------------------------------------------------------------
ALTER TABLE pool_metrics SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'pool_address, timeframe',
    timescaledb.compress_orderby   = 'bucket_time DESC'
);

-- ---------------------------------------------------------------------
-- ATENÇÃO: as políticas de compressão/retenção (background jobs) e o
-- continuous aggregate do TimescaleDB NÃO podem ser criados dentro de uma
-- transação. O Flyway executa cada script numa transação por padrão, então
-- esses comandos foram movidos para a migração V2, configurada com
-- executeInTransaction=false (ver V2__continuous_aggregate.sql.conf).
-- Manter aqui faria toda a V1 sofrer rollback, deixando o schema sem as
-- tabelas e quebrando o restante da aplicação.
-- ---------------------------------------------------------------------
