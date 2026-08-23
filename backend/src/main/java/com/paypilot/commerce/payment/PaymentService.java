package com.paypilot.commerce.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.common.error.ConflictException;
import com.paypilot.common.error.NotFoundException;
import com.paypilot.common.error.UnauthorizedException;
import com.paypilot.commerce.order.domain.Order;
import com.paypilot.commerce.order.domain.OrderStatus;
import com.paypilot.commerce.order.repo.OrderItemRepository;
import com.paypilot.commerce.order.repo.OrderRepository;
import com.paypilot.commerce.payment.api.dto.PaymentResponse;
import com.paypilot.commerce.payment.domain.Payment;
import com.paypilot.commerce.payment.domain.PaymentEvent;
import com.paypilot.commerce.payment.domain.PaymentStatus;
import com.paypilot.commerce.payment.gateway.GatewayOrder;
import com.paypilot.commerce.payment.gateway.GatewayOrderRequest;
import com.paypilot.commerce.payment.gateway.PaymentGatewayPort;
import com.paypilot.commerce.payment.repo.PaymentEventRepository;
import com.paypilot.commerce.payment.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * Payment lifecycle over the V2 payments/payment_events schema.
 * Webhooks are the only door through which gateway truth enters.
 *
 * Webhook contract:
 *  bad signature        -> 401, nothing touched
 *  unknown event/order  -> 200 ack, ignored (so gateways stop retrying)
 *  valid + FSM-legal    -> ledger row + payment transition + order
 *                          confirmation + stock settlement, ATOMIC
 *  replays / conflicts  -> 200 ack, no-op (idempotent by design)
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final StockSettlement stockSettlement;
    private final PaymentGatewayPort gatewayPort;
    private final WebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final java.time.Duration attemptTtl;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentEventRepository eventRepository,
                          OrderRepository orderRepository,
                          OrderItemRepository orderItemRepository,
                          InventoryRepository inventoryRepository,
                          StockSettlement stockSettlement,
                          PaymentGatewayPort gatewayPort,
                          WebhookSignatureVerifier signatureVerifier,
                          ObjectMapper objectMapper,
                          Clock clock,
                          @Value("${paypilot.payments.attempt-ttl-minutes:30}") long attemptTtlMinutes) {
        this.paymentRepository = paymentRepository;
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.stockSettlement = stockSettlement;
        this.gatewayPort = gatewayPort;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.attemptTtl = java.time.Duration.ofMinutes(attemptTtlMinutes);
    }

    @Transactional
    public PaymentResponse initiate(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException("INVALID_ORDER_STATE",
                    "Order %d is %s; payments can only start from PENDING_PAYMENT"
                            .formatted(orderId, order.getStatus()));
        }
        // Idempotent: an in-flight attempt IS the attempt. A terminal attempt
        // means it failed/expired - a fresh attempt is legitimate.
        var existing = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .filter(p -> p.getStatus().isActive())
                .map(this::toResponse);
        return existing.orElseGet(() -> createAttempt(userId, order));
    }

    private PaymentResponse createAttempt(Long userId, Order order) {
        // A retry (previous attempt FAILED/EXPIRED/CANCELLED) released its
        // reservation - re-reserve before offering the gateway another chance,
        // otherwise a later capture would settle stock we no longer hold.
        if (paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()).isPresent()) {
            reReserve(order);
        }
        GatewayOrder gatewayOrder = gatewayPort.createOrder(
                new GatewayOrderRequest(order.getTotalPaise(), "INR", "order-" + order.getId()));
        if (gatewayOrder.amountPaise() != order.getTotalPaise()) {
            throw new IllegalStateException(
                    "Gateway echoed amount %d for order %d but expected %d"
                            .formatted(gatewayOrder.amountPaise(), order.getId(), order.getTotalPaise()));
        }
        Payment saved = paymentRepository.save(
                new Payment(order.getId(), userId, gatewayOrder.id(), order.getTotalPaise()));
        saved.setExpiresAt(clock.instant().plus(attemptTtl));
        log.info("Payment attempt {} created for order {} (expires at {})",
                saved.getId(), order.getId(), saved.getExpiresAt());
        return toResponse(saved);
    }

    /** Same conditional-update semantics as checkout; shortage aborts loudly. */
    private void reReserve(Order order) {
        for (var item : orderItemRepository.findByOrderId(order.getId())) {
            int updated = inventoryRepository.reserve(
                    item.getProductId(), item.getQuantity(), clock.instant());
            if (updated == 0) {
                throw new ConflictException("INSUFFICIENT_STOCK",
                        "Stock for this order was sold while payment was pending");
            }
        }
    }

    /**
     * Simulated hosted-checkout outcome (mock gateway only). Builds a real
     * event payload and pushes it through the exact signed-webhook pipeline
     * the live gateway uses.
     */
    @Transactional
    public void simulateCapture(Long userId, Long paymentId) {
        pushSimulatedEvent(userId, paymentId, "payment.captured");
    }

    @Transactional
    public void simulateFailure(Long userId, Long paymentId) {
        pushSimulatedEvent(userId, paymentId, "payment.failed");
    }

    private void pushSimulatedEvent(Long userId, Long paymentId, String event) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        String payId = ("payment.captured".equals(event) ? "pay_sim_" : "pay_sim_fail_")
                + payment.getId();
        String body = buildWebhookBody(event, payId, payment.getRazorpayOrderId(),
                payment.getAmountPaise());
        // Real gateways stamp every delivery with an id; mirror that so the
        // ledger's UNIQUE(event_id) dedupe is exercised on this path too.
        processWebhook(body, signatureVerifier.sign(body),
                "evt_sim_" + java.util.UUID.randomUUID());
    }

    private String buildWebhookBody(String event, String payId, String rzpOrderId,
                                    long amountPaise) {
        try {
            // Build top-down; the chained fluent style would return the last
            // child node and serialize only the inner entity object.
            var root = objectMapper.createObjectNode();
            root.put("event", event);
            var entity = root.putObject("payload").putObject("payment").putObject("entity");
            entity.put("id", payId);
            entity.put("order_id", rzpOrderId);
            entity.put("amount", amountPaise);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build webhook payload", e);
        }
    }

    public enum Outcome { PROCESSED, IGNORED }

    /**
     * Signature is checked against the raw body BEFORE parsing, so forged
     * or malformed payloads never touch state. eventId is the gateway's
     * delivery id (X-Razorpay-Event-Header equivalent) for ledger dedupe.
     */
    @Transactional
    public Outcome processWebhook(String rawBody, String signatureHex, String eventId) {
        if (!signatureVerifier.verify(rawBody, signatureHex)) {
            throw new UnauthorizedException("WEBHOOK_INVALID_SIGNATURE",
                    "Webhook signature verification failed");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Webhook unparseable despite valid signature: {}", e.getMessage());
            return Outcome.IGNORED;
        }
        if (eventId != null && eventRepository.existsByEventId(eventId)) {
            log.info("Duplicate webhook delivery {}; acknowledging without action", eventId);
            return Outcome.IGNORED;
        }

        String event = root.path("event").asText("");
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String razorpayOrderId = entity.path("order_id").asText(null);
        String razorpayPaymentId = entity.path("id").asText(null);
        long amount = entity.path("amount").asLong(-1);

        boolean knownEvent = "payment.captured".equals(event)
                || "payment.failed".equals(event)
                || "payment.authorized".equals(event);
        if (!knownEvent || razorpayOrderId == null) {
            log.info("Ignoring webhook event '{}' (unknown type or missing correlation)", event);
            return Outcome.IGNORED;
        }

        Payment payment = paymentRepository.findByRazorpayOrderId(razorpayOrderId).orElse(null);
        if (payment == null) {
            log.warn("Webhook references unknown razorpay_order_id '{}'; acking without action",
                    razorpayOrderId);
            return Outcome.IGNORED;
        }
        // Tamper guard: gateway must agree with what we asked it to charge.
        if (amount != payment.getAmountPaise()) {
            log.error("Webhook amount mismatch for {}: expected {}, got {}. Ignoring.",
                    razorpayOrderId, payment.getAmountPaise(), amount);
            return Outcome.IGNORED;
        }

        recordDelivery(payment, eventId, event, root);

        switch (event) {
            case "payment.captured" -> applyCapture(payment, razorpayPaymentId);
            case "payment.failed" -> applyFailure(payment, razorpayPaymentId, root);
            case "payment.authorized" -> applyAuthorized(payment, razorpayPaymentId);
            default -> throw new IllegalStateException("unreachable");
        }
        return Outcome.PROCESSED;
    }

    /** Append-only audit row; UNIQUE(event_id) backstops duplicate deliveries. */
    private void recordDelivery(Payment payment, String eventId, String event, JsonNode root) {
        try {
            Map<String, Object> payload = objectMapper.convertValue(
                    root, new TypeReference<Map<String, Object>>() {
                    });
            eventRepository.save(new PaymentEvent(payment.getId(), eventId, event,
                    payload, true));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalStateException("Duplicate webhook delivery " + eventId, e);
        }
    }

    private void applyCapture(Payment payment, String gatewayPaymentId) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Replayed capture for payment {}; no-op", payment.getId());
            return;
        }
        if (gatewayPaymentId != null
                && paymentRepository.existsByRazorpayPaymentIdAndIdNot(
                        gatewayPaymentId, payment.getId())) {
            // UNIQUE(razorpay_payment_id) would 500 at commit; a gateway id
            // already bound to another attempt means corruption or a forged
            // replay - refuse loudly, ack cleanly.
            log.error("Capture for payment {} carries razorpay_payment_id {} "
                    + "already bound to another payment; ignoring",
                    payment.getId(), gatewayPaymentId);
            return;
        }
        if (!payment.canTransitionTo(firstStepTowardSuccess(payment))) {
            log.warn("Conflicting capture for payment {} in state {}; ignoring",
                    payment.getId(), payment.getStatus());
            return;
        }
        payment.markCaptured(gatewayPaymentId);
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Captured payment references missing order %d"
                                .formatted(payment.getOrderId())));
        order.markConfirmed();
        stockSettlement.confirmSale(order.getId());
        log.info("Payment {} SUCCESS; order {} CONFIRMED", payment.getId(), order.getId());
    }

    private PaymentStatus firstStepTowardSuccess(Payment payment) {
        return switch (payment.getStatus()) {
            case CREATED -> PaymentStatus.PAYMENT_PENDING;
            case PAYMENT_PENDING -> PaymentStatus.AUTHORIZED;
            case AUTHORIZED -> PaymentStatus.PROCESSING;
            default -> PaymentStatus.SUCCESS;
        };
    }

    /** Money reserved at the bank but not yet captured; order stays pending. */
    private void applyAuthorized(Payment payment, String gatewayPaymentId) {
        if (payment.getStatus() == PaymentStatus.AUTHORIZED
                || payment.getStatus() == PaymentStatus.PROCESSING
                || payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Replayed/conflicting authorization for payment {}; no-op",
                    payment.getId());
            return;
        }
        if (gatewayPaymentId != null && paymentRepository.existsByRazorpayPaymentIdAndIdNot(
                gatewayPaymentId, payment.getId())) {
            log.error("Authorization for payment {} carries razorpay_payment_id {} "
                    + "already bound to another payment; ignoring",
                    payment.getId(), gatewayPaymentId);
            return;
        }
        try {
            payment.markAuthorized(gatewayPaymentId);
        } catch (IllegalStateException e) {
            log.warn("Conflicting authorization for payment {} in state {}; ignoring",
                    payment.getId(), payment.getStatus());
            return;
        }
        log.info("Payment {} AUTHORIZED", payment.getId());
    }

    private void applyFailure(Payment payment, String gatewayPaymentId, JsonNode root) {
        if (!payment.getStatus().isActive()) {
            log.info("Replayed/conflicting failure for payment {} in state {}; no-op",
                    payment.getId(), payment.getStatus());
            return;
        }
        if (gatewayPaymentId != null
                && paymentRepository.existsByRazorpayPaymentIdAndIdNot(
                        gatewayPaymentId, payment.getId())) {
            log.error("Failure for payment {} carries razorpay_payment_id {} "
                    + "already bound to another payment; ignoring",
                    payment.getId(), gatewayPaymentId);
            return;
        }
        String reason = root.path("payload").path("payment").path("entity")
                .path("error_description").asText("gateway reported failure");
        payment.markFailed(gatewayPaymentId, reason);
        stockSettlement.releaseSale(payment.getOrderId());
        log.info("Payment {} FAILED; stock released, order stays payable", payment.getId());
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getRazorpayOrderId(),
                p.getRazorpayPaymentId(),
                java.math.BigDecimal.valueOf(p.getAmountPaise(), 2),
                p.getCurrency(), p.getStatus().name());
    }
}
