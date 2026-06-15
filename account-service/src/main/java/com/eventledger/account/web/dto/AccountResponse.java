package com.eventledger.account.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        long transactionCount,
        List<TransactionView> recentTransactions
) {
}
