package com.eventledger.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repo.TransactionRepository;
import com.eventledger.account.web.dto.ApplyTransactionRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Idempotency must hold even when the same event is applied concurrently. This proves
 * the insert-and-catch path: many threads racing on one {@code eventId} produce exactly
 * one stored transaction and one "applied" outcome, with the balance applied once.
 */
@SpringBootTest
class AccountServiceConcurrencyTest {

    @Autowired
    private AccountService service;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void concurrentApplyOfSameEventAppliesExactlyOnce() throws Exception {
        int threads = 16;
        ApplyTransactionRequest request = new ApplyTransactionRequest(
                "dup-1", TransactionType.CREDIT, new BigDecimal("100.00"), "USD",
                Instant.parse("2026-05-15T10:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ApplyOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();                       // release all threads at once
                return service.apply("acct-1", request);
            }));
        }
        startGate.countDown();

        int applied = 0;
        int duplicates = 0;
        for (Future<ApplyOutcome> f : futures) {
            if (f.get().duplicate()) {
                duplicates++;
            } else {
                applied++;
            }
        }
        pool.shutdown();

        assertThat(applied).as("exactly one thread should apply").isEqualTo(1);
        assertThat(duplicates).as("the rest are duplicates").isEqualTo(threads - 1);
        assertThat(repository.count()).as("only one row stored").isEqualTo(1);
        assertThat(service.balance("acct-1").balance())
                .as("balance applied exactly once")
                .isEqualByComparingTo("100.00");
    }
}
