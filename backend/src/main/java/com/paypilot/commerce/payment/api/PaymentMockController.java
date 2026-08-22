package com.paypilot.commerce.payment.api;

import com.paypilot.commerce.payment.PaymentService;
import com.paypilot.security.AuthenticatedUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock-gateway only. Stands in for the hosted Razorpay checkout page:
 * "customer paid" / "customer abandoned". Events flow through the real
 * signed-webhook pipeline, so this exercises production verification code.
 * Vanishes entirely when paypilot.payments.gateway != mock.
 */
@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnProperty(name = "paypilot.payments.gateway", havingValue = "mock")
public class PaymentMockController {

    private final PaymentService paymentService;

    public PaymentMockController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{paymentId}/simulate-capture")
    @ResponseStatus(HttpStatus.OK)
    public void simulateCapture(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable Long paymentId) {
        paymentService.simulateCapture(user.userId(), paymentId);
    }

    @PostMapping("/{paymentId}/simulate-failure")
    @ResponseStatus(HttpStatus.OK)
    public void simulateFailure(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable Long paymentId) {
        paymentService.simulateFailure(user.userId(), paymentId);
    }
}
