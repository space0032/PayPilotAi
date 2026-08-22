package com.paypilot.commerce.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A line item. product_id is a bare FK (no association): cart reads batch
 * fetch product data explicitly, keeping modules decoupled and queries
 * predictable.
 *
 * price_snapshot_paise is the unit price captured when the item was added;
 * it is audit history, not pricing truth - checkout re-prices against the
 * live catalog before any money moves.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    public static final int MAX_QUANTITY = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_snapshot_paise", nullable = false)
    private long priceSnapshotPaise;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CartItem() {
        // JPA
    }

    public CartItem(Long cartId, Long productId, int quantity, long priceSnapshotPaise) {
        this.cartId = cartId;
        this.productId = productId;
        this.quantity = quantity;
        this.priceSnapshotPaise = priceSnapshotPaise;
    }

    public Long getId() {
        return id;
    }

    public Long getCartId() {
        return cartId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getPriceSnapshotPaise() {
        return priceSnapshotPaise;
    }

    public void setPriceSnapshotPaise(long priceSnapshotPaise) {
        this.priceSnapshotPaise = priceSnapshotPaise;
    }
}
