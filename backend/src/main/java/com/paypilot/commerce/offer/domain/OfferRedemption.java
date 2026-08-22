package com.paypilot.commerce.offer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Immutable receipt that an offer discounted a specific order for a user.
 * Written at checkout; apply-time usage checks count these rows.
 */
@Entity
@Table(name = "offer_redemptions")
public class OfferRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "discount_paise", nullable = false)
    private long discountPaise;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OfferRedemption() {
        // JPA
    }

    public OfferRedemption(Long offerId, Long userId, Long orderId, long discountPaise) {
        this.offerId = offerId;
        this.userId = userId;
        this.orderId = orderId;
        this.discountPaise = discountPaise;
    }

    public Long getOfferId() {
        return offerId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getDiscountPaise() {
        return discountPaise;
    }
}
