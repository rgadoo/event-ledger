package com.eventledger.gateway.web.dto;

import com.eventledger.gateway.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Payload submitted to {@code POST /events}. {@code type} is bound to the
 * {@link TransactionType} enum so any value other than CREDIT/DEBIT is rejected
 * as a 400 by the message converter.
 *
 * <p>The {@code @Size} bounds are a deliberate defence: they cap how much an
 * untrusted client can store per field (an unbounded id/currency is a storage and
 * memory abuse vector). {@code currency} is constrained to a 3-letter ISO-4217 code.
 */
public record SubmitEventRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String accountId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO-4217 code") String currency,
        @NotNull Instant eventTimestamp,
        Map<String, Object> metadata
) {
}
