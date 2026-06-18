package com.defi.monitor.solana;

import com.defi.monitor.config.DefiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Cliente HTTP JSON-RPC da Solana, usado na descoberta de startup
 * ({@code getAccountInfo}) para ler o estado inicial das contas (Whirlpool,
 * mints e cofres) antes de abrir as subscrições WebSocket.
 *
 * <p>Usa o {@link ObjectMapper} do Jackson 2 (bean dedicado) para manter o
 * parsing consistente com o caminho WebSocket.
 */
@Component
public class SolanaRpcClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaRpcClient.class);

    private final WebClient webClient;
    private final ObjectMapper mapper;

    public SolanaRpcClient(WebClient.Builder builder, DefiProperties props, ObjectMapper mapper) {
        this.webClient = builder.baseUrl(props.solana().rpcUrl()).build();
        this.mapper = mapper;
    }

    /**
     * Retorna os bytes brutos (base64 decodificado) de uma conta, ou {@code null}
     * se a conta não existir.
     */
    public byte[] getAccountData(String pubkey) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "getAccountInfo",
                "params", java.util.List.of(pubkey, Map.of("encoding", "base64")));
        try {
            String json = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (json == null) return null;
            JsonNode value = mapper.readTree(json).path("result").path("value");
            if (value.isMissingNode() || value.isNull()) {
                log.warn("Conta não encontrada: {}", pubkey);
                return null;
            }
            String base64 = value.path("data").path(0).asText(null);
            return base64 == null ? null : Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            log.error("Falha em getAccountInfo({}): {}", pubkey, e.getMessage());
            return null;
        }
    }

    /**
     * Lê várias contas numa única chamada ({@code getMultipleAccounts}). Retorna
     * uma lista alinhada à ordem de entrada; posições inexistentes vêm {@code null}.
     */
    public java.util.List<byte[]> getMultipleAccounts(java.util.List<String> pubkeys) {
        java.util.List<byte[]> result = new java.util.ArrayList<>(java.util.Collections.nCopies(pubkeys.size(), null));
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "getMultipleAccounts",
                "params", java.util.List.of(pubkeys, Map.of("encoding", "base64")));
        try {
            String json = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (json == null) return result;
            JsonNode value = mapper.readTree(json).path("result").path("value");
            for (int i = 0; i < value.size() && i < result.size(); i++) {
                JsonNode acc = value.get(i);
                if (acc == null || acc.isNull()) continue;
                String b64 = acc.path("data").path(0).asText(null);
                if (b64 != null) result.set(i, Base64.getDecoder().decode(b64));
            }
        } catch (Exception e) {
            log.error("Falha em getMultipleAccounts: {}", e.getMessage());
        }
        return result;
    }
}
