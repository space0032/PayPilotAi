package com.paypilot.commerce.offer.domain;

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
 * A discount offer. Value semantics live in the DB CHECKs as well:
 * PERCENTAGE stores basis points (1..10000), FLAT stores paise (>= 1).
 */
@Entity
@Table(name = "offers")
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferType type;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount_paise")
    private Long maxDiscountPaise;

    @Column(name = "min_cart_paise", nullable = false)
    private long minCartPaise;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "usage_limit_per_user", nullable = false)
    private int usageLimitPerUser;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Offer() {
        // JPA
    }

    public Offer(String code, OfferType type, long discountValue, Long maxDiscountPaise,
                 long minCartPaise, Instant validFrom, Instant validTo,
                 int usageLimitPerUser, boolean active) {
        this.code = code;
        this.type = type;
        this.discountValue = discountValue;
        this.maxDiscountPaise = maxDiscountPaise;
        this.minCartPaise = minCartPaise;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.usageLimitPerUser = usageLimitPerUser;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public OfferType getType() {
        return type;
    }

    public long getDiscountValue() {
        return discountValue;
    }

    public Long getMaxDiscountPaise() {
        return maxDiscountPaise;
    }

    public long getMinCartPaise() {
        return minCartPaise;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public int getUsageLimitPerUser() {
        return usageLimitPerUser;
    }

    public boolean isActive() {
        return active;
    }
}
