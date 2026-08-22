package com.paypilot.commerce.cart.api.dto;

import java.math.BigDecimal;

/**
 * Line view: current catalog pricing (authoritative for what checkout will
 * charge) plus the snapshot captured at add-time, so clients can flag
 * price drift.
 */
public record CartItemResponse(
        Long productId,
        String sku,
        String brand,
        String title,
        int quantity,
        int available,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        boolean priceChanged,
        BigDecimal addedAtPrice) {
}
