package com.paypilot.agent;

import com.paypilot.agent.domain.AgentMessage;
import com.paypilot.agent.domain.AgentMessageRole;
import com.paypilot.agent.domain.AgentSession;
import com.paypilot.agent.repo.AgentMessageRepository;
import com.paypilot.agent.repo.AgentSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Consent TTL sweep: an approval ask must not dangle forever. Sessions
 * left in REQUESTED past their window are flipped to the FSM's terminal
 * EXPIRED state and a SYSTEM note is appended, so a stale "yes" clicked
 * hours later can never authorize a purchase whose price moved.
 *
 * TTL anchor: agent_sessions.updated_at - set when requestConsent() saved
 * the session (schema law forbids a dedicated consent_expires_at column).
 * Any other session write would refresh the window; none exist today.
 *
 * Concurrency: per-row conditional UPDATE exactly like payment expiry -
 * if confirm/cancel/consume lands between listing and flipping, the
 * predicate no longer matches and that session is skipped untouched.
 */
@Service
public class ConsentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            ConsentReconciliationService.class);

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final Clock clock;
    private final long ttlMinutes;

    public ConsentReconciliationService(AgentSessionRepository sessions,
                                        AgentMessageRepository messages,
                                        Clock clock,
                                        @Value("${paypilot.agent.consent-ttl-minutes:30}")
                                        long ttlMinutes) {
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
        this.ttlMinutes = ttlMinutes;
    }

    @Scheduled(fixedDelayString =
            "${paypilot.agent.consent-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int expired = expireStaleConsents();
            if (expired > 0) {
                log.info("Expired {} stale consent requests", expired);
            }
        } catch (Exception e) {
            // A broken sweep must never kill the scheduler thread.
            log.error("Consent expiry sweep failed", e);
        }
    }

    @Transactional
    public int expireStaleConsents() {
        Instant cutoff = clock.instant().minusSeconds(ttlMinutes * 60);
        List<AgentSession> stale =
                sessions.findStaleRequested(cutoff);
        int count = 0;
        for (var session : stale) {
            if (sessions.expireIfStillRequested(session.getId(), cutoff) == 0) {
                continue; // human answered first; their answer governs
            }
            messages.save(new AgentMessage(session.getId(),
                    AgentMessageRole.SYSTEM,
                    "Purchase approval window elapsed; the request expired."));
            count++;
        }
        return count;
    }
}
