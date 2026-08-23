package com.paypilot.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Chat-log line for a session (V2 schema): what the user asked, what the
 * agent said, and tool outputs - the conversational half of the evidence.
 */
@Entity
@Table(name = "agent_messages")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AgentMessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentMessage() {
        // JPA
    }

    public AgentMessage(Long sessionId, AgentMessageRole role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public AgentMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
