package com.eventledger.gateway.client.dto;

import com.eventledger.gateway.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request sent to the Account Service. This is the Gateway's view of the
 * inter-service contract (see README "API Contract"). {@code accountId} travels
 * in the URL path, not the body.
 */
public record ApplyTransactionRequest(
        String eventId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {
}
