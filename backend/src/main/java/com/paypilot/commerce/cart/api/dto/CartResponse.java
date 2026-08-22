package com.paypilot.commerce.cart.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long cartId,
        List<CartItemResponse> items,
        int totalItems,
        BigDecimal subtotal) {
}
