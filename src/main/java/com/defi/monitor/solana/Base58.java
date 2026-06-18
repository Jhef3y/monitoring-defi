package com.defi.monitor.solana;

import java.util.Arrays;

/**
 * Codificação Base58 (alfabeto Bitcoin/Solana). Usada para converter pubkeys
 * de 32 bytes (decodificados das contas) em strings e vice-versa.
 */
public final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final char[] ALPHA = ALPHABET.toCharArray();
    private static final int[] INDEX = new int[128];

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHA.length; i++) INDEX[ALPHA[i]] = i;
    }

    private Base58() {}

    public static String encode(byte[] input) {
        if (input.length == 0) return "";
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;

        byte[] copy = Arrays.copyOf(input, input.length);
        StringBuilder sb = new StringBuilder();
        for (int start = zeros; start < copy.length; ) {
            sb.append(ALPHA[divmod(copy, start, 256, 58)]);
            if (copy[start] == 0) start++;
        }
        for (int i = 0; i < zeros; i++) sb.append(ALPHA[0]);
        return sb.reverse().toString();
    }

    public static byte[] decode(String input) {
        if (input.isEmpty()) return new byte[0];
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = c < 128 ? INDEX[c] : -1;
            if (digit < 0) throw new IllegalArgumentException("Caractere Base58 inválido: " + c);
            input58[i] = (byte) digit;
        }
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) zeros++;

        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        for (int start = zeros; start < input58.length; ) {
            decoded[--outputStart] = divmod(input58, start, 58, 256);
            if (input58[start] == 0) start++;
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) outputStart++;
        byte[] result = new byte[zeros + (decoded.length - outputStart)];
        System.arraycopy(decoded, outputStart, result, zeros, decoded.length - outputStart);
        return result;
    }

    /** Divisão longa in-place: number (base inputBase) /= divisor (base outputBase). */
    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = (int) number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
