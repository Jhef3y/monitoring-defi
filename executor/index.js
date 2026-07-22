/**
 * index.js — loop principal do executor do agente.
 *
 * A cada EXEC_INTERVAL_MS:
 *   1. Garante o schema (agent_config / agent_positions) e carrega a config.
 *   2. GERENCIA posições abertas: fecha por MANUAL, BREAKOUT (preço fora da
 *      banda, com preço fresco) ou HORIZON (tempo esgotado). O gerenciamento
 *      roda SEMPRE, mesmo com auto_open desabilitado (kill switch só para de
 *      abrir, nunca abandona posição).
 *   3. ABRE novas posições a partir de pool_signals (enter=true, confiança
 *      >= mínima, sinal fresco), respeitando max_open_pools e 1 posição por
 *      pool. Modo paper simula com matemática CLMM; modo live envia transação
 *      via Orca SDK (orca.js).
 *
 * PnL registrado no fechamento:
 *   exit_value = valor da posição ao preço de saída (nas moedas operadas)
 *   pnl_usd    = exit_value + fees - capital     (fees: estimadas no paper,
 *                incluídas na diferença de saldo no live)
 */
import {
  ensureSchema, loadConfig, openPositions, openCount, hasOpenForPool,
  latestPrice, freshEnterSignals, insertPosition, closePosition, markError,
  updateWallet,
} from './db.js';
import { positionAmounts, positionValue, liquidityForCapital, estimateFees } from './clmm.js';
import { decideClose, decideOpen, TF_MINUTES } from './decide.js';

const INTERVAL_MS = Number(process.env.EXEC_INTERVAL_MS || 60000);
const SIGNAL_FRESH_MULT = 3;          // sinal válido por 3 candles do timeframe

const log = (...a) => console.log(new Date().toISOString(), '-', ...a);

// ---------------------------------------------------------------------------
// Fechamento
// ---------------------------------------------------------------------------
async function handleClose(pos, cfg, tick, reason) {
  const exitPrice = tick ? tick.price : Number(pos.entry_price);
  const minutesOpen = (Date.now() - new Date(pos.opened_at)) / 60000;

  if (pos.mode === 'paper') {
    const L = Number(pos.liquidity);
    const lo = Number(pos.range_low), hi = Number(pos.range_high);
    const { amountA, amountB } = positionAmounts(L, exitPrice, lo, hi);
    const exitValue = positionValue(L, exitPrice, lo, hi);
    const fees = estimateFees(Number(pos.capital_usd), Number(cfg.fee_apr_ref),
      Number(cfg.ref_width), lo, hi, Number(pos.entry_price), minutesOpen);
    const pnl = exitValue + fees - Number(pos.capital_usd);
    await closePosition(pos.id, {
      exit_price: exitPrice, close_reason: reason,
      exit_amount_a: amountA, exit_amount_b: amountB,
      exit_value_usd: exitValue, fees_est_usd: fees, pnl_usd: pnl,
      yield_pct: pnl / Number(pos.capital_usd),
    });
    log(`[paper] fechou #${pos.id} ${pos.symbol} (${reason}) pnl=$${pnl.toFixed(4)}`);
    return;
  }

  // live
  const orca = await import('./orca.js');
  const res = await orca.closePosition(pos.pool_address, pos.position_mint,
    Number(cfg.slippage_bps));
  const exitValue = res.amountA * exitPrice + res.amountB;   // fees já incluídas
  const pnl = exitValue - Number(pos.capital_usd);
  await closePosition(pos.id, {
    exit_price: exitPrice, close_reason: reason,
    exit_amount_a: res.amountA, exit_amount_b: res.amountB,
    exit_value_usd: exitValue, fees_est_usd: null, pnl_usd: pnl,
    yield_pct: pnl / Number(pos.capital_usd),
    close_tx: res.txSignatures.join(','),
  });
  log(`[live] fechou #${pos.id} ${pos.symbol} (${reason}) pnl=$${pnl.toFixed(4)} tx=${res.txSignatures[0]}`);
}

async function managePositions(cfg) {
  const now = new Date();
  for (const pos of await openPositions()) {
    try {
      const tick = await latestPrice(pos.pool_address, cfg.timeframe);
      const { close, reason } = decideClose(pos, tick, now);
      if (close) await handleClose(pos, cfg, tick, reason);
    } catch (e) {
      log(`!! erro ao gerenciar posição #${pos.id}: ${e.message}`);
      await markError(pos.id, e.message).catch(() => {});
    }
  }
}

// ---------------------------------------------------------------------------
// Abertura
// ---------------------------------------------------------------------------
async function handleOpen(sig, cfg) {
  const tfMin = TF_MINUTES[cfg.timeframe] || 5;
  const expiresAt = new Date(Date.now() + cfg.horizon_candles * tfMin * 60000);
  const px = Number(sig.close);
  const lo = Number(sig.range_low), hi = Number(sig.range_high);
  const capital = Number(cfg.capital_per_pool_usd);

  const base = {
    pool_address: sig.pool_address, symbol: sig.symbol || sig.pool_address,
    mode: cfg.mode, signal_time: sig.signal_time, confidence: sig.confidence,
    expires_at: expiresAt, entry_price: px, range_low: lo, range_high: hi,
    capital_usd: capital,
  };

  if (cfg.mode === 'paper') {
    const { L, amountA, amountB } = liquidityForCapital(capital, px, lo, hi);
    const id = await insertPosition({
      ...base, entry_amount_a: amountA, entry_amount_b: amountB, liquidity: L,
    });
    log(`[paper] abriu #${id} ${base.symbol} conf=${Number(sig.confidence).toFixed(3)} ` +
        `banda [${lo.toFixed(4)}, ${hi.toFixed(4)}] capital=$${capital}`);
    return;
  }

  // live
  const orca = await import('./orca.js');
  const res = await orca.openPosition(sig.pool_address, lo, hi, capital,
    Number(cfg.slippage_bps));
  const id = await insertPosition({
    ...base, entry_amount_a: res.amountA, entry_amount_b: res.amountB,
    liquidity: null, position_mint: res.positionMint, open_tx: res.txSignature,
  });
  log(`[live] abriu #${id} ${base.symbol} mint=${res.positionMint} tx=${res.txSignature}`);
}

async function tryOpen(cfg) {
  const tfMin = TF_MINUTES[cfg.timeframe] || 5;
  const signals = await freshEnterSignals(
    cfg.timeframe, Number(cfg.min_confidence), SIGNAL_FRESH_MULT * tfMin);

  for (const sig of signals) {
    try {
      const nOpen = await openCount();
      const dup = await hasOpenForPool(sig.pool_address);
      const { open, skip } = decideOpen(sig, cfg, nOpen, dup);
      if (!open) { if (skip !== 'auto_open desabilitado') log(`skip ${sig.symbol || sig.pool_address}: ${skip}`); continue; }
      await handleOpen(sig, cfg);
    } catch (e) {
      log(`!! erro ao abrir para ${sig.pool_address}: ${e.message}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Carteira (info para o front — apenas endereço público e saldo)
// ---------------------------------------------------------------------------
let walletCheckedAt = 0;
async function refreshWallet() {
  if (Date.now() - walletCheckedAt < 5 * 60000) return;   // a cada 5 min
  walletCheckedAt = Date.now();
  if (!process.env.WALLET_KEYPAIR_PATH || !process.env.RPC_URL) return;
  try {
    const orca = await import('./orca.js');
    const info = await orca.walletInfo();
    await updateWallet(info.pubkey, info.sol);
  } catch (e) {
    log(`aviso: não foi possível ler a carteira (${e.message})`);
  }
}

// ---------------------------------------------------------------------------
// Loop
// ---------------------------------------------------------------------------
async function cycle() {
  try {
    await ensureSchema();
    const cfg = await loadConfig();
    if (!cfg) return;
    await refreshWallet();
    await managePositions(cfg);         // sempre gerencia (kill switch só p/ abrir)
    await tryOpen(cfg);
  } catch (e) {
    log(`!! erro no ciclo: ${e.message}`);
  }
}

log(`Executor do agente iniciado (intervalo ${INTERVAL_MS / 1000}s)`);
await cycle();
setInterval(cycle, INTERVAL_MS);
