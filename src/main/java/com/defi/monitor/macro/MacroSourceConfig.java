package com.defi.monitor.macro;

import com.defi.monitor.config.DefiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Seleciona a {@link MacroCalendarSource} em uso a partir da configuração
 * {@code defi.macro.mock-enabled}. Optou-se por uma fábrica {@code @Bean}
 * explícita (em vez de {@code @ConditionalOnProperty}) para manter a escolha
 * simples e independente de detalhes de auto-configuração.
 */
@Configuration
public class MacroSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MacroSourceConfig.class);

    @Bean
    public MacroCalendarSource macroCalendarSource(WebClient.Builder builder, DefiProperties props) {
        if (props.macro().mockEnabled()) {
            log.info("Calendário macro: fonte MOCK (defi.macro.mock-enabled=true)");
            return new MockMacroCalendarSource();
        }
        log.info("Calendário macro: fonte REAL faireconomy/Forex Factory");
        return new ForexFactoryCalendarSource(builder, props);
    }
}
