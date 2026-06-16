package com.eventledger.account.repo;

import com.eventledger.account.domain.TransactionRecord;
import com.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {

    /** Any one existing transaction for the account — used to read the account's established currency. */
    Optional<TransactionRecord> findFirstByAccountId(String accountId);

    /**
     * Sum of amounts for an account of a given type, computed in the database.
     * Avoids loading every transaction into memory just to total it.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionRecord t "
            + "WHERE t.accountId = :accountId AND t.type = :type")
    BigDecimal sumAmount(@Param("accountId") String accountId, @Param("type") TransactionType type);

    long countByAccountIdAndType(String accountId, TransactionType type);

    /** Most recent transactions first, limited at the database via {@link Pageable}. */
    List<TransactionRecord> findByAccountIdOrderByEventTimestampDesc(String accountId, Pageable pageable);
}
