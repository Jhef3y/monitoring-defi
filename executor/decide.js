/**
 * decide.js — regras de decisão do agente (funções puras, testáveis).
 */

const STALE_PRICE_MINUTES = 10;

/**
 * Decide se uma posição aberta deve ser fechada.
 * Política (definida com o usuário): fecha no ROMPIMENTO da banda OU ao
 * completar o HORIZONTE — o que vier primeiro. Fechamento MANUAL (flag do
 * front) tem precedência.
 *
 * @param pos   linha de agent_positions (range_low/high, expires_at, close_requested)
 * @param tick  { price, at } último preço conhecido (ou null se indisponível)
 * @param now   Date atual
 * @returns     { close: bool, reason: 'MANUAL'|'BREAKOUT'|'HORIZON'|null }
 */
export function decideClose(pos, tick, now) {
  if (pos.close_requested) return { close: true, reason: 'MANUAL' };

  const priceFresh = tick &&
    (now - new Date(tick.at)) / 60000 <= STALE_PRICE_MINUTES;

  // Rompimento exige preço FRESCO (não fechar por dado velho/outage)
  if (priceFresh &&
      (tick.price > Number(pos.range_high) || tick.price < Number(pos.range_low))) {
    return { close: true, reason: 'BREAKOUT' };
  }

  if (pos.expires_at && now >= new Date(pos.expires_at)) {
    return { close: true, reason: 'HORIZON' };
  }
  return { close: false, reason: null };
}

/**
 * Decide se um sinal deve virar uma posição nova.
 * @returns { open: bool, skip: string|null (motivo) }
 */
export function decideOpen(signal, cfg, nOpen, alreadyOpenForPool) {
  if (!cfg.auto_open_enabled) return { open: false, skip: 'auto_open desabilitado' };
  if (nOpen >= cfg.max_open_pools) return { open: false, skip: 'máximo de pools abertas' };
  if (alreadyOpenForPool) return { open: false, skip: 'pool já tem posição aberta' };
  if (Number(signal.confidence) < Number(cfg.min_confidence)) {
    return { open: false, skip: 'confiança abaixo do mínimo' };
  }
  const lo = Number(signal.range_low), hi = Number(signal.range_high),
        px = Number(signal.close);
  if (!(lo < px && px < hi)) return { open: false, skip: 'preço fora da banda do sinal' };
  return { open: true, skip: null };
}

export const TF_MINUTES = { '1m': 1, '5m': 5, '1h': 60 };
