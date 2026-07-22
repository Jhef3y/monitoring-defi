import express from 'express';
import pg from 'pg';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const { Pool } = pg;

// ---------------------------------------------------------------------------
// Conexão com o TimescaleDB (mesmas credenciais do app Java / docker-compose)
// ---------------------------------------------------------------------------
const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME || 'defi_timeseries',
  user: process.env.DB_USER || 'defi',
  password: process.env.DB_PASSWORD || 'defi',
  max: 10,
});

const app = express();
const PORT = Number(process.env.PORT || 3000);

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

/** Executa uma query e tolera o caso de a tabela ainda não existir (pipeline novo). */
async function safeQuery(res, sql, params = []) {
  try {
    const { rows } = await pool.query(sql, params);
    return rows;
  } catch (err) {
    if (err.code === '42P01') {        // undefined_table
      // Header HTTP so aceita ASCII (Latin-1) — sem acentos/travessao aqui.
      res.set('x-data-warning', 'tables not created yet - run the pipeline first');
      return [];
    }
    console.error('Erro de query:', err.message);
    res.status(500).json({ error: err.message });
    return null;
  }
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

app.get('/api/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok' });
  } catch (err) {
    res.status(503).json({ status: 'db_unavailable', error: err.message });
  }
});

// Lista de pools monitoradas
app.get('/api/pools', async (_req, res) => {
  const rows = await safeQuery(res,
    `SELECT address, symbol, fee_tier_bps, token0_decimals, token1_decimals
       FROM pool ORDER BY symbol`);
  if (rows) res.json(rows);
});

// Série temporal de OHLCV + indicadores de uma pool/timeframe
app.get('/api/metrics', async (req, res) => {
  const poolAddr = req.query.pool || '';   // endereços Solana são case-sensitive
  const timeframe = req.query.timeframe || '1m';
  const limit = Math.min(Number(req.query.limit || 500), 5000);
  if (!poolAddr) return res.status(400).json({ error: 'parâmetro "pool" é obrigatório' });

  const rows = await safeQuery(res,
    `SELECT bucket_time, open, high, low, close,
            volume_token0, volume_token1, swap_count,
            tvl_usd, volume_24h_usd, fees_24h_usd, volume_tvl_ratio,
            atr_14, bb_middle, bb_upper, bb_lower, bb_bandwidth, is_squeeze,
            poc_price, value_area_high, value_area_low,
            macro_high_impact, market_state
       FROM pool_metrics
      WHERE pool_address = $1 AND timeframe = $2
      ORDER BY bucket_time DESC
      LIMIT $3`, [poolAddr, timeframe, limit]);
  if (rows) res.json(rows.reverse());   // ordem cronológica para os gráficos
});

// Scanner: último estado de cada (pool, timeframe) — squeeze / regime de mercado
app.get('/api/scanner', async (req, res) => {
  const timeframe = req.query.timeframe;
  const params = [];
  let filter = '';
  if (timeframe) { params.push(timeframe); filter = 'WHERE timeframe = $1'; }

  const rows = await safeQuery(res,
    `SELECT p.symbol, m.*
       FROM (
         SELECT DISTINCT ON (pool_address, timeframe)
                pool_address, timeframe, bucket_time, close,
                bb_bandwidth, is_squeeze, atr_14, volume_tvl_ratio,
                tvl_usd, fees_24h_usd, poc_price, value_area_high, value_area_low,
                macro_high_impact, market_state
           FROM pool_metrics
           ${filter}
          ORDER BY pool_address, timeframe, bucket_time DESC
       ) m
       JOIN pool p ON p.address = m.pool_address
      ORDER BY m.is_squeeze DESC, p.symbol, m.timeframe`, params);
  if (rows) res.json(rows);
});

// Fundamentos mais recentes por pool (TVL / Volume 24h / fees / Volume-TVL)
app.get('/api/fundamentals', async (_req, res) => {
  const rows = await safeQuery(res,
    `SELECT p.symbol, f.*
       FROM (
         SELECT DISTINCT ON (pool_address)
                pool_address, bucket_time, tvl_usd, volume_24h_usd,
                fees_24h_usd, volume_tvl_ratio
           FROM pool_metrics
          WHERE tvl_usd IS NOT NULL
          ORDER BY pool_address, bucket_time DESC
       ) f
       JOIN pool p ON p.address = f.pool_address
      ORDER BY f.volume_tvl_ratio DESC NULLS LAST`);
  if (rows) res.json(rows);
});

// Sinais da IA (pool_signals) — série alinhada ao gráfico + último sinal
app.get('/api/signals', async (req, res) => {
  const poolAddr = req.query.pool || '';
  const timeframe = req.query.timeframe || '5m';
  const limit = Math.min(Number(req.query.limit || 500), 5000);
  if (!poolAddr) return res.status(400).json({ error: 'parâmetro "pool" é obrigatório' });

  const rows = await safeQuery(res,
    `SELECT signal_time, close, enter, confidence, threshold,
            range_low, range_high, range_low_pct, range_high_pct, model_tf
       FROM pool_signals
      WHERE pool_address = $1 AND timeframe = $2
      ORDER BY signal_time DESC
      LIMIT $3`, [poolAddr, timeframe, limit]);
  if (rows) res.json(rows.reverse());   // ordem cronológica para o overlay
});

// ---------------------------------------------------------------------------
// Agente de execução (agent_config / agent_positions — tabelas do executor)
// ---------------------------------------------------------------------------

// Campos editáveis pelo front (whitelist — o resto é do executor)
const CONFIG_FIELDS = [
  'auto_open_enabled', 'mode', 'capital_per_pool_usd', 'max_open_pools',
  'timeframe', 'horizon_candles', 'min_confidence', 'slippage_bps',
  'fee_apr_ref', 'ref_width',
];

app.get('/api/agent/config', async (_req, res) => {
  const rows = await safeQuery(res, 'SELECT * FROM agent_config WHERE id = 1');
  if (rows) res.json(rows[0] || null);
});

app.put('/api/agent/config', async (req, res) => {
  const sets = [];
  const params = [];
  for (const f of CONFIG_FIELDS) {
    if (req.body[f] !== undefined) {
      params.push(req.body[f]);
      sets.push(`${f} = $${params.length}`);
    }
  }
  if (!sets.length) return res.status(400).json({ error: 'nenhum campo valido' });
  if (req.body.mode && !['paper', 'live'].includes(req.body.mode)) {
    return res.status(400).json({ error: 'mode deve ser paper ou live' });
  }
  const rows = await safeQuery(res,
    `UPDATE agent_config SET ${sets.join(', ')}, updated_at = now()
      WHERE id = 1 RETURNING *`, params);
  if (rows) res.json(rows[0] || null);
});

app.get('/api/agent/positions', async (req, res) => {
  const limit = Math.min(Number(req.query.limit || 50), 500);
  const rows = await safeQuery(res,
    `SELECT * FROM agent_positions
      ORDER BY (status = 'OPEN') DESC, opened_at DESC
      LIMIT $1`, [limit]);
  if (rows) res.json(rows);
});

// Fechamento manual: o executor honra a flag no próximo ciclo
app.post('/api/agent/positions/:id/close', async (req, res) => {
  const rows = await safeQuery(res,
    `UPDATE agent_positions SET close_requested = TRUE
      WHERE id = $1 AND status = 'OPEN' RETURNING id`, [req.params.id]);
  if (rows) res.json({ requested: rows.length > 0 });
});

app.get('/api/agent/performance', async (_req, res) => {
  const rows = await safeQuery(res,
    `SELECT mode,
            count(*) FILTER (WHERE status = 'OPEN')   AS n_open,
            count(*) FILTER (WHERE status = 'CLOSED') AS n_closed,
            sum(pnl_usd)    FILTER (WHERE status = 'CLOSED') AS total_pnl,
            avg(yield_pct)  FILTER (WHERE status = 'CLOSED') AS avg_yield,
            sum(fees_est_usd) FILTER (WHERE status = 'CLOSED') AS total_fees_est,
            (count(*) FILTER (WHERE status = 'CLOSED' AND pnl_usd > 0))::float
              / NULLIF(count(*) FILTER (WHERE status = 'CLOSED'), 0) AS win_rate
       FROM agent_positions
      GROUP BY mode ORDER BY mode`);
  if (rows) res.json(rows);
});

app.listen(PORT, () => {
  console.log(`Dashboard DeFi em http://localhost:${PORT}`);
});
