package com.eventledger.account.web;

import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.ApplyOutcome;
import com.eventledger.account.web.dto.AccountResponse;
import com.eventledger.account.web.dto.ApplyTransactionRequest;
import com.eventledger.account.web.dto.BalanceResponse;
import com.eventledger.account.web.dto.TransactionView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Apply a transaction to an account. Idempotent on {@code eventId}:
     * a new transaction returns 201, a replay returns 200 with the original.
     */
    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionView> apply(@PathVariable String accountId,
                                                 @Valid @RequestBody ApplyTransactionRequest request) {
        ApplyOutcome outcome = accountService.apply(accountId, request);
        TransactionView body = TransactionView.from(outcome.record());
        return ResponseEntity
                .status(outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(body);
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return accountService.balance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountResponse account(@PathVariable String accountId,
                                   @RequestParam(defaultValue = "20") int recentLimit) {
        return accountService.account(accountId, recentLimit);
    }
}
