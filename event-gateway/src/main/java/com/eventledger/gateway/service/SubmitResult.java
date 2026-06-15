package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventRecord;

public record SubmitResult(EventRecord record, SubmitOutcome outcome) {
}
