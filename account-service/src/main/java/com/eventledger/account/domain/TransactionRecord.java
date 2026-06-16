package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * An immutable transaction applied to an account.
 *
 * <p>The originating {@code eventId} is used as the primary key. This makes
 * applying a transaction naturally idempotent: replaying the same event is a
 * no-op insert. Because the balance is computed as a fold over these rows
 * (see {@code AccountService}), arrival order never affects the result.
 *
 * <p>Implements {@link Persistable} so that {@code save()} issues a real SQL
 * {@code INSERT} (not a merge). With an assigned id, Spring Data would otherwise
 * treat every save as an update and silently overwrite — which would defeat the
 * primary-key guard that protects idempotency under concurrent submissions.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_account", columnList = "accountId")
})
public class TransactionRecord implements Persistable<String> {

    @Transient
    private boolean isNew = true;

    /** The originating event id — also the idempotency key. */
    @Id
    @Column(nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    /** When the transaction originally occurred upstream (may arrive out of order). */
    @Column(nullable = false)
    private Instant eventTimestamp;

    /** When this service durably recorded the transaction. */
    @Column(nullable = false)
    private Instant recordedAt;

    protected TransactionRecord() {
        // for JPA
    }

    public TransactionRecord(String eventId, String accountId, TransactionType type,
                             BigDecimal amount, String currency, Instant eventTimestamp,
                             Instant recordedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.recordedAt = recordedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Once persisted or loaded, the entity is no longer new (subsequent saves are updates). */
    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
