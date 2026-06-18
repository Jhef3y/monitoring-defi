-- =====================================================================
-- V2 — Políticas de background e Continuous Aggregate (TimescaleDB)
--
-- Esta migração roda FORA de transação (ver V2__continuous_aggregate.sql.conf
-- com executeInTransaction=false), pois:
--   * add_compression_policy / add_retention_policy registram background jobs;
--   * CREATE MATERIALIZED VIEW ... WITH (timescaledb.continuous) não pode ser
--     executado dentro de um bloco de transação.
-- =====================================================================

-- Comprime automaticamente chunks com mais de 7 dias
SELECT add_compression_policy('pool_metrics', INTERVAL '7 days', if_not_exists => TRUE);

-- Retenção: descarta dados brutos com mais de 2 anos (ajuste conforme o treino)
SELECT add_retention_policy('pool_metrics', INTERVAL '730 days', if_not_exists => TRUE);

-- ---------------------------------------------------------------------
-- Continuous Aggregate: rollup de 1m -> 1h pré-materializado (consultas rápidas)
-- ---------------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS pool_metrics_1h_cagg
WITH (timescaledb.continuous) AS
SELECT
    pool_address,
    time_bucket(INTERVAL '1 hour', bucket_time) AS hour,
    first(open, bucket_time)  AS open,
    max(high)                 AS high,
    min(low)                  AS low,
    last(close, bucket_time)  AS close,
    sum(volume_token1)        AS volume_token1,
    avg(tvl_usd)              AS avg_tvl_usd,
    avg(volume_tvl_ratio)     AS avg_volume_tvl_ratio
FROM pool_metrics
WHERE timeframe = '1m'
GROUP BY pool_address, hour
WITH NO DATA;

SELECT add_continuous_aggregate_policy('pool_metrics_1h_cagg',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);
