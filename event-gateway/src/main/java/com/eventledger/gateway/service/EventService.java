package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.client.dto.ApplyTransactionRequest;
import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.repo.EventRepository;
import com.eventledger.gateway.web.dto.SubmitEventRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository repository;
    private final AccountServiceClient accountClient;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EventService(EventRepository repository, AccountServiceClient accountClient,
                        MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.accountClient = accountClient;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Submit an event. Idempotent on {@code eventId}:
     * <ul>
     *   <li>Already APPLIED → returns the original, no re-apply ({@code DUPLICATE}).</li>
     *   <li>New, or a previously FAILED/RECEIVED event → (re)attempts the Account Service call.
     *       The Account Service is itself idempotent on eventId, so retrying is always safe.</li>
     *   <li>Account Service unavailable → event is persisted as FAILED and {@code DEGRADED}
     *       is returned so the caller gets a 503 rather than a hang or a 500.</li>
     * </ul>
     *
     * <p>Not wrapped in a single transaction on purpose: each save commits independently so
     * the local event record survives even when the downstream call fails, keeping the
     * read endpoints usable during an outage.
     */
    public SubmitResult submit(SubmitEventRequest req) {
        Optional<EventRecord> existing = repository.findById(req.eventId());

        if (existing.isPresent() && existing.get().getStatus() == EventStatus.APPLIED) {
            log.info("Duplicate event ignored: eventId={} accountId={}", req.eventId(), req.accountId());
            count(req.type().name(), "duplicate");
            return new SubmitResult(existing.get(), SubmitOutcome.DUPLICATE);
        }

        boolean isNew = existing.isEmpty();
        EventRecord record = existing.orElseGet(() -> repository.save(new EventRecord(
                req.eventId(), req.accountId(), req.type(), req.amount(), req.currency(),
                req.eventTimestamp(), req.metadata(), Instant.now(clock))));

        try {
            accountClient.applyTransaction(req.accountId(), new ApplyTransactionRequest(
                    req.eventId(), req.type(), req.amount(), req.currency(), req.eventTimestamp()));
            record.markApplied(Instant.now(clock));
            repository.save(record);
            log.info("Event applied: eventId={} accountId={} new={}", req.eventId(), req.accountId(), isNew);
            count(req.type().name(), "applied");
            return new SubmitResult(record, isNew ? SubmitOutcome.CREATED : SubmitOutcome.DUPLICATE);
        } catch (AccountServiceUnavailableException e) {
            record.markFailed(e.getMessage());
            repository.save(record);
            log.warn("Event stored but not applied (Account Service unavailable): eventId={}", req.eventId());
            count(req.type().name(), "degraded");
            return new SubmitResult(record, SubmitOutcome.DEGRADED);
        }
    }

    public EventRecord getEvent(String eventId) {
        return repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /** Local read — always available, even when the Account Service is down. */
    public List<EventRecord> listByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }

    private void count(String type, String outcome) {
        meterRegistry.counter("gateway_events_total", "type", type, "outcome", outcome).increment();
    }
}
