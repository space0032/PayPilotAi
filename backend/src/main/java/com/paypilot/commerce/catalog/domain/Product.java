package com.paypilot.commerce.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A sellable product.
 *
 * Mapping notes:
 *  - categoryId is kept as a bare FK id, not a {@code @ManyToOne} association:
 *    catalog reads never navigate the object graph, so we avoid lazy-loading
 *    pitfalls and keep modules loosely coupled.
 *  - price lives as raw paise here; converting to the {@code Money} domain type
 *    is the job of application services at module boundaries.
 *  - attributes is a free-form JSONB document (size, specs, colour...) whose
 *    shape varies per category - exactly what relational columns are bad at.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 80)
    private String brand;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** ISO 4217 currency code for price_paise (e.g. "INR", "USD"). */
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Canonical stored amount in INR minor units (paise). Never floating point. */
    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> attributes = new HashMap<>();

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
        // JPA
    }

    public Product(Long categoryId, String sku, String brand, String title,
                   String description, long pricePaise, BigDecimal rating,
                   Map<String, Object> attributes) {
        this(categoryId, sku, brand, title, description, "INR", pricePaise, rating, attributes);
    }

    public Product(Long categoryId, String sku, String brand, String title,
                   String description, String currency, long pricePaise, BigDecimal rating,
                   Map<String, Object> attributes) {
        this.categoryId = categoryId;
        this.sku = sku;
        this.brand = brand;
        this.title = title;
        this.description = description;
        this.currency = currency == null ? "INR" : currency.toUpperCase();
        this.pricePaise = pricePaise;
        this.rating = rating;
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getSku() {
        return sku;
    }

    public String getBrand() {
        return brand;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void deactivate() {
        this.active = false;
    }
}
