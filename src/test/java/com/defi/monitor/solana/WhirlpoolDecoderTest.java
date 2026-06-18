package com.defi.monitor.solana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WhirlpoolDecoderTest {

    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    // ---------- Base58 ----------

    @Test
    @DisplayName("Base58: round-trip do mint da USDC (32 bytes)")
    void base58RoundTrip() {
        byte[] raw = Base58.decode(USDC_MINT);
        assertThat(raw).hasSize(32);
        assertThat(Base58.encode(raw)).isEqualTo(USDC_MINT);
    }

    // ---------- preço ----------

    @Test
    @DisplayName("priceBPerA: sqrtPrice = 2^64 e decimais iguais => 1.0")
    void priceUnit() {
        BigInteger q64 = BigInteger.TWO.pow(64);
        assertThat(WhirlpoolDecoder.priceBPerA(q64, 6, 6)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("priceBPerA: ajuste de decimais aplica 10^(decA-decB)")
    void priceDecimalsAdjustment() {
        BigInteger q64 = BigInteger.TWO.pow(64);
        // decA=9 (SOL), decB=6 (USDC) => 1 * 10^(9-6) = 1000
        assertThat(WhirlpoolDecoder.priceBPerA(q64, 9, 6)).isCloseTo(1000.0, within(1e-6));
    }

    // ---------- leitura Borsh ----------

    @Test
    @DisplayName("readU64LE / readI32LE / readU128LE em little-endian")
    void littleEndianReaders() {
        byte[] b = new byte[16];
        putU64LE(b, 0, 1_000_000L);
        assertThat(WhirlpoolDecoder.readU64LE(b, 0)).isEqualTo(BigInteger.valueOf(1_000_000L));

        byte[] i = new byte[4];
        putI32LE(i, 0, -42);
        assertThat(WhirlpoolDecoder.readI32LE(i, 0)).isEqualTo(-42);

        byte[] u = new byte[16];
        putU128LE(u, 0, BigInteger.TWO.pow(80));
        assertThat(WhirlpoolDecoder.readU128LE(u, 0)).isEqualTo(BigInteger.TWO.pow(80));
    }

    @Test
    @DisplayName("decodeSplAmount lê o saldo (u64) no offset 64")
    void splAmount() {
        byte[] acc = new byte[165];
        putU64LE(acc, 64, 123_456_789L);
        assertThat(WhirlpoolDecoder.decodeSplAmount(acc)).isEqualTo(BigInteger.valueOf(123_456_789L));
    }

    @Test
    @DisplayName("decodeMintDecimals lê decimais (u8) no offset 44")
    void mintDecimals() {
        byte[] mint = new byte[82];
        mint[44] = 9;
        assertThat(WhirlpoolDecoder.decodeMintDecimals(mint)).isEqualTo(9);
    }

    @Test
    @DisplayName("decodeWhirlpool extrai sqrtPrice, tick, liquidez e pubkeys nos offsets corretos")
    void decodeWhirlpoolFields() {
        byte[] d = new byte[654];
        putU128LE(d, 49, BigInteger.valueOf(5_000_000L));   // liquidity
        putU128LE(d, 65, BigInteger.TWO.pow(64));           // sqrt_price = 2^64
        putI32LE(d, 81, -123);                              // tick_current_index
        byte[] usdc = Base58.decode(USDC_MINT);
        System.arraycopy(usdc, 0, d, 181, 32);              // token_mint_b = USDC

        WhirlpoolAccount w = WhirlpoolDecoder.decodeWhirlpool(d);
        assertThat(w.liquidity()).isEqualTo(BigInteger.valueOf(5_000_000L));
        assertThat(w.sqrtPrice()).isEqualTo(BigInteger.TWO.pow(64));
        assertThat(w.tickCurrentIndex()).isEqualTo(-123);
        assertThat(w.mintB()).isEqualTo(USDC_MINT);
    }

    // ---------- helpers ----------

    private static void putU64LE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
    private static void putI32LE(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
    private static void putU128LE(byte[] b, int off, BigInteger v) {
        byte[] be = v.toByteArray();               // big-endian, possivelmente com byte de sinal
        for (int i = 0; i < be.length; i++) {
            int leIndex = be.length - 1 - i;
            if (i < 16) b[off + i] = be[leIndex];
        }
    }
}
