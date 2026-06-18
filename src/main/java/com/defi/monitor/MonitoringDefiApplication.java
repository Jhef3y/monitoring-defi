package com.defi.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MonitoringDefiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonitoringDefiApplication.class, args);
    }
}
