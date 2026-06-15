package com.eventledger.account.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Injectable clock so time-dependent logic is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI accountServiceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Account Service API")
                .version("1.0.0")
                .description("Internal service owning account balances and transaction history. "
                        + "Called only by the Event Gateway."));
    }
}
