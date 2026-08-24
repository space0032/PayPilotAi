package com.paypilot.commerce.payment.gateway;

/**
 * The only seam between PayPilot and a payment gateway. Domain code never
 * imports SDK classes; swapping mock for live is a bean swap.
 */
public interface PaymentGatewayPort {

    GatewayOrder createOrder(GatewayOrderRequest request);

    /**
     * Refunds part or all of a captured payment. Implementations must
     * treat refund calls as at-least-once: gateway idempotency (Razorpay
     * dedupes identical refunds) plus our own SUCCESS->REFUNDED
     * conditional update keep double refunds impossible.
     */
    GatewayRefund refund(String gatewayPaymentId, long amountPaise);
}
