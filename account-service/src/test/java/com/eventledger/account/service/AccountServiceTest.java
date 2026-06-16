package com.eventledger.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repo.TransactionRepository;
import com.eventledger.account.web.dto.AccountResponse;
import com.eventledger.account.web.dto.ApplyTransactionRequest;
import com.eventledger.account.web.dto.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountServiceTest {

    @Autowired
    private AccountService service;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private static ApplyTransactionRequest req(String eventId, TransactionType type, String amount, String ts) {
        return new ApplyTransactionRequest(eventId, type, new BigDecimal(amount), "USD", Instant.parse(ts));
    }

    @Test
    void applyingSameEventTwiceIsIdempotent() {
        ApplyTransactionRequest r = req("e1", TransactionType.CREDIT, "100.00", "2026-05-15T10:00:00Z");

        ApplyOutcome first = service.apply("acct-1", r);
        ApplyOutcome second = service.apply("acct-1", r);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
        // Balance reflects a single application, not two.
        assertThat(service.balance("acct-1").balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void balanceIsCorrectRegardlessOfArrivalOrder() {
        // CREDIT 100 @ 10:00 arrives first; DEBIT 30 @ 09:00 (earlier) arrives second.
        service.apply("acct-1", req("e1", TransactionType.CREDIT, "100.00", "2026-05-15T10:00:00Z"));
        service.apply("acct-1", req("e2", TransactionType.DEBIT, "30.00", "2026-05-15T09:00:00Z"));

        assertThat(service.balance("acct-1").balance()).isEqualByComparingTo("70.00");

        // Account view lists recent transactions newest-first by event time.
        AccountResponse account = service.account("acct-1", 10);
        assertThat(account.recentTransactions())
                .extracting(TransactionView::eventId)
                .containsExactly("e1", "e2");
    }

    @Test
    void balanceIsSumOfCreditsMinusDebits() {
        service.apply("acct-2", req("c1", TransactionType.CREDIT, "200.00", "2026-05-15T10:00:00Z"));
        service.apply("acct-2", req("c2", TransactionType.CREDIT, "50.00", "2026-05-15T11:00:00Z"));
        service.apply("acct-2", req("d1", TransactionType.DEBIT, "75.00", "2026-05-15T12:00:00Z"));

        var balance = service.balance("acct-2");
        assertThat(balance.balance()).isEqualByComparingTo("175.00");
        assertThat(balance.creditCount()).isEqualTo(2);
        assertThat(balance.debitCount()).isEqualTo(1);
        assertThat(balance.transactionCount()).isEqualTo(3);
    }

    @Test
    void rejectsTransactionWithMismatchedCurrency() {
        // First transaction establishes the account currency as USD.
        service.apply("acct-cur", req("c1", TransactionType.CREDIT, "100.00", "2026-05-15T10:00:00Z"));

        ApplyTransactionRequest eur = new ApplyTransactionRequest(
                "c2", TransactionType.DEBIT, new BigDecimal("10.00"), "EUR",
                Instant.parse("2026-05-15T11:00:00Z"));

        assertThatThrownBy(() -> service.apply("acct-cur", eur))
                .isInstanceOf(CurrencyMismatchException.class);

        // Rejected transaction left no trace; balance is untouched.
        assertThat(service.balance("acct-cur").transactionCount()).isEqualTo(1);
        assertThat(service.balance("acct-cur").balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void unknownAccountHasZeroBalance() {
        assertThat(service.balance("nobody").balance()).isEqualByComparingTo("0");
        assertThat(service.balance("nobody").transactionCount()).isZero();
    }
}
