package com.paypilot.commerce.order;

import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.ApplyOfferRequest;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.offer.domain.Offer;
import com.paypilot.commerce.offer.domain.OfferType;
import com.paypilot.commerce.offer.repo.OfferRedemptionRepository;
import com.paypilot.commerce.offer.repo.OfferRepository;
import com.paypilot.commerce.order.api.dto.OrderResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkout end-to-end: totals, stock reservation with full rollback on
 * shortage, authoritative offer re-validation, redemption writes, cart
 * lifecycle, ownership.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "paypilot.security.rate-limit.auth-capacity-per-minute=100")
class OrderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    OfferRepository offerRepository;
    @Autowired
    OfferRedemptionRepository redemptionRepository;
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

    private Long productIdBySku(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getId();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private CartResponse post(String url, Object body, String token) {
        return http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), CartResponse.class).getBody();
    }

    /** Pins a SKU to an exact price and stock so money math is deterministic. */
    private void fixture(String sku, long unitPaise, int available) {
        tx.executeWithoutResult(s -> {
            productRepository.setPricePaise(productIdBySku(sku), unitPaise);
            inventoryRepository.setAvailable(productIdBySku(sku), available);
        });
    }

    private record Inv(int available, int reserved) {
    }

    private Inv invOf(String sku) {
        return jdbc.queryForObject(
                "SELECT available, reserved FROM inventory WHERE product_id = ?",
                (rs, n) -> new Inv(rs.getInt(1), rs.getInt(2)),
                productIdBySku(sku));
    }

    @Test
    void checkout_createsOrder_reservesStock_ordersCart() {
        String token = newUser("happy");
        fixture("SHOE-NK-DOWN12", 100_000L, 10);
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-NK-DOWN12"), 3), token);

        ResponseEntity<OrderResponse> response = http.exchange("/api/v1/orders",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), OrderResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        OrderResponse order = response.getBody();
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.subtotal()).isEqualByComparingTo("3000.00");
        assertThat(order.discount()).isEqualByComparingTo("0.00");
        assertThat(order.total()).isEqualByComparingTo("3000.00");
        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).unitPrice()).isEqualByComparingTo("1000.00");

        // Stock moved from available into reserved - nothing sold yet.
        Inv inv = invOf("SHOE-NK-DOWN12");
        assertThat(inv.available()).isEqualTo(7);
        assertThat(inv.reserved()).isEqualTo(3);

        // Cart is terminal; the next cart view materializes a NEW cart.
        CartResponse nextCart = http.exchange("/api/v1/cart", HttpMethod.GET,
                new HttpEntity<CartResponse>(null, bearer(token)), CartResponse.class)
                .getBody();
        assertThat(nextCart.items()).isEmpty();

        // Snapshot frozen with quantities and unit prices. Hibernate emits
        // pretty-printed JSON, so compare whitespace-insensitively.
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT cart_snapshot FROM orders WHERE id = ?", order.orderId());
        String flat = snapshot.get("cart_snapshot").toString().replaceAll("\\s", "");
        assertThat(flat)
                .contains("\"quantity\":3")
                .contains("\"unitPricePaise\":100000")
                .doesNotContain("offerCode");
    }

    @Test
    void shortage_rollsBackEntireCheckout() {
        String token = newUser("shortage");
        // Add-time guards allow both lines (stock is sufficient NOW).
        fixture("SHOE-NK-DOWN12", 100_000L, 10);
        fixture("SHOE-AD-GALAXY6", 200_000L, 5);
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-NK-DOWN12"), 2), token);
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-AD-GALAXY6"), 2), token);

        // ...then another buyer takes GALAXY6 units between add and checkout.
        tx.executeWithoutResult(s ->
                inventoryRepository.setAvailable(productIdBySku("SHOE-AD-GALAXY6"), 1));

        // Stock state right before the doomed checkout - other tests in this
        // class share the DB, so only DELTAS from here prove rollback.
        Inv downBefore = invOf("SHOE-NK-DOWN12");
        Inv galaxyBefore = invOf("SHOE-AD-GALAXY6");

        ResponseEntity<String> response = http.exchange("/api/v1/orders",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INSUFFICIENT_STOCK");

        // The earlier line's reservation must have been rolled back too...
        assertThat(invOf("SHOE-NK-DOWN12")).isEqualTo(downBefore);
        assertThat(invOf("SHOE-AD-GALAXY6")).isEqualTo(galaxyBefore);
        // ...and no order row may exist for this user.
        Integer orders = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM orders o
                JOIN users u ON u.id = o.user_id WHERE u.email LIKE 'shortage-%'
                """, Integer.class);
        assertThat(orders).isZero();
    }

    @Test
    void offerRevalidatedAtCheckout_expiredAfterApply_blocksOrder() {
        String token = newUser("expiremid");
        fixture("SHOE-NK-DOWN12", 100_000L, 10);
        Offer flashSale = offerRepository.save(new Offer("FLASH5", OfferType.PERCENTAGE,
                500, null, 0, Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS), 1, true));
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-NK-DOWN12"), 1), token);
        post("/api/v1/cart/offers", new ApplyOfferRequest("FLASH5"), token);

        Inv stockBefore = invOf("SHOE-NK-DOWN12");

        // The offer expires AFTER apply but BEFORE checkout.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE offers SET valid_to = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.SECONDS)),
                flashSale.getId()));

        ResponseEntity<String> response = http.exchange("/api/v1/orders",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("OFFER_EXPIRED");

        // No reservation leaked despite the failure path.
        assertThat(invOf("SHOE-NK-DOWN12")).isEqualTo(stockBefore);
    }

    @Test
    void discount_appliedAndRedeemed() {
        String token = newUser("discount");
        fixture("SHOE-NK-DOWN12", 100_000L, 10); // subtotal Rs 2000 -> WELCOME10 min met
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-NK-DOWN12"), 2), token);
        post("/api/v1/cart/offers", new ApplyOfferRequest("WELCOME10"), token);

        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), OrderResponse.class).getBody();

        assertThat(order.discount()).isEqualByComparingTo("200.00");
        assertThat(order.total()).isEqualByComparingTo("1800.00");
        assertThat(redemptionRepository.countByOfferIdAndUserId(
                offerRepository.findByCodeIgnoreCase("WELCOME10").orElseThrow().getId(),
                jdbc.queryForObject(
                        "SELECT user_id FROM orders WHERE id = ?", Long.class, order.orderId())))
                .isEqualTo(1);
    }

    @Test
    void emptyCart_checkout_isRejected() {
        String token = newUser("emptyorder");
        http.exchange("/api/v1/cart", HttpMethod.GET,
                new HttpEntity<CartResponse>(null, bearer(token)), CartResponse.class);

        ResponseEntity<String> response = http.exchange("/api/v1/orders",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("EMPTY_CART");
    }

    @Test
    void orderDetails_areOwnerOnly() {
        String ownerToken = newUser("owner");
        String intruderToken = newUser("intruder");
        fixture("SHOE-NK-DOWN12", 100_000L, 5);
        post("/api/v1/cart/items", new AddItemRequest(productIdBySku("SHOE-NK-DOWN12"), 1), ownerToken);
        OrderResponse order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(bearer(ownerToken)), OrderResponse.class).getBody();

        ResponseEntity<String> asOwner = http.exchange(
                "/api/v1/orders/" + order.orderId(), HttpMethod.GET,
                new HttpEntity<>(bearer(ownerToken)), String.class);
        ResponseEntity<String> asIntruder = http.exchange(
                "/api/v1/orders/" + order.orderId(), HttpMethod.GET,
                new HttpEntity<>(bearer(intruderToken)), String.class);

        assertThat(asOwner.getStatusCode().value()).isEqualTo(200);
        // 404, not 403 - never leak that someone else's order exists.
        assertThat(asIntruder.getStatusCode().value()).isEqualTo(404);
    }
}
