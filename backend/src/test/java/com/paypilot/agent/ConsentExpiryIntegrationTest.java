package com.paypilot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nobody waits forever for an approval: stale REQUESTED sessions flip to
 * terminal EXPIRED, get a SYSTEM note in their chat log, and can never
 * authorize a payment afterwards. Idempotent by conditional update -
 * sweeping twice expires nothing new, and fresh asks are left alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties =
        "paypilot.security.rate-limit.auth-capacity-per-minute=100")
class ConsentExpiryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ConsentReconciliationService sweeper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestRestTemplate http;

    private Long newUser(String label) {
        String email = label + "-" + System.nanoTime() + "@example.com";
        http.postForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "sup3rsafe!"), Map.class);
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** Raw session insert keeps the fixture independent of any planner. */
    private long newSession(Long userId, String requestedAgoSql) {
        jdbc.update("""
                INSERT INTO agent_sessions (user_id, title, consent_state,
                                            created_at, updated_at)
                VALUES (?, ?, 'REQUESTED',
                        now() - INTERVAL '%s', now() - INTERVAL '%s')
                """.formatted(requestedAgoSql, requestedAgoSql),
                userId, "fixture goal");
        return jdbc.queryForObject(
                "SELECT id FROM agent_sessions WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", Long.class, userId);
    }

    private String consentOf(long sessionId) {
        return jdbc.queryForObject(
                "SELECT consent_state FROM agent_sessions WHERE id = ?",
                String.class, sessionId);
    }

    @Test
    void stale_requests_expire_with_a_note_and_sweep_is_idempotent() {
        Long userId = newUser("consent-expiry");
        long stale = newSession(userId, "1 hour");
        long fresh = newSession(newUser("consent-fresh"), "2 minutes");

        int expired = sweeper.expireStaleConsents();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(consentOf(stale)).isEqualTo("EXPIRED");
        // Fresh ask untouched.
        assertThat(consentOf(fresh)).isEqualTo("REQUESTED");

        // The chat log records why the window closed.
        Integer notes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_messages WHERE session_id = ? "
                        + "AND role = 'SYSTEM' AND content LIKE '%expired%'",
                Integer.class, stale);
        assertThat(notes).isEqualTo(1);

        // Second sweep is a no-op: terminal states stay put.
        assertThat(sweeper.expireStaleConsents()).isEqualTo(0);
        assertThat(consentOf(stale)).isEqualTo("EXPIRED");
    }
}
