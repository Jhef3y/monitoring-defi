package com.defi.monitor.dto;

/**
 * Métricas fundamentais de uma pool (Record imutável — Java 25), agora
 * calculadas on-chain a partir das reservas dos cofres da Whirlpool e do
 * preço corrente.
 *
 * @param poolAddress      conta da Whirlpool
 * @param tvlUsd           valor total bloqueado em USD (reservaUSDC + reservaBase × preço)
 * @param volume24hUsd     volume recente em USD (janela acumulada desde a última leitura)
 * @param fees24hUsd       taxas estimadas (volume × feeRate)
 * @param volumeTvlRatio   eficiência de geração de taxas (volume da janela / TVL)
 */
public record PoolFundamentals(
        String poolAddress,
        double tvlUsd,
        double volume24hUsd,
        double fees24hUsd,
        double volumeTvlRatio
) {}
