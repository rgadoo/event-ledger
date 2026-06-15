package com.eventledger.gateway.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * RestClient for the Account Service.
     *
     * <p>Built from the auto-configured {@link RestClient.Builder} so that Micrometer
     * tracing instrumentation is applied — this is what injects the W3C
     * {@code traceparent} header on the outbound call. Connect/read timeouts bound how
     * long a slow Account Service can block a Gateway thread (the "timeout" half of our
     * resiliency strategy; the circuit breaker is applied in {@code AccountServiceClient}).
     */
    @Bean
    public RestClient accountServiceRestClient(
            RestClient.Builder builder,
            @Value("${account-service.base-url}") String baseUrl,
            @Value("${account-service.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${account-service.read-timeout-ms:2000}") long readTimeoutMs) {

        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
