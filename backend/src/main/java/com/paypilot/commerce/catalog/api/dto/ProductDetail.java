package com.paypilot.commerce.catalog.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Full product view: description, free-form attributes and live stock.
 */
public record ProductDetail(
        Long id,
        String sku,
        String brand,
        String title,
        String description,
        BigDecimal price,
        String currency,
        BigDecimal rating,
        Map<String, Object> attributes,
        CategoryDto category,
        int available) {
}
