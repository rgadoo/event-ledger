package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionRecord;

/**
 * Result of applying a transaction. {@code duplicate} is true when the event had
 * already been applied, so the caller can return the original without re-applying.
 */
public record ApplyOutcome(TransactionRecord record, boolean duplicate) {
}
