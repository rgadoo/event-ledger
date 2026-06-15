package com.eventledger.gateway.repo;

import com.eventledger.gateway.domain.EventRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<EventRecord, String> {

    /** Events for an account in chronological order, regardless of arrival order. */
    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
