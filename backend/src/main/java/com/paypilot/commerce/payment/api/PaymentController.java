package com.paypilot.commerce.payment.api;

import com.paypilot.commerce.payment.PaymentService;
import com.paypilot.commerce.payment.api.dto.InitiatePaymentRequest;
import com.paypilot.commerce.payment.api.dto.PaymentResponse;
import com.paypilot.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Owner-scoped: initiating against someone else's order is a 404. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse initiate(@AuthenticationPrincipal AuthenticatedUser user,
                                    @jakarta.validation.Valid
                                    @RequestBody InitiatePaymentRequest request) {
        return paymentService.initiate(user.userId(), request.orderId());
    }

    /**
     * Gateway callback. Authenticates via HMAC signature over the raw body -
     * deliberately OUTSIDE the JWT perimeter (gateways hold no user tokens),
     * so it is permitted anonymously in SecurityConfig and verified here.
     * Always 200 after a valid signature; failures are acked-and-logged, not
     * errored, or the gateway would retry forever.
     */
    @PostMapping(value = "/webhook", consumes = {"application/json", "text/plain", "*/*"})
    public ResponseEntity<Map<String, String>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        PaymentService.Outcome outcome =
                paymentService.processWebhook(rawBody, signature, eventId);
        return ResponseEntity.ok(Map.of("status",
                outcome == PaymentService.Outcome.PROCESSED ? "processed" : "ignored"));
    }
}
