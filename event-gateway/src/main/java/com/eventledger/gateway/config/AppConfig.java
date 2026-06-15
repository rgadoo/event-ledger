package com.eventledger.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Event Gateway API")
                .version("1.0.0")
                .description("Public-facing entry point for transaction events. Validates input, "
                        + "enforces idempotency, stores events, and applies them via the Account Service."));
    }
}
