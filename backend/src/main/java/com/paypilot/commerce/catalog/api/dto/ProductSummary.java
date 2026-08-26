package com.paypilot.commerce.catalog.api.dto;

import java.math.BigDecimal;

/**
 * List-view projection. Prices leave the API as decimal rupees (exact
 * BigDecimal scale 2); paise stay an internal storage detail.  The
 * currency field indicates which ISO 4217 code the price is denominated
 * in (may differ from the product's native currency if a ?currency
 * conversion was requested).
 */
public record ProductSummary(
        Long id,
        String sku,
        String brand,
        String title,
        BigDecimal price,
        String currency,
        BigDecimal rating) {
}
