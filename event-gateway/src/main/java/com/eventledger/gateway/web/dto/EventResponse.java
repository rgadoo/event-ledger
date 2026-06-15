package com.eventledger.gateway.web.dto;

import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        EventStatus status,
        Map<String, Object> metadata,
        Instant receivedAt,
        Instant appliedAt,
        String failureReason
) {
    public static EventResponse from(EventRecord e) {
        return new EventResponse(e.getEventId(), e.getAccountId(), e.getType(), e.getAmount(),
                e.getCurrency(), e.getEventTimestamp(), e.getStatus(), e.getMetadata(),
                e.getReceivedAt(), e.getAppliedAt(), e.getFailureReason());
    }
}
