package com.eventledger.gateway.client.dto;

import com.eventledger.gateway.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/** Account Service response for an applied transaction. */
public record TransactionView(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant recordedAt
) {
}
