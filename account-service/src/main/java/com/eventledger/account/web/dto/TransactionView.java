package com.eventledger.account.web.dto;

import com.eventledger.account.domain.TransactionRecord;
import com.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant recordedAt
) {
    public static TransactionView from(TransactionRecord r) {
        return new TransactionView(r.getEventId(), r.getAccountId(), r.getType(),
                r.getAmount(), r.getCurrency(), r.getEventTimestamp(), r.getRecordedAt());
    }
}
