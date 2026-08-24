package com.paypilot.agent.repo;

import com.paypilot.agent.domain.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    /** Consent asks whose approval window elapsed (updated_at anchors the TTL). */
    @Query("""
            SELECT s FROM AgentSession s
            WHERE s.consentState = com.paypilot.agent.domain.ConsentState.REQUESTED
              AND s.updatedAt < :cutoff
            """)
    List<AgentSession> findStaleRequested(@Param("cutoff") Instant cutoff);

    /**
     * Conditional flip REQUESTED -> EXPIRED; zero rows means something
     * moved first (human answered, or payment consumed a later grant) -
     * the sweeper then leaves that session untouched.
     */
    @Modifying
    @Query("""
            UPDATE AgentSession s
            SET s.consentState = com.paypilot.agent.domain.ConsentState.EXPIRED
            WHERE s.id = :id
              AND s.consentState = com.paypilot.agent.domain.ConsentState.REQUESTED
              AND s.updatedAt < :cutoff
            """)
    int expireIfStillRequested(@Param("id") Long id,
                               @Param("cutoff") Instant cutoff);
}
