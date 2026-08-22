package com.paypilot.commerce.payment.gateway;

/**
 * The only seam between PayPilot and a payment gateway. Domain code never
 * imports SDK classes; swapping mock for live is a bean swap.
 */
public interface PaymentGatewayPort {

    GatewayOrder createOrder(GatewayOrderRequest request);
}
