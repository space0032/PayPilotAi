package com.paypilot.commerce.payment.domain;

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
import java.util.List;

/**
 * One payment ATTEMPT against the gateway, mapped onto the V2 schema.
 *
 * Invariants (DB-enforced where possible):
 *  - amount_paise immutable after creation; webhooks must match it
 *  - razorpay_order_id UNIQUE: exact webhook correlation, no heuristics
 *  - status moves only along PaymentStatus.canTransitionTo paths
 *  - a PENDING_PAYMENT order holds reservations; FAILED/EXPIRED/CANCELLED
 *    attempts release them, and initiating a fresh attempt re-reserves -
 *    so money events and stock never drift apart
 */
@Entity
@Table(name = "payments")
public class Payment {

    /** Canonical gateway-side lifecycle from CREATED to SUCCESS. */
    private static final List<PaymentStatus> HAPPY_PATH = List.of(
            PaymentStatus.CREATED,
            PaymentStatus.PAYMENT_PENDING,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCESS);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "razorpay_order_id", nullable = false, unique = true, length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", unique = true, length = 64)
    private String razorpayPaymentId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    /** ISO-4217; VARCHAR(3) since V6 (was CHAR(3) in V2). */
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    public Payment(Long orderId, Long userId, String razorpayOrderId, long amountPaise) {
        this.orderId = orderId;
        this.userId = userId;
        this.razorpayOrderId = razorpayOrderId;
        this.amountPaise = amountPaise;
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return status.canTransitionTo(target);
    }

    /**
     * Canonical happy path from WHATEVER stage the attempt is currently at
     * (a capture event may arrive while the customer's browser is still on
     * PAYMENT_PENDING). Each hop is FSM-validated so an illegal jump fails
     * loudly instead of inventing money.
     */
    public void markCaptured(String gatewayPaymentId) {
        advance(HAPPY_PATH);
        this.razorpayPaymentId = gatewayPaymentId;
    }

    /** payment.authorized webhook: money reserved by the bank, not captured. */
    public void markAuthorized(String gatewayPaymentId) {
        advance(List.of(PaymentStatus.CREATED,
                PaymentStatus.PAYMENT_PENDING,
                PaymentStatus.AUTHORIZED));
        this.razorpayPaymentId = gatewayPaymentId;
    }

    public void markFailed(String gatewayPaymentId, String reason) {
        if (!canTransitionTo(PaymentStatus.FAILED)) {
            throw new IllegalStateException(
                    "Payment " + id + " cannot transition from " + status + " to FAILED");
        }
        this.status = PaymentStatus.FAILED;
        this.razorpayPaymentId = gatewayPaymentId;
        this.failureReason = reason != null && reason.length() > 255
                ? reason.substring(0, 255) : reason;
    }

    private void advance(List<PaymentStatus> path) {
        int idx = path.indexOf(status);
        if (idx < 0) {
            throw new IllegalStateException(
                    "Payment " + id + " in state " + status + " cannot advance along "
                            + path);
        }
        // Walk only FUTURE steps - validating the current state against itself
        // would reject every legal advance before it began.
        for (int i = idx + 1; i < path.size(); i++) {
            PaymentStatus step = path.get(i);
            if (!canTransitionTo(step)) {
                throw new IllegalStateException(
                        "Payment " + id + " cannot transition from " + status + " to " + step);
            }
            status = step;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
