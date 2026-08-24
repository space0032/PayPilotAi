package com.paypilot.commerce.payment;

import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.order.api.dto.OrderResponse;
import com.paypilot.commerce.payment.api.dto.InitiatePaymentRequest;
import com.paypilot.commerce.payment.api.dto.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment lifecycle end-to-end through the REAL webhook pipeline:
 * initiation idempotency, HMAC enforcement, FSM transitions, atomic
 * stock settlement on capture/failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "paypilot.security.rate-limit.auth-capacity-per-minute=100",
        // Sweeps are triggered explicitly in tests - never by wall clock.
        "paypilot.payments.expiry-sweep-interval-ms=3600000"})
class PaymentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SKU = "SHOE-NK-DOWN12";

    @Autowired
    TestRestTemplate http;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    WebhookSignatureVerifier signer;
    @Autowired
    PaymentReconciliationService reconciliation;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private String newUser(String label) {
        String email = label + "-" + System.nanoTime() + "@example.com";
        return (String) http.postForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "sup3rsafe!"), Map.class)
                .getBody().get("accessToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private Long pid(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getId();
    }

    private void fixture(String sku, long unitPaise, int available) {
        tx.executeWithoutResult(s -> {
            productRepository.setPricePaise(pid(sku), unitPaise);
            inventoryRepository.setAvailable(pid(sku), available);
        });
    }

    private record Inv(int available, int reserved) {
    }

    private Inv invOf(String sku) {
        return jdbc.queryForObject(
                "SELECT available, reserved FROM inventory WHERE product_id = ?",
                (rs, n) -> new Inv(rs.getInt(1), rs.getInt(2)),
                pid(sku));
    }

    /** register -> add 2x SKU at Rs 1000 -> checkout -> initiate payment */
    private record Attempt(String token, OrderResponse order, PaymentResponse payment) {
    }

    private Attempt readyToPay(String label) {
        String token = newUser(label);
        fixture(SKU, 100_000L, 10);
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(new AddItemRequest(pid(SKU), 2), bearer(token)), Void.class);
        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), OrderResponse.class).getBody();
        ResponseEntity<PaymentResponse> initiated = initiate(token, order.orderId());
        assertThat(initiated.getStatusCode().value()).isEqualTo(201);
        return new Attempt(token, order, initiated.getBody());
    }

    private ResponseEntity<PaymentResponse> initiate(String token, Long orderId) {
        return http.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(orderId), bearer(token)),
                PaymentResponse.class);
    }

    private ResponseEntity<String> simulate(String token, Long paymentId, String action) {
        // Header-only request: HttpEntity has no (headers) ctor - passing the
        // headers object alone would silently send them as the BODY.
        return http.exchange("/api/v1/payments/" + paymentId + "/" + action,
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)), String.class);
    }

    private ResponseEntity<String> postWebhook(String body, String signature) {
        return postWebhook(body, signature, null);
    }

    private ResponseEntity<String> postWebhook(String body, String signature, String eventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("X-Razorpay-Signature", signature);
        }
        if (eventId != null) {
            headers.set("X-Razorpay-Event-Id", eventId);
        }
        return http.exchange("/api/v1/payments/webhook",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String eventBody(String event, PaymentResponse payment,
                             String payId, long amountPaise) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"" + payId + "\",\"order_id\":\"" + payment.gatewayOrderId()
                + "\",\"amount\":" + amountPaise + "}}}}";
    }

    private String capturedBody(PaymentResponse payment, long amountPaise) {
        // Gateway payment ids are globally unique in production; mirror that
        // or the UNIQUE(razorpay_payment_id) schema guard fires across tests.
        return eventBody("payment.captured", payment,
                "pay_ext_" + payment.paymentId(), amountPaise);
    }

    private String failedBody(PaymentResponse payment) {
        long paise = payment.amount().movePointRight(2).longValueExact();
        return eventBody("payment.failed", payment,
                "pay_ext_fail_" + payment.paymentId(), paise);
    }

    private String paymentStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId);
    }

    private String orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?",
                String.class, orderId);
    }
    @Test
    void capture_confirmsOrder_andConvertsReservedStockToSold() {
        // Explicit steps so inventory deltas can be measured against a
        // baseline - other tests in this class legitimately hold reservations.
        String token = newUser("capture");
        fixture(SKU, 100_000L, 10);
        Inv baseline = invOf(SKU);
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(new AddItemRequest(pid(SKU), 2), bearer(token)), Void.class);
        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), OrderResponse.class).getBody();
        ResponseEntity<PaymentResponse> initiated = initiate(token, order.orderId());
        assertThat(initiated.getStatusCode().value()).isEqualTo(201);
        PaymentResponse payment = initiated.getBody();
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(baseline.available() - 2, baseline.reserved() + 2));

        String body = capturedBody(payment, 200_000L);
        ResponseEntity<String> response = postWebhook(body, signer.sign(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("processed");
        assertThat(paymentStatus(payment.paymentId())).isEqualTo("SUCCESS");
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        // Reserved units are consumed: gone from BOTH counters.
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(baseline.available() - 2, baseline.reserved()));
        String gwPaymentId = jdbc.queryForObject(
                "SELECT razorpay_payment_id FROM payments WHERE id = ?",
                String.class, payment.paymentId());
        assertThat(gwPaymentId).isEqualTo("pay_ext_" + payment.paymentId());
        // Every verified delivery lands in the append-only audit ledger.
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_events WHERE payment_id = ? "
                + "AND type = 'payment.captured' AND signature_verified",
                Integer.class, payment.paymentId());
        assertThat(ledgerRows).isEqualTo(1);

        // Simulated path (mock controller) drives the same pipeline.
        Attempt second = readyToPay("capture2");
        ResponseEntity<String> sim = simulate(second.token(),
                second.payment().paymentId(), "simulate-capture");
        assertThat(sim.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(second.payment().paymentId())).isEqualTo("SUCCESS");
    }
    @Test
    void failure_releasesStock_andOrderStaysPayable() {
        Attempt attempt = readyToPay("fail");
        Inv before = invOf(SKU);

        String body = failedBody(attempt.payment());
        ResponseEntity<String> response = postWebhook(body, signer.sign(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("FAILED");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("PENDING_PAYMENT");
        // Release moves OUR 2 units from reserved back to available; other
        // tests share this product row, so assert the delta, not absolutes.
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(before.available() + 2, before.reserved() - 2));

        // A FAILED attempt does not block a fresh attempt on the same order.
        ResponseEntity<PaymentResponse> retry =
                initiate(attempt.token(), attempt.order().orderId());
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody().gatewayOrderId())
                .isNotEqualTo(attempt.payment().gatewayOrderId());
    }

    @Test
    void badSignature_isRejectedAndTouchesNothing() {
        Attempt attempt = readyToPay("badsig");
        String body = capturedBody(attempt.payment(), 200_000L);

        ResponseEntity<String> response = postWebhook(body, "deadbeef".repeat(8));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("WEBHOOK_INVALID_SIGNATURE");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("CREATED");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("PENDING_PAYMENT");
    }
    @Test
    void tamperedAmount_isAckedButIgnored() {
        Attempt attempt = readyToPay("tamper");
        String body = capturedBody(attempt.payment(), 1_000L); // not what we charged

        ResponseEntity<String> response = postWebhook(body, signer.sign(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("ignored");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("CREATED");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void replayedCapture_isIdempotent() {
        Attempt attempt = readyToPay("replay");
        String body = capturedBody(attempt.payment(), 200_000L);
        String sig = signer.sign(body);

        assertThat(postWebhook(body, sig).getStatusCode().value()).isEqualTo(200);
        Inv settled = invOf(SKU);

        ResponseEntity<String> replay = postWebhook(body, sig);

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("SUCCESS");
        // No double settlement from the duplicate delivery.
        assertThat(invOf(SKU)).isEqualTo(settled);
    }
    @Test
    void unknownGatewayOrder_isAckedWithoutAction() {
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":"
                + "{\"entity\":{\"id\":\"pay_x\",\"order_id\":\"order_mock_unknown\","
                + "\"amount\":999}}}}";

        ResponseEntity<String> response = postWebhook(body, signer.sign(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("ignored");
    }

    @Test
    void initiation_isIdempotent_ownerChecked_andStateGuarded() {
        Attempt attempt = readyToPay("initiate");

        // Same order again -> same in-flight attempt, not a second row.
        ResponseEntity<PaymentResponse> again =
                initiate(attempt.token(), attempt.order().orderId());
        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody().paymentId()).isEqualTo(attempt.payment().paymentId());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id = ?",
                Integer.class, attempt.order().orderId());
        assertThat(rows).isEqualTo(1);

        // Someone else's order is invisible.
        String intruder = newUser("intruder-pay");
        ResponseEntity<String> foreign =
                http.exchange("/api/v1/payments", HttpMethod.POST,
                        new HttpEntity<>(
                                new InitiatePaymentRequest(attempt.order().orderId()),
                                bearer(intruder)),
                        String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);

        // After capture: no new attempts against a confirmed order.
        String body = capturedBody(attempt.payment(), 200_000L);
        postWebhook(body, signer.sign(body));
        ResponseEntity<String> rawPostCapture = http.exchange("/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(attempt.order().orderId()),
                        bearer(attempt.token())),
                String.class);
        assertThat(rawPostCapture.getStatusCode().value()).isEqualTo(409);
        assertThat(rawPostCapture.getBody()).contains("INVALID_ORDER_STATE");

        // ...and simulate endpoints are owner-scoped too.
        ResponseEntity<String> stolen = simulate(intruder,
                attempt.payment().paymentId(), "simulate-capture");
        assertThat(stolen.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void duplicateEventId_isDeliveredExactlyOnce() {
        Attempt attempt = readyToPay("dup-event");
        String body = capturedBody(attempt.payment(), 200_000L);
        String sig = signer.sign(body);
        String eventId = "evt_dup_" + attempt.payment().paymentId();

        ResponseEntity<String> first = postWebhook(body, sig, eventId);
        Inv settled = invOf(SKU);
        ResponseEntity<String> replay = postWebhook(body, sig, eventId);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).contains("processed");
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).contains("ignored");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("SUCCESS");
        assertThat(invOf(SKU)).isEqualTo(settled);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_events WHERE event_id = ?",
                Integer.class, eventId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void failedReplay_releasesStockOnlyOnce() {
        Attempt attempt = readyToPay("fail-replay");
        String body = failedBody(attempt.payment());
        String sig = signer.sign(body);

        postWebhook(body, sig);
        Inv afterFirst = invOf(SKU);

        // Duplicate failure delivery (no event id): FSM no-op, no double release.
        ResponseEntity<String> replay = postWebhook(body, sig);

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("FAILED");
        assertThat(invOf(SKU)).isEqualTo(afterFirst);
    }

    @Test
    void authorizedEvent_blocksCancel_thenCaptureStillCompletes() {
        Attempt attempt = readyToPay("auth-cancel");
        long paise = attempt.payment().amount().movePointRight(2).longValueExact();
        String authBody = eventBody("payment.authorized", attempt.payment(),
                "pay_ext_auth_" + attempt.payment().paymentId(), paise);
        ResponseEntity<String> auth = postWebhook(authBody, signer.sign(authBody));

        assertThat(auth.getStatusCode().value()).isEqualTo(200);
        assertThat(auth.getBody()).contains("processed");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("AUTHORIZED");

        // Money is held at the bank - cancelling now would strand it.
        ResponseEntity<String> cancel = http.exchange(
                "/api/v1/orders/" + attempt.order().orderId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())), String.class);
        assertThat(cancel.getStatusCode().value()).isEqualTo(409);
        assertThat(cancel.getBody()).contains("PAYMENT_IN_FLIGHT");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("PENDING_PAYMENT");

        // The gateway completes the capture regardless of customer intent.
        String capBody = capturedBody(attempt.payment(), paise);
        ResponseEntity<String> cap = postWebhook(capBody, signer.sign(capBody));
        assertThat(cap.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("SUCCESS");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    void staleAttempt_expires_releasesStock_andRetryRecaptures() {
        Attempt attempt = readyToPay("expire");
        Inv beforeExpiry = invOf(SKU);
        jdbc.update("UPDATE payments SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                attempt.payment().paymentId());

        reconciliation.expireStalePayments();
        Inv afterRelease = invOf(SKU);

        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("EXPIRED");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(afterRelease).isEqualTo(
                new Inv(beforeExpiry.available() + 2, beforeExpiry.reserved() - 2));
        // ...even if the sweeper runs again (conditional update loses the race).
        reconciliation.expireStalePayments();
        assertThat(invOf(SKU)).isEqualTo(afterRelease);

        // Retry after expiry: fresh attempt AND re-reserved stock.
        ResponseEntity<PaymentResponse> retry =
                initiate(attempt.token(), attempt.order().orderId());
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody().gatewayOrderId())
                .isNotEqualTo(attempt.payment().gatewayOrderId());
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(afterRelease.available() - 2, afterRelease.reserved() + 2));

        ResponseEntity<String> sim = simulate(attempt.token(),
                retry.getBody().paymentId(), "simulate-capture");
        assertThat(sim.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(retry.getBody().paymentId())).isEqualTo("SUCCESS");
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("CONFIRMED");
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(afterRelease.available() - 2, afterRelease.reserved()));
    }

    @Test
    void cancel_unpaidOrder_cancelsAttempt_andReleasesStock() {
        Attempt attempt = readyToPay("cancel");
        Inv before = invOf(SKU);

        ResponseEntity<OrderResponse> cancelled = http.exchange(
                "/api/v1/orders/" + attempt.order().orderId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())),
                OrderResponse.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().status()).isEqualTo("CANCELLED");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("CANCELLED");
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(before.available() + 2, before.reserved() - 2));

        // A cancelled order is dead: no new payments, no second cancel.
        ResponseEntity<String> reinitiate = http.exchange("/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(attempt.order().orderId()),
                        bearer(attempt.token())), String.class);
        assertThat(reinitiate.getStatusCode().value()).isEqualTo(409);
        assertThat(reinitiate.getBody()).contains("INVALID_ORDER_STATE");
        ResponseEntity<String> again = http.exchange(
                "/api/v1/orders/" + attempt.order().orderId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())), String.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("INVALID_ORDER_STATE");
    }

    @Test
    void cancel_isOwnerScoped_andWorksWithoutAnyAttempt() {
        // No payment ever initiated - cancel still frees the reservation.
        String token = newUser("cancel-bare");
        fixture(SKU, 100_000L, 10);
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(new AddItemRequest(pid(SKU), 2), bearer(token)), Void.class);
        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), OrderResponse.class).getBody();
        Inv before = invOf(SKU);

        ResponseEntity<OrderResponse> cancelled = http.exchange(
                "/api/v1/orders/" + order.orderId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)), OrderResponse.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().status()).isEqualTo("CANCELLED");
        assertThat(invOf(SKU)).isEqualTo(
                new Inv(before.available() + 2, before.reserved() - 2));

        // Someone else's order is invisible to cancel, as everywhere else.
        String intruder = newUser("intruder-cancel");
        ResponseEntity<String> foreign = http.exchange(
                "/api/v1/orders/" + order.orderId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, bearer(intruder)), String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void refund_returnsMoney_flipsStatus_andIsOwnerScoped() {
        Attempt attempt = readyToPay("refund");
        simulate(attempt.token(), attempt.payment().paymentId(), "simulate-capture");
        assertThat(paymentStatus(attempt.payment().paymentId())).isEqualTo("SUCCESS");

        // Someone else's payment is invisible - 404 before any money moves.
        String intruder = newUser("intruder-refund");
        ResponseEntity<String> foreign = http.exchange(
                "/api/v1/payments/" + attempt.payment().paymentId() + "/refund",
                HttpMethod.POST, new HttpEntity<>(null, bearer(intruder)), String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<PaymentResponse> refunded = http.exchange(
                "/api/v1/payments/" + attempt.payment().paymentId() + "/refund",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())),
                PaymentResponse.class);

        assertThat(refunded.getStatusCode().value()).isEqualTo(200);
        assertThat(refunded.getBody().status()).isEqualTo("REFUNDED");
        assertThat(refunded.getBody().gatewayPaymentId())
                .startsWith("pay_sim_");
        String refundId = jdbc.queryForObject(
                "SELECT refund_id FROM payments WHERE id = ?",
                String.class, attempt.payment().paymentId());
        assertThat(refundId).startsWith("rfnd_mock_");
        // Order stays CONFIRMED: a refund returns money, it does not un-sell.
        assertThat(orderStatus(attempt.order().orderId())).isEqualTo("CONFIRMED");

        // REFUNDED is terminal - a second refund is refused, money moved once.
        ResponseEntity<String> again = http.exchange(
                "/api/v1/payments/" + attempt.payment().paymentId() + "/refund",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())), String.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("NOT_REFUNDABLE");
    }

    @Test
    void refund_rejectsPaymentsThatNeverCaptured() {
        Attempt attempt = readyToPay("refund-pending");

        ResponseEntity<String> response = http.exchange(
                "/api/v1/payments/" + attempt.payment().paymentId() + "/refund",
                HttpMethod.POST, new HttpEntity<>(null, bearer(attempt.token())), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("NOT_REFUNDABLE");
        assertThat(paymentStatus(attempt.payment().paymentId()))
                .isEqualTo("CREATED");
    }
}
