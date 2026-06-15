package com.eventledger.gateway.client;

/**
 * Raised when the Account Service cannot be reached or the circuit breaker is open.
 * Mapped to HTTP 503 by the Gateway's exception handler so callers see a clear,
 * non-hanging error instead of a 500.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
