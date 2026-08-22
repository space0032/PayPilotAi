package com.paypilot.commerce.cart.api.dto;

import java.math.BigDecimal;

/**
 * Cart view with server-authoritative pricing. subtotal - discount = total;
 * discount is 0.00 and appliedOfferCode null when no offer is active.
 */
public record CartResponse(
        Long cartId,
        java.util.List<CartItemResponse> items,
        int totalItems,
        BigDecimal subtotal,
        String appliedOfferCode,
        BigDecimal discount,
        BigDecimal total) {
}
