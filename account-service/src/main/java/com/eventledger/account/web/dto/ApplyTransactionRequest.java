package com.eventledger.account.web.dto;

import com.eventledger.account.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for applying a transaction. {@code accountId} comes from the path.
 */
public record ApplyTransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp
) {
}
