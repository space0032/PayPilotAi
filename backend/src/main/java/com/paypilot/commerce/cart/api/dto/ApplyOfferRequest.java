package com.paypilot.commerce.cart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplyOfferRequest(
        @NotBlank @Size(max = 40) String code) {
}
