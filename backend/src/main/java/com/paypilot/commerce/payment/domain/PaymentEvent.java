package com.paypilot.commerce.payment.domain;

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
 * Append-only webhook audit trail (V2 schema). UNIQUE(event_id) makes
 * duplicate gateway deliveries a constraint violation - the DB itself is
 * the idempotency backstop behind the FSM no-op checks.
 */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "event_id", length = 80)
    private String eventId;

    @Column(nullable = false, length = 60)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentEvent() {
        // JPA
    }

    public PaymentEvent(Long paymentId, String eventId, String type,
                        Map<String, Object> payload, boolean signatureVerified) {
        this.paymentId = paymentId;
        this.eventId = eventId;
        this.type = type;
        this.payload = payload;
        this.signatureVerified = signatureVerified;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }
}
