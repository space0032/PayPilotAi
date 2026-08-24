package com.paypilot.commerce.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Local stand-in for Razorpay's Orders API. Generates realistic-looking
 * order ids and echoes the amount back - no network, no account needed.
 *
 * The real adapter (Phase 14+) will call the live API; everything else in
 * the codebase already treats it as opaque through the port.
 */
@Component
@ConditionalOnProperty(name = "paypilot.payments.gateway", havingValue = "mock")
public class MockRazorpayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockRazorpayAdapter.class);

    @Override
    public GatewayOrder createOrder(GatewayOrderRequest request) {
        String id = "order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("MOCK gateway order created: {} amount={} {}", id, request.amountPaise(), request.currency());
        return new GatewayOrder(id, request.amountPaise(), request.currency(), "created");
    }

    @Override
    public GatewayRefund refund(String gatewayPaymentId, long amountPaise) {
        String id = "rfnd_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        log.info("MOCK gateway refund created: {} payment={} amount={}",
                id, gatewayPaymentId, amountPaise);
        return new GatewayRefund(id, amountPaise, "processed");
    }
}
