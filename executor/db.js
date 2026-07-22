import pg from 'pg';

const { Pool } = pg;

export const db = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME || 'defi_timeseries',
  user: process.env.DB_USER || 'defi',
  password: process.env.DB_PASSWORD || 'defi',
  max: 5,
});

// ---------------------------------------------------------------------------
// DDL — mantido em sincronia com frontend/server.js (mesmo CREATE IF NOT EXISTS)
// ---------------------------------------------------------------------------
export const DDL = `
CREATE TABLE IF NOT EXISTS agent_config (
  id                   SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  auto_open_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
  mode                 TEXT NOT NULL DEFAULT 'paper' CHECK (mode IN ('paper','live')),
  capital_per_pool_usd DOUBLE PRECISION NOT NULL DEFAULT 100,
  max_open_pools       INTEGER NOT NULL DEFAULT 1,
  timeframe            TEXT NOT NULL DEFAULT '5m',
  horizon_candles      INTEGER NOT NULL DEFAULT 24,
  min_confidence       DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  slippage_bps         INTEGER NOT NULL DEFAULT 100,
  fee_apr_ref          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  ref_width            DOUBLE PRECISION NOT NULL DEFAULT 0.02,
  wallet_pubkey        TEXT,
  wallet_sol           DOUBLE PRECISION,
  wallet_updated_at    TIMESTAMPTZ,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO agent_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS agent_positions (
  id              BIGSERIAL PRIMARY KEY,
  pool_address    TEXT NOT NULL,
  symbol          TEXT,
  mode            TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN | CLOSED | ERROR
  signal_time     TIMESTAMPTZ,
  confidence      DOUBLE PRECISION,
  opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ,                    -- opened_at + horizonte
  entry_price     DOUBLE PRECISION,
  range_low       DOUBLE PRECISION,
  range_high      DOUBLE PRECISION,
  capital_usd     DOUBLE PRECISION,
  entry_amount_a  DOUBLE PRECISION,               -- token base (ex.: SOL)
  entry_amount_b  DOUBLE PRECISION,               -- token quote (ex.: USDC)
  liquidity       DOUBLE PRECISION,               -- L da matemática CLMM (paper)
  position_mint   TEXT,                           -- NFT da posição (live)
  open_tx         TEXT,
  close_requested BOOLEAN NOT NULL DEFAULT FALSE, -- fechamento manual via front
  closed_at       TIMESTAMPTZ,
  exit_price      DOUBLE PRECISION,
  close_reason    TEXT,                           -- BREAKOUT | HORIZON | MANUAL
  exit_amount_a   DOUBLE PRECISION,
  exit_amount_b   DOUBLE PRECISION,
  exit_value_usd  DOUBLE PRECISION,
  fees_est_usd    DOUBLE PRECISION,
  pnl_usd         DOUBLE PRECISION,
  yield_pct       DOUBLE PRECISION,
  close_tx        TEXT,
  error           TEXT
);

CREATE INDEX IF NOT EXISTS idx_agent_positions_status
  ON agent_positions (status, opened_at DESC);
`;

export async function ensureSchema() {
  await db.query(DDL);
}

export async function loadConfig() {
  const { rows } = await db.query('SELECT * FROM agent_config WHERE id = 1');
  return rows[0];
}

export async function openPositions() {
  const { rows } = await db.query(
    `SELECT * FROM agent_positions WHERE status = 'OPEN' ORDER BY opened_at`);
  return rows;
}

export async function openCount() {
  const { rows } = await db.query(
    `SELECT count(*)::int AS n FROM agent_positions WHERE status = 'OPEN'`);
  return rows[0].n;
}

export async function hasOpenForPool(poolAddress) {
  const { rows } = await db.query(
    `SELECT 1 FROM agent_positions WHERE status = 'OPEN' AND pool_address = $1 LIMIT 1`,
    [poolAddress]);
  return rows.length > 0;
}

/** Preço mais recente da pool (candle 1m mais novo; fallback para o timeframe dado). */
export async function latestPrice(poolAddress, fallbackTf) {
  for (const tf of ['1m', fallbackTf]) {
    const { rows } = await db.query(
      `SELECT close, bucket_time FROM pool_metrics
        WHERE pool_address = $1 AND timeframe = $2
        ORDER BY bucket_time DESC LIMIT 1`, [poolAddress, tf]);
    if (rows.length) return { price: Number(rows[0].close), at: rows[0].bucket_time };
  }
  return null;
}

/** Sinais de entrada recentes (mais novo por pool) acima da confiança mínima. */
export async function freshEnterSignals(timeframe, minConfidence, freshMinutes) {
  const { rows } = await db.query(
    `SELECT DISTINCT ON (s.pool_address)
            s.pool_address, s.signal_time, s.confidence, s.close,
            s.range_low, s.range_high, p.symbol
       FROM pool_signals s
       LEFT JOIN pool p ON p.address = s.pool_address
      WHERE s.timeframe = $1 AND s.enter = TRUE AND s.confidence >= $2
        AND s.signal_time >= now() - ($3 || ' minutes')::interval
      ORDER BY s.pool_address, s.signal_time DESC`,
    [timeframe, minConfidence, String(freshMinutes)]);
  return rows;
}

export async function insertPosition(row) {
  const { rows } = await db.query(
    `INSERT INTO agent_positions
       (pool_address, symbol, mode, status, signal_time, confidence, opened_at,
        expires_at, entry_price, range_low, range_high, capital_usd,
        entry_amount_a, entry_amount_b, liquidity, position_mint, open_tx)
     VALUES ($1,$2,$3,'OPEN',$4,$5,now(),$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
     RETURNING id`,
    [row.pool_address, row.symbol, row.mode, row.signal_time, row.confidence,
     row.expires_at, row.entry_price, row.range_low, row.range_high,
     row.capital_usd, row.entry_amount_a, row.entry_amount_b, row.liquidity,
     row.position_mint || null, row.open_tx || null]);
  return rows[0].id;
}

export async function closePosition(id, upd) {
  await db.query(
    `UPDATE agent_positions SET
       status = 'CLOSED', closed_at = now(), exit_price = $2, close_reason = $3,
       exit_amount_a = $4, exit_amount_b = $5, exit_value_usd = $6,
       fees_est_usd = $7, pnl_usd = $8, yield_pct = $9, close_tx = $10
     WHERE id = $1`,
    [id, upd.exit_price, upd.close_reason, upd.exit_amount_a, upd.exit_amount_b,
     upd.exit_value_usd, upd.fees_est_usd, upd.pnl_usd, upd.yield_pct,
     upd.close_tx || null]);
}

export async function markError(id, message) {
  await db.query(
    `UPDATE agent_positions SET status = 'ERROR', error = $2 WHERE id = $1`,
    [id, String(message).slice(0, 500)]);
}

export async function updateWallet(pubkey, sol) {
  await db.query(
    `UPDATE agent_config SET wallet_pubkey = $1, wallet_sol = $2,
            wallet_updated_at = now() WHERE id = 1`, [pubkey, sol]);
}
