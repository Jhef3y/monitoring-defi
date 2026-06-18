package com.defi.monitor.solana;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Decodificação Borsh do layout (Anchor) das contas da Orca:
 * <ul>
 *   <li>{@link #decodeWhirlpool(byte[])} — estado da pool (sqrtPrice, tick, liquidez, mints, cofres);</li>
 *   <li>{@link #decodeSplAmount(byte[])} — saldo de uma SPL Token Account (cofre);</li>
 *   <li>{@link #decodeMintDecimals(byte[])} — decimais de um Mint;</li>
 *   <li>{@link #priceBPerA(BigInteger, int, int)} — preço humano a partir do sqrtPrice Q64.64.</li>
 * </ul>
 *
 * <p>Offsets do struct {@code Whirlpool} (após o discriminator Anchor de 8 bytes):
 * <pre>
 * 8   whirlpools_config (32)      40  whirlpool_bump (1)
 * 41  tick_spacing (2)            43  tick_spacing_seed (2)
 * 45  fee_rate (u16)              47  protocol_fee_rate (u16)
 * 49  liquidity (u128)           65  sqrt_price (u128)
 * 81  tick_current_index (i32)   85  protocol_fee_owed_a (u64)
 * 93  protocol_fee_owed_b (u64) 101  token_mint_a (32)
 * 133 token_vault_a (32)        165  fee_growth_global_a (u128)
 * 181 token_mint_b (32)         213  token_vault_b (32)
 * </pre>
 */
public final class WhirlpoolDecoder {

    private static final BigDecimal Q64 = new BigDecimal(BigInteger.TWO.pow(64));

    private WhirlpoolDecoder() {}

    public static WhirlpoolAccount decodeWhirlpool(byte[] d) {
        if (d.length < 245) throw new IllegalArgumentException("conta Whirlpool muito curta: " + d.length);
        int feeRate = readU16LE(d, 45);
        BigInteger liquidity = readU128LE(d, 49);
        BigInteger sqrtPrice = readU128LE(d, 65);
        int tick = readI32LE(d, 81);
        String mintA = readPubkey(d, 101);
        String vaultA = readPubkey(d, 133);
        String mintB = readPubkey(d, 181);
        String vaultB = readPubkey(d, 213);
        return new WhirlpoolAccount(liquidity, sqrtPrice, tick, feeRate, mintA, vaultA, mintB, vaultB);
    }

    /** Saldo bruto (u64) de uma SPL Token Account — campo {@code amount} no offset 64. */
    public static BigInteger decodeSplAmount(byte[] d) {
        if (d.length < 72) throw new IllegalArgumentException("token account inválida");
        return readU64LE(d, 64);
    }

    /** Decimais (u8 no offset 44) de um Mint SPL. */
    public static int decodeMintDecimals(byte[] d) {
        if (d.length < 45) throw new IllegalArgumentException("mint inválido");
        return d[44] & 0xFF;
    }

    /**
     * Preço humano de tokenA em termos de tokenB:
     * {@code (sqrtPrice / 2^64)^2 * 10^(decA - decB)}.
     */
    public static double priceBPerA(BigInteger sqrtPrice, int decA, int decB) {
        BigDecimal ratio = new BigDecimal(sqrtPrice).divide(Q64, 40, RoundingMode.HALF_UP);
        BigDecimal raw = ratio.multiply(ratio);                 // tokenB/tokenA (bruto)
        return raw.movePointRight(decA - decB).doubleValue();   // ajuste de decimais
    }

    // ---------------- leitura de tipos Borsh (little-endian) ----------------

    static int readU16LE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    static int readI32LE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    static BigInteger readU64LE(byte[] d, int off) {
        return leUnsigned(d, off, 8);
    }

    static BigInteger readU128LE(byte[] d, int off) {
        return leUnsigned(d, off, 16);
    }

    private static BigInteger leUnsigned(byte[] d, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) be[len - 1 - i] = d[off + i];   // LE -> BE
        return new BigInteger(1, be);
    }

    static String readPubkey(byte[] d, int off) {
        return Base58.encode(Arrays.copyOfRange(d, off, off + 32));
    }
}
