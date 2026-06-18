package com.defi.monitor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** Records do calendário econômico macro (Java 25). */
public final class MacroDtos {

    private MacroDtos() {}

    /**
     * Evento econômico bruto do feed faireconomy / Forex Factory.
     *
     * <p>Exemplo de item do JSON:
     * <pre>
     * {"title":"Federal Funds Rate","country":"USD",
     *  "date":"2026-06-17T14:00:00-04:00","impact":"High",
     *  "forecast":"3.75%","previous":"3.75%"}
     * </pre>
     * O campo {@code date} (ISO-8601 com offset) é desserializado direto para
     * {@link Instant} pelo módulo JSR-310 do Jackson.
     *
     * @param country código da moeda (USD, EUR, JPY…)
     * @param impact  "High" | "Medium" | "Low" | "Holiday"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForexFactoryEvent(
            String title,
            String country,
            Instant date,
            String impact,
            String forecast,
            String previous
    ) {
        public MacroEvent toDomain() {
            return new MacroEvent(title, country, impact, date);
        }
    }

    /**
     * Evento macroeconômico normalizado do domínio.
     *
     * @param title    ex.: "US CPI m/m", "Federal Funds Rate"
     * @param country  moeda/região (ex.: "USD")
     * @param impact   "HIGH" | "MEDIUM" | "LOW" | …
     * @param time     horário de divulgação (UTC)
     */
    public record MacroEvent(
            String title,
            String country,
            String impact,
            Instant time
    ) {
        public boolean isHighImpact() {
            return impact != null && impact.equalsIgnoreCase("HIGH");
        }
    }

    /**
     * Resultado agregado: existe (ou não) evento de alto impacto na janela analisada.
     *
     * @param highImpactNext24h  flag usada para alertar sobre rompimentos de range
     * @param events             eventos de alto impacto encontrados (para contexto)
     */
    public record MacroFlag(boolean highImpactNext24h, List<MacroEvent> events) {
        public static MacroFlag none() {
            return new MacroFlag(false, List.of());
        }
    }
}
