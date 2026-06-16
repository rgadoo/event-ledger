package com.eventledger.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Persistable;

/**
 * The Gateway's local record of a submitted event. {@code eventId} is the primary
 * key, which enforces idempotency: a second submission of the same id is detected
 * rather than stored twice. This table is owned solely by the Gateway and is the
 * source of truth for the event-listing endpoints, so those reads keep working
 * even when the Account Service is down.
 *
 * <p>Implements {@link Persistable} so the first {@code save()} issues a real SQL
 * {@code INSERT} (not a merge). That lets a concurrent submission of the same
 * {@code eventId} collide on the primary key, which the service catches and treats
 * as a duplicate — keeping idempotency correct under load. Later saves (status
 * updates) are normal updates.
 */
@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_event_account", columnList = "accountId")
})
public class EventRecord implements Persistable<String> {

    @Transient
    private boolean isNew = true;

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

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Lob
    @Convert(converter = MetadataConverter.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant appliedAt;

    @Column(length = 500)
    private String failureReason;

    protected EventRecord() {
        // for JPA
    }

    public EventRecord(String eventId, String accountId, TransactionType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, Map<String, Object> metadata,
                       Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.receivedAt = receivedAt;
        this.status = EventStatus.RECEIVED;
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

    public void markApplied(Instant when) {
        this.status = EventStatus.APPLIED;
        this.appliedAt = when;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = EventStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
