package com.eventledger.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventledger.gateway.support.WireMockGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import java.net.URI;

/**
 * Full Gateway → Account Service flow with the Account Service stubbed by WireMock.
 * Covers the happy path, idempotency, validation, and out-of-order listing.
 */
class GatewayIntegrationTest extends WireMockGatewayTest {

    private void stubApplyAccepted() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    private ResponseEntity<String> postEvent(String json) {
        RequestEntity<String> req = RequestEntity.post(URI.create("/events"))
                .contentType(MediaType.APPLICATION_JSON).body(json);
        return rest.exchange(req, String.class);
    }

    private static String event(String id, String account, String type, String amount, String ts) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(id, account, type, amount, ts);
    }

    @Test
    void newEventIsAppliedAndReturns201() {
        stubApplyAccepted();

        ResponseEntity<String> response =
                postEvent(event("evt-1", "acct-1", "CREDIT", "150.00", "2026-05-15T10:00:00Z"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"status\":\"APPLIED\"");
        wireMock.verify(postRequestedFor(urlPathMatching("/accounts/acct-1/transactions")));
    }

    @Test
    void optionalMetadataIsStoredAndReturned() {
        stubApplyAccepted();
        String json = """
                {"eventId":"meta-1","accountId":"acct-1","type":"CREDIT","amount":10,"currency":"USD",
                 "eventTimestamp":"2026-05-15T10:00:00Z",
                 "metadata":{"source":"mainframe-batch","batchId":"B-9042"}}
                """;
        assertThat(postEvent(json).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The optional metadata object round-trips through storage and back out on read.
        ResponseEntity<String> fetched = rest.getForEntity("/events/meta-1", String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody())
                .contains("\"source\":\"mainframe-batch\"")
                .contains("\"batchId\":\"B-9042\"");
    }

    @Test
    void duplicateEventReturns200AndIsNotReapplied() {
        stubApplyAccepted();
        String payload = event("evt-dup", "acct-1", "CREDIT", "150.00", "2026-05-15T10:00:00Z");

        assertThat(postEvent(payload).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> second = postEvent(payload);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The Account Service is called exactly once: the duplicate short-circuits.
        wireMock.verify(exactly(1), postRequestedFor(urlPathMatching("/accounts/acct-1/transactions")));
    }

    @Test
    void invalidPayloadsAreRejectedWith400() {
        // zero amount
        assertThat(postEvent(event("b1", "a", "CREDIT", "0", "2026-05-15T10:00:00Z"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // unknown type
        assertThat(postEvent(event("b2", "a", "TRANSFER", "5", "2026-05-15T10:00:00Z"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // missing required field (accountId)
        assertThat(postEvent("{\"eventId\":\"b3\",\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\","
                + "\"eventTimestamp\":\"2026-05-15T10:00:00Z\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // malformed currency (not a 3-letter ISO-4217 code)
        assertThat(postEvent("{\"eventId\":\"b4\",\"accountId\":\"a\",\"type\":\"CREDIT\",\"amount\":5,"
                + "\"currency\":\"usdollar\",\"eventTimestamp\":\"2026-05-15T10:00:00Z\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // No downstream call should have been made for invalid input.
        wireMock.verify(exactly(0), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void downstreamBusinessRejectionIsSurfacedWithItsStatus() {
        // Account Service rejects the transaction with 422 (e.g. currency mismatch).
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"currency mismatch\"}")));

        ResponseEntity<String> response =
                postEvent(event("rej-1", "acct-1", "CREDIT", "10", "2026-05-15T10:00:00Z"));

        // The 4xx is forwarded, not masked as a 500/502/503.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // The event is recorded locally as FAILED and remains readable.
        ResponseEntity<String> byId = rest.getForEntity("/events/rej-1", String.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).contains("\"status\":\"FAILED\"");
    }

    @Test
    void failedEventIsRetriedAndAppliedOnResubmission() {
        // First attempt: Account Service errors -> event stored as FAILED, caller gets 503.
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));
        String payload = event("retry-1", "acct-1", "CREDIT", "10", "2026-05-15T10:00:00Z");
        assertThat(postEvent(payload).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rest.getForEntity("/events/retry-1", String.class).getBody())
                .contains("\"status\":\"FAILED\"");

        // Account Service recovers; resubmitting the same eventId now applies it (no duplicate).
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ResponseEntity<String> retry = postEvent(payload);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).contains("\"status\":\"APPLIED\"");
    }

    @Test
    void getMissingEventReturns404() {
        assertThat(rest.getForEntity("/events/does-not-exist", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void eventsAreListedInChronologicalOrderRegardlessOfArrival() {
        stubApplyAccepted();
        // Arrive out of order: later timestamp first, earlier timestamp second.
        postEvent(event("late", "acct-9", "CREDIT", "100", "2026-05-15T14:00:00Z"));
        postEvent(event("early", "acct-9", "DEBIT", "40", "2026-05-15T09:00:00Z"));

        ResponseEntity<String> list = rest.getForEntity("/events?account=acct-9", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = list.getBody();
        // "early" (09:00) must appear before "late" (14:00).
        assertThat(body.indexOf("early")).isLessThan(body.indexOf("late"));
    }
}
