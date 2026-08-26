package com.paypilot.commerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lightweight order row for list views - no items (those live behind the
 * detail endpoint) so a page of history stays one cheap query.
 */
public record OrderSummary(
        Long orderId,
        String status,
        String currency,
        BigDecimal total,
        Instant createdAt) {
}
