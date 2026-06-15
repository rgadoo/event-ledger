package com.eventledger.account.domain;

/**
 * The kind of ledger movement. CREDIT increases a balance, DEBIT decreases it.
 */
public enum TransactionType {
    CREDIT,
    DEBIT
}
