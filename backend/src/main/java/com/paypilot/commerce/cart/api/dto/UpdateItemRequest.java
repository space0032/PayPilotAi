package com.paypilot.commerce.cart.api.dto;

import com.paypilot.commerce.cart.domain.CartItem;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Sets the absolute quantity for a product line; zero removes the line.
 */
public record UpdateItemRequest(
        @NotNull @Min(0) @Max(CartItem.MAX_QUANTITY) Integer quantity) {
}
