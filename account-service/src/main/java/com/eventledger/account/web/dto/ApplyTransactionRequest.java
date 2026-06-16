package com.eventledger.account.web.dto;

import com.eventledger.account.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for applying a transaction. {@code accountId} comes from the path.
 *
 * <p>The Gateway already validates input, but the Account Service re-validates
 * (defence in depth) — it must not trust its only caller blindly, and the same
 * bounds protect it if it is ever called directly.
 */
public record ApplyTransactionRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO-4217 code") String currency,
        @NotNull Instant eventTimestamp
) {
}
