package com.eventledger.account.repo;

import com.eventledger.account.domain.TransactionRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {

    /** All transactions for an account, oldest event first. */
    List<TransactionRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
