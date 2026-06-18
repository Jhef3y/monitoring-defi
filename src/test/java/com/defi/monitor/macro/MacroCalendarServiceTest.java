package com.defi.monitor.macro;

import com.defi.monitor.dto.MacroDtos.ForexFactoryEvent;
import com.defi.monitor.dto.MacroDtos.MacroEvent;
import com.defi.monitor.dto.MacroDtos.MacroFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MacroCalendarServiceTest {

    private MacroCalendarService serviceWith(List<MacroEvent> events) {
        return new MacroCalendarService(() -> events);   // MacroCalendarSource é funcional
    }

    @Test
    @DisplayName("Evento de alto impacto dentro de 24h dispara a flag")
    void highImpactWithin24hTriggersFlag() {
        var service = serviceWith(List.of(
                new MacroEvent("Federal Funds Rate", "USD", "HIGH",
                        Instant.now().plus(6, ChronoUnit.HOURS))));
        MacroFlag flag = service.fetchNext24h();
        assertThat(flag.highImpactNext24h()).isTrue();
        assertThat(flag.events()).hasSize(1);
    }

    @Test
    @DisplayName("Evento de alto impacto fora da janela de 24h não dispara")
    void highImpactBeyond24hIgnored() {
        var service = serviceWith(List.of(
                new MacroEvent("US CPI m/m", "USD", "HIGH",
                        Instant.now().plus(48, ChronoUnit.HOURS))));
        assertThat(service.fetchNext24h().highImpactNext24h()).isFalse();
    }

    @Test
    @DisplayName("Eventos de impacto médio/baixo são ignorados")
    void nonHighImpactIgnored() {
        var service = serviceWith(List.of(
                new MacroEvent("Retail Sales m/m", "USD", "Medium",
                        Instant.now().plus(3, ChronoUnit.HOURS)),
                new MacroEvent("Crude Oil Inventories", "USD", "Low",
                        Instant.now().plus(1, ChronoUnit.HOURS))));
        assertThat(service.fetchNext24h().highImpactNext24h()).isFalse();
    }

    @Test
    @DisplayName("Eventos passados (já divulgados) são ignorados")
    void pastEventsIgnored() {
        var service = serviceWith(List.of(
                new MacroEvent("FOMC Statement", "USD", "HIGH",
                        Instant.now().minus(2, ChronoUnit.HOURS))));
        assertThat(service.fetchNext24h().highImpactNext24h()).isFalse();
    }

    @Test
    @DisplayName("ForexFactoryEvent.toDomain mapeia campos do feed (impact 'High')")
    void forexFactoryMapping() {
        var raw = new ForexFactoryEvent("Federal Funds Rate", "USD",
                Instant.parse("2026-06-17T18:00:00Z"), "High", "3.75%", "3.75%");
        MacroEvent e = raw.toDomain();
        assertThat(e.title()).isEqualTo("Federal Funds Rate");
        assertThat(e.country()).isEqualTo("USD");
        assertThat(e.isHighImpact()).isTrue();
        assertThat(e.time()).isEqualTo(Instant.parse("2026-06-17T18:00:00Z"));
    }
}
