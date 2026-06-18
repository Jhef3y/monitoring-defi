package com.defi.monitor.processing;

import com.defi.monitor.dto.MacroDtos.MacroFlag;
import com.defi.monitor.dto.PoolFundamentals;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache em memória que conecta o caminho lento (cron: The Graph + macro) ao
 * caminho rápido (WebSocket em tempo real). O orquestrador escreve; os workers
 * de monitoramento leem ao fechar cada candle para enriquecer a métrica.
 */
@Component
public final class EnrichmentCache {

    private final ConcurrentHashMap<String, PoolFundamentals> fundamentals = new ConcurrentHashMap<>();
    private volatile MacroFlag macroFlag = MacroFlag.none();

    public void updateFundamentals(PoolFundamentals f) {
        fundamentals.put(f.poolAddress().toLowerCase(), f);
    }

    public Optional<PoolFundamentals> fundamentalsFor(String poolAddress) {
        return Optional.ofNullable(fundamentals.get(poolAddress.toLowerCase()));
    }

    public void updateMacroFlag(MacroFlag flag) {
        this.macroFlag = flag;
    }

    public MacroFlag macroFlag() {
        return macroFlag;
    }
}
