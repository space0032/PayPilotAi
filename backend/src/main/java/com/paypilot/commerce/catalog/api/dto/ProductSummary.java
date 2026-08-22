package com.paypilot.commerce.catalog.api.dto;

import java.math.BigDecimal;

/**
 * List-view projection. Prices leave the API as decimal rupees (exact
 * BigDecimal scale 2); paise stay an internal storage detail.
 */
public record ProductSummary(
        Long id,
        String sku,
        String brand,
        String title,
        BigDecimal price,
        BigDecimal rating) {
}
