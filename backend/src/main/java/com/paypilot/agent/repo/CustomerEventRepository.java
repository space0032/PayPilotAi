package com.paypilot.agent.repo;

import com.paypilot.agent.domain.CustomerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerEventRepository extends JpaRepository<CustomerEvent, Long> {

    /** Sum of amountPaise payloads reserved against one session's budget. */
    @Query(value = """
            SELECT COALESCE(SUM((payload->>'amountPaise')::bigint), 0)
            FROM customer_events
            WHERE session_id = :sessionId AND type = 'SPEND_RESERVED'
            """, nativeQuery = true)
    long sumReservedSpend(@Param("sessionId") Long sessionId);
}
