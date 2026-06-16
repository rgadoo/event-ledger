package com.eventledger.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventledger.gateway.support.WireMockGatewayTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import java.net.URI;

/**
 * Idempotency at the public boundary: many concurrent submissions of the same
 * {@code eventId} must not 500 or store a duplicate. Exactly one event row exists
 * and every caller gets a clean 2xx.
 */
class GatewayConcurrencyTest extends WireMockGatewayTest {

    @Test
    void concurrentDuplicateSubmissionsAreSafe() throws Exception {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        String payload = """
                {"eventId":"race-1","accountId":"acct-1","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HttpStatusCode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return rest.exchange(RequestEntity.post(URI.create("/events"))
                        .contentType(MediaType.APPLICATION_JSON).body(payload), String.class)
                        .getStatusCode();
            }));
        }
        startGate.countDown();

        int created = 0;
        for (Future<HttpStatusCode> f : futures) {
            HttpStatusCode status = f.get();
            assertThat(status.is5xxServerError()).as("no caller should get a 5xx").isFalse();
            if (status.equals(HttpStatus.CREATED)) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(created).as("exactly one caller creates the event").isEqualTo(1);
        assertThat(eventRepository.count()).as("only one event row stored").isEqualTo(1);
    }
}
