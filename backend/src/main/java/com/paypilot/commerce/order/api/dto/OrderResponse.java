package com.paypilot.commerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order view. Invariant: total = subtotal - discount (also enforced by the
 * database CHECK chk_order_total_math).
 */
public record OrderResponse(
        Long orderId,
        String status,
        List<OrderItemResponse> items,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal total,
        Instant createdAt) {
}
