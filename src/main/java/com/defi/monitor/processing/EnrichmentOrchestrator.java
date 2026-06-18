package com.defi.monitor.processing;

import com.defi.monitor.dto.MacroDtos.MacroFlag;
import com.defi.monitor.dto.PoolFundamentals;
import com.defi.monitor.macro.MacroCalendarService;
import com.defi.monitor.solana.OrcaWhirlpoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.StructuredTaskScope;

/**
 * Orquestrador do "caminho lento" de enriquecimento.
 *
 * <p>Periodicamente coordena, em paralelo, o snapshot de fundamentos on-chain
 * das Whirlpools (TVL, Volume/TVL, fees) e a consulta ao calendário macro,
 * usando <b>Structured Concurrency</b> (JEP 505, Java 25). As duas tarefas
 * rodam em Virtual Threads filhas do escopo; se uma falhar, a outra é cancelada.
 * Só após ambas concluírem o resultado é publicado no {@link EnrichmentCache},
 * de onde os workers de tempo real o leem ao fechar cada candle.
 */
@Component
public class EnrichmentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentOrchestrator.class);

    private final OrcaWhirlpoolService orcaService;
    private final MacroCalendarService macroService;
    private final EnrichmentCache cache;

    public EnrichmentOrchestrator(OrcaWhirlpoolService orcaService,
                                  MacroCalendarService macroService,
                                  EnrichmentCache cache) {
        this.orcaService = orcaService;
        this.macroService = macroService;
        this.cache = cache;
    }

    /** Dispara um enriquecimento inicial assim que a aplicação fica pronta. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshEnrichment();
    }

    /** Roda a cada hora cheia (ver cron). */
    @Scheduled(cron = "${defi.solana.stats-cron}")
    public void refreshEnrichment() {
        log.info("Iniciando enriquecimento paralelo (fundamentos on-chain + macro)…");
        try (var scope = StructuredTaskScope.open()) {           // joiner padrão: aguarda todas, propaga falha
            StructuredTaskScope.Subtask<List<PoolFundamentals>> fundamentalsTask =
                    scope.fork(orcaService::snapshotFundamentals);
            StructuredTaskScope.Subtask<MacroFlag> macroTask =
                    scope.fork(macroService::fetchNext24h);

            scope.join();   // bloqueia até ambas concluírem (ou falharem)

            fundamentalsTask.get().forEach(cache::updateFundamentals);
            cache.updateMacroFlag(macroTask.get());

            log.info("Enriquecimento concluído: {} fundamentos, macroHighImpact={}",
                    fundamentalsTask.get().size(), macroTask.get().highImpactNext24h());

        } catch (StructuredTaskScope.FailedException e) {
            log.error("Falha em uma das tarefas de enriquecimento: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Enriquecimento interrompido");
        }
    }
}
