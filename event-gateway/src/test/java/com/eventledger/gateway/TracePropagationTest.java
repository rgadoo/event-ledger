package com.eventledger.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventledger.gateway.support.WireMockGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import java.net.URI;

/**
 * Verifies that the trace context generated at the Gateway is propagated to the
 * Account Service via the W3C {@code traceparent} header — i.e. a single client
 * request produces one traceable path across both services.
 */
// @SpringBootTest disables observability by default; enable it so tracing actually runs.
@AutoConfigureObservability
class TracePropagationTest extends WireMockGatewayTest {

    @Test
    void gatewayPropagatesW3CTraceparentToAccountService() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        String json = """
                {"eventId":"trace-1","accountId":"acct-1","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        ResponseEntity<String> response = rest.exchange(RequestEntity.post(URI.create("/events"))
                .contentType(MediaType.APPLICATION_JSON).body(json), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The outbound call carried a well-formed W3C traceparent: 00-<32 hex trace>-<16 hex span>-<flags>.
        wireMock.verify(postRequestedFor(urlPathMatching("/accounts/acct-1/transactions"))
                .withHeader("traceparent", matching("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")));
    }
}
