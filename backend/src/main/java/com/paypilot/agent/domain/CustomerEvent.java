package com.paypilot.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-cutting behavioral event tied to a user and/or agent session
 * (V2 schema). The agent writes SPEND_RESERVED rows here so cumulative
 * session spend survives even though the payments table has no session
 * linkage - the event log IS the ledger.
 */
@Entity
@Table(name = "customer_events")
public class CustomerEvent {

    public static final String SPEND_RESERVED = "SPEND_RESERVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(nullable = false, length = 60)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CustomerEvent() {
        // JPA
    }

    public CustomerEvent(Long userId, Long sessionId, String type,
                         Map<String, Object> payload) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.type = type;
        this.payload = payload == null ? Map.of() : payload;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getType() {
        return type;
    }
}
