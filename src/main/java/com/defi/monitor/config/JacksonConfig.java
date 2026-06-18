package com.defi.monitor.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean explícito de {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2).
 *
 * <p>O Spring Boot 4 / Spring Framework 7 passou a usar <b>Jackson 3</b>
 * ({@code tools.jackson.databind.ObjectMapper}) por padrão, então o
 * {@code ObjectMapper} do Jackson 2 deixou de ser autoconfigurado. Como o
 * {@code AlchemyWebSocketService} faz o parsing manual das mensagens JSON-RPC
 * com a API do Jackson 2, registramos aqui o bean correspondente.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.findAndRegisterModules();   // registra JavaTime/jsr310 se presente no classpath
        return mapper;
    }
}
