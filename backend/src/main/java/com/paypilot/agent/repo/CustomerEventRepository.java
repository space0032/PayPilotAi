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

    /**
     * Rolling-window spend for one user across ALL sessions - the daily
     * cap input. Timezone-free by design: the caller passes a cutoff
     * instant (now minus 24h), no calendar-day ambiguity.
     */
    @Query(value = """
            SELECT COALESCE(SUM((payload->>'amountPaise')::bigint), 0)
            FROM customer_events
            WHERE user_id = :userId AND type = 'SPEND_RESERVED'
              AND created_at >= :since
            """, nativeQuery = true)
    long sumUserSpendSince(@Param("userId") Long userId,
                           @Param("since") java.time.Instant since);
}
