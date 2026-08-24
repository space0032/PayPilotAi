package com.paypilot.commerce.payment.gateway;

/** Gateway-side refund as returned by the provider. */
public record GatewayRefund(String id, long amountPaise, String status) {
}
