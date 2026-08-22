package com.paypilot.commerce.payment.gateway;

/** Request to create a gateway-side order. Amounts in paise, always. */
public record GatewayOrderRequest(long amountPaise, String currency, String receipt) {

    public GatewayOrderRequest {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be positive: " + amountPaise);
        }
    }
}
