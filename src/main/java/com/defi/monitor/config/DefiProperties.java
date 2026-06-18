package com.defi.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Propriedades tipadas (Records — Java 25) mapeadas de {@code defi.*} no application.yml.
 */
@ConfigurationProperties(prefix = "defi")
public record DefiProperties(
        Solana solana,
        Macro macro,
        Indicators indicators
) {
    /**
     * Configuração da ingestão on-chain via Solana RPC/WebSocket (Alchemy) das
     * pools de liquidez concentrada da Orca (Whirlpools).
     *
     * @param rpcUrl              endpoint HTTP (discovery: getAccountInfo)
     * @param wsUrl               endpoint WebSocket (accountSubscribe em tempo real)
     * @param whirlpoolProgramId  program id da Orca Whirlpools
     * @param usdcMint            mint da USDC na Solana (para orientar o preço em USD)
     * @param reconnectDelayMs    backoff de reconexão
     * @param statsCron           cron do snapshot de fundamentos (TVL/ratio)
     * @param pools               pools monitoradas (conta da Whirlpool)
     */
    public record Solana(
            String rpcUrl,
            String wsUrl,
            String whirlpoolProgramId,
            String usdcMint,
            long reconnectDelayMs,
            String statsCron,
            boolean useWebsocket,     // true = accountSubscribe (WS); false = polling HTTP (padrão)
            long pollIntervalMs,      // intervalo do polling quando useWebsocket=false
            List<Pool> pools
    ) {}

    /** Decimais e ordenação dos tokens são descobertos on-chain no startup. */
    public record Pool(String symbol, String whirlpool) {}

    public record Macro(
            boolean mockEnabled,
            String calendarThisWeekUrl,
            String calendarNextWeekUrl,
            List<String> currencies,
            String pollCron
    ) {}

    public record Indicators(
            int atrPeriod,
            int bollingerPeriod,
            double bollingerStdDev,
            double squeezeBandwidthThreshold,
            int volumeProfileBins
    ) {}
}
