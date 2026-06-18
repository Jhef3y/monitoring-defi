package com.defi.monitor.macro;

import com.defi.monitor.dto.MacroDtos.MacroEvent;
import com.defi.monitor.dto.MacroDtos.MacroFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Serviço do calendário econômico macro (camada de Ingestão).
 *
 * <p>Delega a obtenção dos eventos a uma {@link MacroCalendarSource}
 * (real {@link ForexFactoryCalendarSource} ou {@link MockMacroCalendarSource},
 * escolhida por configuração) e aplica a regra de negócio: produzir a
 * {@link MacroFlag} indicando se há evento de <b>alto impacto nas próximas
 * 24h</b> — usada para alertar sobre possíveis rompimentos de range.
 *
 * <p>O método {@link #fetchNext24h()} é bloqueante por design — executado
 * dentro de Structured Concurrency / Virtual Thread pelo orquestrador.
 */
@Component
public class MacroCalendarService {

    private static final Logger log = LoggerFactory.getLogger(MacroCalendarService.class);

    private final MacroCalendarSource source;

    public MacroCalendarService(MacroCalendarSource source) {
        this.source = source;
    }

    public MacroFlag fetchNext24h() {
        List<MacroEvent> events = source.fetchEvents();
        Instant now = Instant.now();
        Instant horizon = now.plus(24, ChronoUnit.HOURS);

        List<MacroEvent> highImpact = events.stream()
                .filter(MacroEvent::isHighImpact)
                .filter(e -> e.time() != null
                        && !e.time().isBefore(now) && !e.time().isAfter(horizon))
                .sorted(java.util.Comparator.comparing(MacroEvent::time))
                .toList();

        if (!highImpact.isEmpty()) {
            log.info("Macro: {} evento(s) de alto impacto nas próximas 24h; próximo: {} ({})",
                    highImpact.size(), highImpact.getFirst().title(), highImpact.getFirst().time());
        } else {
            log.info("Macro: nenhum evento de alto impacto nas próximas 24h");
        }
        return new MacroFlag(!highImpact.isEmpty(), highImpact);
    }
}
