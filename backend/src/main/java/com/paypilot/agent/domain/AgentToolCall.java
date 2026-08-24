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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One audited tool call (V2 schema): arguments, summarized result, outcome
 * and duration. The append-only trace that makes agent behavior disputable
 * after the fact - evidence, not a log line.
 */
@Entity
@Table(name = "agent_tool_calls")
public class AgentToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 60)
    private String tool;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> arguments = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_summary")
    private Map<String, Object> resultSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolCallStatus status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "duration_ms")
    private Integer durationMs;

    /** X-Request-Id of the HTTP call that triggered this step (V8). */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentToolCall() {
        // JPA
    }

    public AgentToolCall(Long sessionId, String tool,
                         Map<String, Object> arguments,
                         String correlationId) {
        this.sessionId = sessionId;
        this.tool = tool;
        this.arguments = arguments == null ? Map.of() : arguments;
        this.correlationId = correlationId != null && correlationId.length() > 64
                ? correlationId.substring(0, 64) : correlationId;
        this.status = ToolCallStatus.OK;
    }

    public void succeed(Map<String, Object> summary, int durationMs) {
        this.resultSummary = summary;
        this.durationMs = durationMs;
        this.status = ToolCallStatus.OK;
    }

    public void fail(String errorCode, int durationMs, boolean rejected) {
        this.error = errorCode;
        this.durationMs = durationMs;
        this.status = rejected ? ToolCallStatus.REJECTED : ToolCallStatus.ERROR;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getTool() {
        return tool;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Map<String, Object> getResultSummary() {
        return resultSummary;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
