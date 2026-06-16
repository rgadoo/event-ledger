package com.eventledger.account.service;

/**
 * Raised when a transaction's currency differs from the account's established currency.
 * An account's currency is set by its first transaction; later transactions must match.
 * Surfaced as HTTP 422 (Unprocessable Entity) — the request is well-formed but violates
 * a business rule.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String accountId, String expected, String actual) {
        super("Account " + accountId + " uses currency " + expected
                + "; transaction currency " + actual + " is not allowed");
    }
}
