package com.paypilot.commerce.cart.api.dto;

import com.paypilot.commerce.cart.domain.CartItem;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddItemRequest(
        @NotNull Long productId,
        @NotNull @Min(1) @Max(CartItem.MAX_QUANTITY) Integer quantity) {
}
