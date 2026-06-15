package com.eventledger.gateway.support;

import com.eventledger.gateway.repo.EventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for Gateway tests that stub the Account Service with WireMock. The Account
 * Service base URL is pointed at the WireMock port, and the circuit breaker + event
 * store are reset before each test for isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class WireMockGatewayTest {

    protected static final WireMockServer wireMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        // Started once for the test JVM and shared across all Gateway test classes.
        // Not stopped per-class on purpose: a shared static server stopped in @AfterAll
        // would leave later test classes pointing at a dead port. It dies with the JVM.
        wireMock.start();
    }

    @DynamicPropertySource
    static void accountServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected EventRepository eventRepository;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        eventRepository.deleteAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }
}
