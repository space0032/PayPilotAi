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

/**
 * One payment ATTEMPT against the gateway, mapped onto the V2 schema.
 *
 * Invariants (DB-enforced where possible):
 *  - amount_paise immutable after creation; webhooks must match it
 *  - razorpay_order_id UNIQUE: exact webhook correlation, no heuristics
 *  - status moves only along PaymentStatus.canTransitionTo paths
 */
@Entity
@Table(name = "payments")
public class Payment {

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
     * Canonical happy path: a capture event implies the customer completed
     * every intermediate stage. Each hop is FSM-validated so an illegal jump
     * fails loudly instead of inventing money.
     */
    public void markCaptured(String gatewayPaymentId) {
        for (PaymentStatus step : new PaymentStatus[]{
                PaymentStatus.PAYMENT_PENDING, PaymentStatus.AUTHORIZED,
                PaymentStatus.PROCESSING, PaymentStatus.SUCCESS}) {
            if (!canTransitionTo(step)) {
                throw new IllegalStateException(
                        "Payment " + id + " cannot advance from " + status + " to " + step);
            }
            status = step;
        }
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
