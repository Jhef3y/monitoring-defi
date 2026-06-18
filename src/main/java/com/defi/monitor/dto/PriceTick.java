package com.defi.monitor.dto;

import java.math.BigInteger;

/**
 * Tick de preço/volume normalizado (Record imutável — Java 25).
 *
 * <p>Unidade que alimenta o agregador OHLCV. Na ingestão Solana/Orca, é
 * produzido a cada atualização da conta da Whirlpool: o preço vem do
 * {@code sqrtPrice} (Q64.64) e os volumes vêm do delta dos cofres (vaults)
 * de cada token desde o tick anterior.
 *
 * @param poolAddress  conta da Whirlpool (base58)
 * @param price        preço do ativo base em USDC (≈ USD)
 * @param baseVolume   volume do token base desde o último tick (unidades humanas)
 * @param quoteVolume  volume em USDC desde o último tick
 * @param sqrtPrice    sqrtPrice bruto Q64.64 da Whirlpool (auditoria)
 * @param tick         tick atual da pool (índice de preço)
 * @param blockTimeSec timestamp aproximado (epoch s)
 */
public record PriceTick(
        String poolAddress,
        double price,
        double baseVolume,
        double quoteVolume,
        BigInteger sqrtPrice,
        int tick,
        long blockTimeSec
) {}
