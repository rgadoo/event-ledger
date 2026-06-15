package com.eventledger.account.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        long creditCount,
        long debitCount,
        long transactionCount,
        Instant asOf
) {
}
