package com.defi.monitor.macro;

import com.defi.monitor.dto.MacroDtos.MacroEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Mock estruturado do calendário macro. Selecionado por {@link MacroSourceConfig}
 * quando {@code defi.macro.mock-enabled=true} (ambientes offline / testes de fumaça).
 */
public class MockMacroCalendarSource implements MacroCalendarSource {

    private static final Logger log = LoggerFactory.getLogger(MockMacroCalendarSource.class);

    public MockMacroCalendarSource() {
        log.info("Calendário macro: fonte MOCK ativa (defi.macro.mock-enabled=true)");
    }

    @Override
    public List<MacroEvent> fetchEvents() {
        return List.of(
                new MacroEvent("US CPI m/m", "USD", "HIGH", Instant.now().plus(6, ChronoUnit.HOURS)),
                new MacroEvent("FOMC Member Speech", "USD", "MEDIUM", Instant.now().plus(2, ChronoUnit.HOURS))
        );
    }
}
