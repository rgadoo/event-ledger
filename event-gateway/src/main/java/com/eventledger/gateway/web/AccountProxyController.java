package com.eventledger.gateway.web;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.dto.BalanceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-through to the internal Account Service for balance queries.
 *
 * <p>The Account Service is not exposed to external clients, so the Gateway proxies
 * balance reads. The call goes through the same circuit breaker as event application;
 * when the Account Service is unavailable the breaker/fallback yields a clear 503
 * (via {@code AccountServiceUnavailableException}) rather than hanging.
 */
@RestController
public class AccountProxyController {

    private final AccountServiceClient accountClient;

    public AccountProxyController(AccountServiceClient accountClient) {
        this.accountClient = accountClient;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public BalanceView balance(@PathVariable String accountId) {
        return accountClient.getBalance(accountId);
    }
}
