package com.eventledger.gateway.web.dto;

import com.eventledger.gateway.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Payload submitted to {@code POST /events}. {@code type} is bound to the
 * {@link TransactionType} enum so any value other than CREDIT/DEBIT is rejected
 * as a 400 by the message converter.
 */
public record SubmitEventRequest(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp,
        Map<String, Object> metadata
) {
}
