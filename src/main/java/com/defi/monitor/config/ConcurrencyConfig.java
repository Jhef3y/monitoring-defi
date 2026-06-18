package com.defi.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configuração de concorrência baseada em Virtual Threads (Project Loom).
 *
 * <p>O Spring Boot já roda o servidor e {@code @Async}/{@code @Scheduled} sobre
 * virtual threads quando {@code spring.threads.virtual.enabled=true}. Aqui
 * expomos um {@link ExecutorService} dedicado (uma virtual thread por tarefa)
 * para o pool de conexões WebSocket simultâneas e o processamento de I/O.
 */
@Configuration
@EnableAsync
public class ConcurrencyConfig {

    /** Executor "uma virtual thread por tarefa" — ideal para I/O bloqueante massivo. */
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
