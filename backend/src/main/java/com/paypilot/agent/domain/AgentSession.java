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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One autonomous shopping session (V2 schema). Carries the purchase
 * consent FSM - the hard guardrail that separates "the AI looked at
 * things" from "the AI spent money".
 */
@Entity
@Table(name = "agent_sessions")
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_state", nullable = false, length = 20)
    private ConsentState consentState = ConsentState.NONE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentSession() {
        // JPA
    }

    public AgentSession(Long userId, String title) {
        this.userId = userId;
        this.title = title;
    }

    /** The agent asks; only a human may confirm. */
    public void requestConsent() {
        advance(ConsentState.REQUESTED);
    }

    /** Simulated human approval (mock planner); live flow hits this via API. */
    public void confirmConsent() {
        advance(ConsentState.CONFIRMED);
    }

    /** Payment initiation consumes the grant: one consent, one purchase. */
    public void consumeConsent() {
        advance(ConsentState.CONSUMED);
    }

    private void advance(ConsentState target) {
        if (!consentState.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Consent for session " + id + " cannot move from "
                            + consentState + " to " + target);
        }
        consentState = target;
    }

    public boolean consentGranted() {
        return consentState == ConsentState.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public ConsentState getConsentState() {
        return consentState;
    }
}
