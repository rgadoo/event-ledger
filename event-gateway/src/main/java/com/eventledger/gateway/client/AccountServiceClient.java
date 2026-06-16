package com.eventledger.gateway.client;

import com.eventledger.gateway.client.dto.ApplyTransactionRequest;
import com.eventledger.gateway.client.dto.BalanceView;
import com.eventledger.gateway.client.dto.TransactionView;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client for the internal Account Service, guarded by a Resilience4j circuit breaker.
 *
 * <p>When the breaker is open or a call fails/times out, the configured fallback runs.
 * The fallback distinguishes a genuine downstream outage (→ {@link
 * AccountServiceUnavailableException} → HTTP 503) from a 4xx response, which indicates
 * a data/contract problem rather than an outage and is surfaced unchanged. 4xx responses
 * are also excluded from the breaker's failure count (see application.yml
 * {@code ignoreExceptions}) so client errors never trip the circuit.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    static final String CIRCUIT_BREAKER = "accountService";

    private final RestClient restClient;

    public AccountServiceClient(@Qualifier("accountServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "applyFallback")
    public TransactionView applyTransaction(String accountId, ApplyTransactionRequest request) {
        return restClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .body(request)
                .retrieve()
                .body(TransactionView.class);
    }

    /*
     * Fallback methods look unused but are invoked by reflection by the Resilience4j
     * aspect whenever the guarded method throws or the breaker is open. The signature
     * must match the guarded method plus a trailing Throwable. A 4xx is rethrown as-is
     * (it is a business/data error, not an outage); anything else becomes a 503-mapped
     * AccountServiceUnavailableException.
     */
    @SuppressWarnings("unused")
    private TransactionView applyFallback(String accountId, ApplyTransactionRequest request, Throwable t) {
        if (t instanceof HttpClientErrorException clientError) {
            throw clientError; // 4xx: a contract/data problem, not an outage
        }
        log.warn("Account Service apply failed for eventId={} accountId={}: {}",
                request.eventId(), accountId, t.toString());
        throw new AccountServiceUnavailableException(
                "Account Service unavailable while applying transaction " + request.eventId(), t);
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "balanceFallback")
    public BalanceView getBalance(String accountId) {
        return restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .body(BalanceView.class);
    }

    @SuppressWarnings("unused")
    private BalanceView balanceFallback(String accountId, Throwable t) {
        if (t instanceof HttpClientErrorException clientError) {
            throw clientError;
        }
        log.warn("Account Service balance query failed for accountId={}: {}", accountId, t.toString());
        throw new AccountServiceUnavailableException(
                "Account Service unavailable while fetching balance for " + accountId, t);
    }
}
