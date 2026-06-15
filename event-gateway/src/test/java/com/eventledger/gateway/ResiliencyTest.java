package com.eventledger.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventledger.gateway.support.WireMockGatewayTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;

/**
 * Resiliency and graceful degradation. The circuit breaker is tuned to open after a
 * few failures so the behaviour is deterministic and fast.
 */
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.accountService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.accountService.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=10s",
        "resilience4j.circuitbreaker.instances.accountService.permitted-number-of-calls-in-half-open-state=2"
})
class ResiliencyTest extends WireMockGatewayTest {

    private void stubAccountServiceFailing() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));
    }

    private ResponseEntity<String> postEvent(String id) {
        String json = """
                {"eventId":"%s","accountId":"acct-1","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(id);
        return rest.exchange(RequestEntity.post(URI.create("/events"))
                .contentType(MediaType.APPLICATION_JSON).body(json), String.class);
    }

    @Test
    void accountServiceFailureYields503NotHangOr500() {
        stubAccountServiceFailing();

        ResponseEntity<String> response = postEvent("evt-fail");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void localReadsStillWorkWhileAccountServiceIsDown() {
        stubAccountServiceFailing();
        postEvent("evt-stored"); // returns 503 but the event is persisted locally as FAILED

        // GET by id and the account listing depend only on the Gateway's own store.
        ResponseEntity<String> byId = rest.getForEntity("/events/evt-stored", String.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).contains("\"status\":\"FAILED\"");

        ResponseEntity<String> list = rest.getForEntity("/events?account=acct-1", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains("evt-stored");
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailuresAndShortCircuits() {
        stubAccountServiceFailing();

        // Four failing calls fill the window and trip the breaker (100% failure rate).
        for (int i = 0; i < 4; i++) {
            assertThat(postEvent("evt-" + i).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Further calls are short-circuited: still 503, but the Account Service is no longer hit.
        assertThat(postEvent("evt-open").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        wireMock.verify(exactly(4), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }
}
