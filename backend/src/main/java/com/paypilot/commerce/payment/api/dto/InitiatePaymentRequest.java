package com.paypilot.commerce.payment.api.dto;

import jakarta.validation.constraints.NotNull;

public record InitiatePaymentRequest(@NotNull Long orderId) {
}
