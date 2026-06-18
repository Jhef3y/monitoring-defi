package com.defi.monitor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Records imutáveis (Java 25) para o protocolo JSON-RPC da Solana (Alchemy),
 * usados na descoberta HTTP ({@code getAccountInfo}) e nas notificações
 * WebSocket ({@code accountNotification}). Os dados binários das contas chegam
 * em base64 no primeiro elemento de {@code data} e são decodificados via Borsh.
 */
public final class SolanaDtos {

    private SolanaDtos() {}

    /** Conteúdo de uma conta retornado pela RPC ({@code value}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountValue(
            List<String> data,   // [ "<base64>", "base64" ]
            long lamports,
            String owner,
            boolean executable
    ) {
        /** Bytes brutos da conta (primeiro elemento de data é base64). */
        public byte[] decode() {
            if (data == null || data.isEmpty() || data.getFirst() == null) return new byte[0];
            return java.util.Base64.getDecoder().decode(data.getFirst());
        }
    }
}
