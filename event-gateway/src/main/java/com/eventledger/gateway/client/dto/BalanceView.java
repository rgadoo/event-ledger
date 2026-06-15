package com.eventledger.gateway.client.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Account Service response for a balance query. */
public record BalanceView(
        String accountId,
        BigDecimal balance,
        String currency,
        long creditCount,
        long debitCount,
        long transactionCount,
        Instant asOf
) {
}
