package com.paypilot.commerce.payment.gateway;

/** Gateway-side order as returned by the provider. */
public record GatewayOrder(String id, long amountPaise, String currency, String status) {
}
