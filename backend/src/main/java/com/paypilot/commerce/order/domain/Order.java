package com.paypilot.commerce.order.domain;

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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stable purchase intent. Money columns satisfy
 * chk_order_total_math (total = subtotal - discount) enforced by the DB.
 *
 * cart_snapshot freezes exactly what was purchased (products, quantities,
 * unit prices, offer) at checkout moment - immune to later catalog edits.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    /** ISO 4217 currency code (e.g. "INR", "USD"). */
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "subtotal_paise", nullable = false)
    private long subtotalPaise;

    @Column(name = "discount_paise", nullable = false)
    private long discountPaise;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @Column(name = "offer_id")
    private Long offerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> cartSnapshot = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
        // JPA
    }

    public Order(Long userId, long subtotalPaise, long discountPaise,
                 long totalPaise, Long offerId, Map<String, Object> cartSnapshot) {
        this(userId, "INR", subtotalPaise, discountPaise, totalPaise, offerId, cartSnapshot);
    }

    public Order(Long userId, String currency, long subtotalPaise, long discountPaise,
                 long totalPaise, Long offerId, Map<String, Object> cartSnapshot) {
        this.userId = userId;
        this.currency = currency == null ? "INR" : currency.toUpperCase();
        this.subtotalPaise = subtotalPaise;
        this.discountPaise = discountPaise;
        this.totalPaise = totalPaise;
        this.offerId = offerId;
        this.cartSnapshot = cartSnapshot;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getSubtotalPaise() {
        return subtotalPaise;
    }

    public long getDiscountPaise() {
        return discountPaise;
    }

    public long getTotalPaise() {
        return totalPaise;
    }

    public Long getOfferId() {
        return offerId;
    }

    /**
     * PENDING_PAYMENT -> CONFIRMED only. Anything else means a webhook is
     * trying to confirm an already-terminal or cancelled order - loud failure
     * beats silent money loss.
     */
    public void markConfirmed() {
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Order " + id + " cannot be confirmed from " + status);
        }
        this.status = OrderStatus.CONFIRMED;
    }

    /** PENDING_PAYMENT -> CANCELLED only; callers release reservations after. */
    public void markCancelled() {
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Order " + id + " cannot be cancelled from " + status);
        }
        this.status = OrderStatus.CANCELLED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getCartSnapshot() {
        return new LinkedHashMap<>(cartSnapshot);
    }
}
