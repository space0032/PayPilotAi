package com.paypilot.commerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A purchased line frozen at checkout. unit_price_paise is the price the
 * customer actually agreed to - not a live reference.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_paise", nullable = false)
    private long unitPricePaise;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(Long orderId, Long productId, int quantity, long unitPricePaise) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPricePaise = unitPricePaise;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPricePaise() {
        return unitPricePaise;
    }
}
