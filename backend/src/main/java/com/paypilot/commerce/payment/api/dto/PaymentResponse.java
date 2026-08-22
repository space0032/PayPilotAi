package com.paypilot.commerce.payment.api.dto;

import java.math.BigDecimal;

/**
 * Checkout payload handed to the client. gatewayOrderId is what a real
 * Razorpay.js checkout would bind to; amount/currency let the client
 * render the pay page without another round trip.
 */
public record PaymentResponse(
        Long paymentId,
        Long orderId,
        String gatewayOrderId,
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String status) {
}
