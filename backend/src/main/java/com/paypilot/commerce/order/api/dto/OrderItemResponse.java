package com.paypilot.commerce.order.api.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String sku,
        String brand,
        String title,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {
}
