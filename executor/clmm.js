/**
 * clmm.js — matemática de liquidez concentrada (Uniswap v3 / Orca Whirlpools).
 *
 * Convenção: preço p = tokenB por tokenA (ex.: USDC por SOL), range [pa, pb].
 * Fórmulas padrão (com sqrt de preço):
 *   amountA(L, p) = L * (sqrt(pb) - sqrt(p)) / (sqrt(p) * sqrt(pb))   [p < pb]
 *   amountB(L, p) = L * (sqrt(p) - sqrt(pa))                          [p > pa]
 * Fora do range a posição fica 100% num dos lados.
 * Usado pelo modo paper (simulação exata de IL) e pelo modo live (proporção
 * de depósito A/B). Precisão double é suficiente para relatório/decisão.
 */

/** Quantidades de tokens de uma posição com liquidez L ao preço p (com clamp). */
export function positionAmounts(L, p, pa, pb) {
  const sa = Math.sqrt(pa), sb = Math.sqrt(pb);
  const pc = Math.min(Math.max(p, pa), pb);      // clamp no range
  const sp = Math.sqrt(pc);
  const amountA = pc >= pb ? 0 : L * (sb - sp) / (sp * sb);
  const amountB = pc <= pa ? 0 : L * (sp - sa);
  return { amountA, amountB };
}

/** Valor da posição em tokenB (quote) ao preço p. */
export function positionValue(L, p, pa, pb) {
  const { amountA, amountB } = positionAmounts(L, p, pa, pb);
  return amountA * p + amountB;
}

/**
 * Liquidez L necessária para investir `capital` (em tokenB/USD) ao preço p0
 * num range [pa, pb]; devolve também os amounts de entrada e a fração do
 * capital no lado B (útil p/ montar o depósito no modo live).
 */
export function liquidityForCapital(capital, p0, pa, pb) {
  if (!(pa < p0 && p0 < pb)) {
    throw new Error(`preço de entrada ${p0} fora do range [${pa}, ${pb}]`);
  }
  const sa = Math.sqrt(pa), sb = Math.sqrt(pb), sp = Math.sqrt(p0);
  // custo (em tokenB) de 1 unidade de L:
  const costPerL = ((sb - sp) / (sp * sb)) * p0 + (sp - sa);
  const L = capital / costPerL;
  const { amountA, amountB } = positionAmounts(L, p0, pa, pb);
  return { L, amountA, amountB, shareB: amountB / capital };
}

/**
 * Estimativa de fees (modo paper): APR de referência medido numa banda
 * `refWidth`, escalado pela concentração (bandas mais estreitas rendem mais
 * por dólar) e pelo tempo decorrido. Espelha o modelo do backtest.
 */
export function estimateFees(capital, aprRef, refWidth, rangeLow, rangeHigh,
                             entryPrice, minutesOpen) {
  const width = (rangeHigh - rangeLow) / entryPrice;
  const mult = width > 1e-9 ? refWidth / width : 1.0;
  const years = minutesOpen / (365 * 24 * 60);
  return capital * aprRef * mult * years;
}
