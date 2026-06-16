package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionRecord;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repo.TransactionRepository;
import com.eventledger.account.web.dto.AccountResponse;
import com.eventledger.account.web.dto.ApplyTransactionRequest;
import com.eventledger.account.web.dto.BalanceResponse;
import com.eventledger.account.web.dto.TransactionView;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TransactionRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AccountService(TransactionRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Apply a transaction idempotently. If the {@code eventId} was already applied,
     * the stored record is returned unchanged and no new movement is recorded.
     *
     * <p>Correct under concurrency: rather than a check-then-insert (which races), it
     * attempts the insert and, if a concurrent caller won the primary-key race, catches
     * the {@link DataIntegrityViolationException} and returns the winning record as a
     * duplicate. Not wrapped in a single transaction so the post-collision lookup runs
     * in a fresh transaction (the failed insert's transaction has rolled back).
     */
    public ApplyOutcome apply(String accountId, ApplyTransactionRequest request) {
        Optional<TransactionRecord> existing = repository.findById(request.eventId());
        if (existing.isPresent()) {
            return asDuplicate(existing.get());
        }
        // An account's currency is established by its first transaction; later ones must match.
        repository.findFirstByAccountId(accountId).ifPresent(prior -> {
            if (!prior.getCurrency().equals(request.currency())) {
                throw new CurrencyMismatchException(accountId, prior.getCurrency(), request.currency());
            }
        });
        try {
            TransactionRecord saved = repository.save(new TransactionRecord(
                    request.eventId(), accountId, request.type(), request.amount(),
                    request.currency(), request.eventTimestamp(), Instant.now(clock)));
            log.info("Transaction applied: eventId={} accountId={} type={} amount={}",
                    saved.getEventId(), accountId, saved.getType(), saved.getAmount());
            countApply(saved.getType(), "applied");
            return new ApplyOutcome(saved, false);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent submission of the same eventId won the insert — treat as duplicate.
            TransactionRecord winner = repository.findById(request.eventId()).orElseThrow(() -> raced);
            return asDuplicate(winner);
        }
    }

    private ApplyOutcome asDuplicate(TransactionRecord existing) {
        log.info("Duplicate transaction ignored: eventId={} accountId={}",
                existing.getEventId(), existing.getAccountId());
        countApply(existing.getType(), "duplicate");
        return new ApplyOutcome(existing, true);
    }

    /**
     * Net balance = sum of CREDITs - sum of DEBITs. Computed with database aggregates
     * rather than loading every transaction, so it stays O(1) work for the service
     * regardless of account size. Order-independent by construction.
     */
    @Transactional(readOnly = true)
    public BalanceResponse balance(String accountId) {
        BigDecimal credits = repository.sumAmount(accountId, TransactionType.CREDIT);
        BigDecimal debits = repository.sumAmount(accountId, TransactionType.DEBIT);
        long creditCount = repository.countByAccountIdAndType(accountId, TransactionType.CREDIT);
        long debitCount = repository.countByAccountIdAndType(accountId, TransactionType.DEBIT);
        String currency = repository.findFirstByAccountId(accountId)
                .map(TransactionRecord::getCurrency).orElse(null);
        return new BalanceResponse(accountId, credits.subtract(debits), currency,
                creditCount, debitCount, creditCount + debitCount, Instant.now(clock));
    }

    /** Account view: balance plus the most recent transactions (newest first, limited at the DB). */
    @Transactional(readOnly = true)
    public AccountResponse account(String accountId, int recentLimit) {
        BalanceResponse bal = balance(accountId);
        List<TransactionView> recent = repository
                .findByAccountIdOrderByEventTimestampDesc(accountId, PageRequest.of(0, recentLimit))
                .stream()
                .map(TransactionView::from)
                .toList();
        return new AccountResponse(accountId, bal.balance(), bal.currency(), bal.transactionCount(), recent);
    }

    private void countApply(TransactionType type, String outcome) {
        meterRegistry.counter("ledger_transactions_applied_total",
                "type", type.name(), "outcome", outcome).increment();
    }
}
