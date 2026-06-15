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
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     */
    @Transactional
    public ApplyOutcome apply(String accountId, ApplyTransactionRequest request) {
        return repository.findById(request.eventId())
                .map(existing -> {
                    log.info("Duplicate transaction ignored: eventId={} accountId={}",
                            existing.getEventId(), existing.getAccountId());
                    countApply(existing.getType(), "duplicate");
                    return new ApplyOutcome(existing, true);
                })
                .orElseGet(() -> {
                    TransactionRecord saved = repository.save(new TransactionRecord(
                            request.eventId(), accountId, request.type(), request.amount(),
                            request.currency(), request.eventTimestamp(), Instant.now(clock)));
                    log.info("Transaction applied: eventId={} accountId={} type={} amount={}",
                            saved.getEventId(), accountId, saved.getType(), saved.getAmount());
                    countApply(saved.getType(), "applied");
                    return new ApplyOutcome(saved, false);
                });
    }

    /** Net balance = sum of CREDITs - sum of DEBITs. Order-independent by construction. */
    @Transactional(readOnly = true)
    public BalanceResponse balance(String accountId) {
        List<TransactionRecord> txns = repository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal balance = BigDecimal.ZERO;
        long credits = 0;
        long debits = 0;
        String currency = null;
        for (TransactionRecord t : txns) {
            if (t.getType() == TransactionType.CREDIT) {
                balance = balance.add(t.getAmount());
                credits++;
            } else {
                balance = balance.subtract(t.getAmount());
                debits++;
            }
            if (currency == null) {
                currency = t.getCurrency();
            }
        }
        return new BalanceResponse(accountId, balance, currency, credits, debits, txns.size(), Instant.now(clock));
    }

    /** Account view: balance plus the most recent transactions (newest first). */
    @Transactional(readOnly = true)
    public AccountResponse account(String accountId, int recentLimit) {
        List<TransactionRecord> txns = repository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BalanceResponse bal = balance(accountId);
        List<TransactionView> recent = txns.stream()
                .sorted(Comparator.comparing(TransactionRecord::getEventTimestamp).reversed())
                .limit(recentLimit)
                .map(TransactionView::from)
                .toList();
        return new AccountResponse(accountId, bal.balance(), bal.currency(), txns.size(), recent);
    }

    private void countApply(TransactionType type, String outcome) {
        meterRegistry.counter("ledger_transactions_applied_total",
                "type", type.name(), "outcome", outcome).increment();
    }
}
