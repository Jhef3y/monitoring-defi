package com.defi.monitor.macro;

import com.defi.monitor.config.DefiProperties;
import com.defi.monitor.dto.MacroDtos.ForexFactoryEvent;
import com.defi.monitor.dto.MacroDtos.MacroEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fonte real do calendário econômico baseada no feed público
 * <b>faireconomy / Forex Factory</b> (JSON semanal, gratuito, sem API key).
 *
 * <p>Busca o feed da semana atual e da próxima (cobrindo a virada de semana),
 * normaliza para {@link MacroEvent} e, opcionalmente, filtra por moedas de
 * interesse (ex.: {@code USD}). Ativa por padrão; o mock só entra quando
 * {@code defi.macro.mock-enabled=true}. A seleção entre esta fonte e o mock é
 * feita em {@link MacroSourceConfig}.
 */
public class ForexFactoryCalendarSource implements MacroCalendarSource {

    private static final Logger log = LoggerFactory.getLogger(ForexFactoryCalendarSource.class);

    private static final ParameterizedTypeReference<List<ForexFactoryEvent>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final DefiProperties.Macro cfg;
    private final Set<String> currencyFilter;

    public ForexFactoryCalendarSource(WebClient.Builder builder, DefiProperties props) {
        this.webClient = builder.build();
        this.cfg = props.macro();
        this.currencyFilter = cfg.currencies() == null ? Set.of()
                : cfg.currencies().stream().map(String::toUpperCase).collect(Collectors.toSet());
        log.info("Calendário macro: fonte REAL (faireconomy) ativa; filtro de moedas={}",
                currencyFilter.isEmpty() ? "TODAS" : currencyFilter);
    }

    @Override
    public List<MacroEvent> fetchEvents() {
        List<MacroEvent> all = new ArrayList<>();
        all.addAll(fetchFeed(cfg.calendarThisWeekUrl(), "semana atual"));
        if (cfg.calendarNextWeekUrl() != null && !cfg.calendarNextWeekUrl().isBlank()) {
            all.addAll(fetchFeed(cfg.calendarNextWeekUrl(), "próxima semana"));
        }
        return all;
    }

    private List<MacroEvent> fetchFeed(String url, String label) {
        try {
            List<ForexFactoryEvent> raw = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(LIST_TYPE)
                    .block(Duration.ofSeconds(20));
            if (raw == null) return List.of();

            List<MacroEvent> events = raw.stream()
                    .filter(e -> e.date() != null)
                    .filter(this::matchesCurrency)
                    .map(ForexFactoryEvent::toDomain)
                    .toList();
            log.debug("Calendário macro ({}): {} evento(s) após filtro", label, events.size());
            return events;
        } catch (Exception e) {
            log.warn("Falha ao buscar calendário macro ({}): {}", label, e.getMessage());
            return List.of();
        }
    }

    private boolean matchesCurrency(ForexFactoryEvent e) {
        if (currencyFilter.isEmpty()) return true;
        return e.country() != null && currencyFilter.contains(e.country().toUpperCase());
    }
}
