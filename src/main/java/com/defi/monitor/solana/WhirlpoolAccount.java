package com.defi.monitor.solana;

import java.math.BigInteger;

/**
 * Estado decodificado de uma conta Whirlpool (Orca), Record imutável (Java 25).
 *
 * @param liquidity         liquidez ativa in-range (u128)
 * @param sqrtPrice         preço Q64.64 (u128)
 * @param tickCurrentIndex  tick atual
 * @param feeRate           taxa em centésimos de bps (fee_rate / 1_000_000)
 * @param mintA             pubkey do token A (base58)
 * @param vaultA            cofre do token A (base58)
 * @param mintB             pubkey do token B (base58)
 * @param vaultB            cofre do token B (base58)
 */
public record WhirlpoolAccount(
        BigInteger liquidity,
        BigInteger sqrtPrice,
        int tickCurrentIndex,
        int feeRate,
        String mintA,
        String vaultA,
        String mintB,
        String vaultB
) {}
