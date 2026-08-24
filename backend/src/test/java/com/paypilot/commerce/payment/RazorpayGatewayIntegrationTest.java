package com.paypilot.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.order.api.dto.OrderResponse;
import com.paypilot.commerce.payment.api.dto.InitiatePaymentRequest;
import com.paypilot.commerce.payment.api.dto.PaymentResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live Razorpay REST adapter end to end - against a stub api.razorpay.com,
 * so the wire contract (Basic auth over key-id:key-secret, exact request
 * bodies, echoed-amount verification, outage mapping) is exercised without
 * any vendor account or network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RazorpayGatewayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String KEY_ID = "key_test";
    private static final String KEY_SECRET = "secret_test";

    // ----- stub Razorpay API ---------------------------------------------

    private static final BlockingQueue<String> ORDER_BODIES = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> REFUND_BODIES = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> AUTH_HEADERS = new LinkedBlockingQueue<>();
    private static final List<String> REFUND_PATHS = new CopyOnWriteArrayList<>();
    private static volatile boolean OUTAGE = false;
    private static volatile boolean TAMPER_AMOUNT = false;
    private static volatile long orderCounter = 0;
    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/orders", exchange -> {
                try (exchange) {
                    recordAuth(exchange);
                    String request = new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    ORDER_BODIES.add(request);
                    JsonNode req = JSON_MAPPER.readTree(request);
                    long amount = req.path("amount").asLong();
                    if (TAMPER_AMOUNT) {
                        amount = amount + 1;
                    }
                    if (OUTAGE) {
                        send(exchange, 500,
                                "{\"error\":{\"description\":\"internal\"}}");
                        return;
                    }
                    String body = JSON_MAPPER.writeValueAsString(Map.of(
                            "id", "order_live_" + ++orderCounter,
                            "amount", amount,
                            "currency", req.path("currency").asText(),
                            "status", "created"));
                    send(exchange, 200, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            server.createContext("/v1/payments", exchange -> {
                try (exchange) {
                    recordAuth(exchange);
                    String request = new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    REFUND_PATHS.add(exchange.getRequestURI().getPath());
                    REFUND_BODIES.add(request);
                    JsonNode req = JSON_MAPPER.readTree(request);
                    if (OUTAGE) {
                        send(exchange, 500,
                                "{\"error\":{\"description\":\"internal\"}}");
                        return;
                    }
                    String body = JSON_MAPPER.writeValueAsString(Map.of(
                            "id", "rfnd_live_" + System.nanoTime(),
                            "amount", req.path("amount").asLong(),
                            "status", "processed"));
                    send(exchange, 200, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Stub Razorpay server failed", e);
        }
    }

    private static void recordAuth(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            AUTH_HEADERS.add(auth);
        }
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("paypilot.security.rate-limit.auth-capacity-per-minute",
                () -> "100");
        registry.add("paypilot.payments.gateway", () -> "razorpay");
        registry.add("paypilot.payments.razorpay.base-url",
                () -> "http://localhost:" + STUB.getAddress().getPort() + "/v1");
        registry.add("paypilot.payments.razorpay.key-id", () -> KEY_ID);
        registry.add("paypilot.payments.razorpay.key-secret", () -> KEY_SECRET);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetStub() {
        ORDER_BODIES.clear();
        REFUND_BODIES.clear();
        AUTH_HEADERS.clear();
        REFUND_PATHS.clear();
        OUTAGE = false;
        TAMPER_AMOUNT = false;
    }

    // ----- commerce flow helpers ------------------------------------------

    private static final String SKU = "SHOE-NK-DOWN12";

    @Autowired
    TestRestTemplate http;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    PaymentService paymentService;

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

    /** register -> add 2x SKU at Rs 1000 -> checkout; payment left to caller */
    private Long checkout(String token) {
        tx.executeWithoutResult(s -> {
            Long pid = productRepository.findBySku(SKU).orElseThrow().getId();
            productRepository.setPricePaise(pid, 100_000L);
            inventoryRepository.setAvailable(pid, 10);
        });
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(new AddItemRequest(
                        productRepository.findBySku(SKU).orElseThrow().getId(), 2),
                        bearer(token)), Void.class);
        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), OrderResponse.class).getBody();
        return order.orderId();
    }

    private ResponseEntity<PaymentResponse> initiate(String token, Long orderId) {
        return http.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(orderId),
                        bearer(token)), PaymentResponse.class);
    }

    // ----- tests ------------------------------------------------------------

    @Test
    void createOrder_speaksTheLiveRazorpayContract() throws Exception {
        String token = newUser("rzp-create");
        Long orderId = checkout(token);
        ResponseEntity<PaymentResponse> initiated = initiate(token, orderId);
        assertThat(initiated.getStatusCode().value()).isEqualTo(201);
        PaymentResponse payment = initiated.getBody();

        // The stub saw exactly what production must send: Basic auth over
        // key-id:key-secret and an order body bound to OUR internal order id.
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(AUTH_HEADERS.poll()).isEqualTo(expectedAuth);

        JsonNode orderBody = JSON_MAPPER.readTree(ORDER_BODIES.poll());
        assertThat(orderBody.get("amount").asLong()).isEqualTo(200_000L);
        assertThat(orderBody.get("currency").asText()).isEqualTo("INR");
        assertThat(orderBody.get("receipt").asText())
                .isEqualTo("order-" + orderId);

        // The gateway's own order id is what we persist and hand back -
        // checkout.js binds to it, webhooks correlate on it.
        assertThat(payment.gatewayOrderId()).startsWith("order_live_");
        String stored = jdbc.queryForObject(
                "SELECT razorpay_order_id FROM payments WHERE id = ?",
                String.class, payment.paymentId());
        assertThat(stored).isEqualTo(payment.gatewayOrderId());
        assertThat(payment.status()).isEqualTo("CREATED");
    }

    @Test
    void tamperedOrderResponse_isRejectedBeforeAnythingIsTrusted() {
        TAMPER_AMOUNT = true;
        String token = newUser("rzp-tamper");
        Long orderId = checkout(token);

        ResponseEntity<String> response = http.exchange("/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(orderId),
                        bearer(token)), String.class);

        // A gateway echoing a different amount than requested is either
        // broken or hostile; initiation dies loudly instead of storing it.
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id = ?",
                Integer.class, orderId);
        assertThat(rows).isZero();
    }

    @Test
    void gatewayOutage_surfacesAsConflict_notAsARawCrash() {
        OUTAGE = true;
        String token = newUser("rzp-outage");
        Long orderId = checkout(token);

        ResponseEntity<String> response = http.exchange("/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(new InitiatePaymentRequest(orderId),
                        bearer(token)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("GATEWAY_UNAVAILABLE");
        // No half-created attempt lingers against the payable order.
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id = ?",
                Integer.class, orderId);
        assertThat(rows).isZero();
    }

    @Test
    void refund_roundTripsThroughTheLiveApi() throws Exception {
        String token = newUser("rzp-refund");
        Long orderId = checkout(token);
        PaymentResponse payment = initiate(token, orderId).getBody();
        // Mock-controller simulate endpoints are absent in this razorpay
        // context; drive the same signed-webhook pipeline via the service.
        Long ownerId = jdbc.queryForObject(
                "SELECT user_id FROM payments WHERE id = ?",
                Long.class, payment.paymentId());
        paymentService.simulateCapture(ownerId, payment.paymentId());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class,
                payment.paymentId())).isEqualTo("SUCCESS");

        ResponseEntity<PaymentResponse> refunded = http.exchange(
                "/api/v1/payments/" + payment.paymentId() + "/refund",
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)),
                PaymentResponse.class);

        assertThat(refunded.getStatusCode().value()).isEqualTo(200);
        assertThat(refunded.getBody().status()).isEqualTo("REFUNDED");

        // The refund call carried the same credentials and the FULL amount.
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(AUTH_HEADERS.poll()).isEqualTo(expectedAuth);
        assertThat(REFUND_PATHS.get(0)).matches(".*/v1/payments/.+/refund");
        JsonNode refundBody = JSON_MAPPER.readTree(REFUND_BODIES.poll());
        assertThat(refundBody.get("amount").asLong()).isEqualTo(200_000L);
        // The gateway's refund receipt is persisted for reconciliation.
        assertThat(jdbc.queryForObject(
                "SELECT refund_id FROM payments WHERE id = ?",
                String.class, payment.paymentId())).startsWith("rfnd_live_");
    }
}
