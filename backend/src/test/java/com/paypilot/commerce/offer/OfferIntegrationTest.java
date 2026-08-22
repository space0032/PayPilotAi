package com.paypilot.commerce.offer;

import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.ApplyOfferRequest;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.offer.domain.Offer;
import com.paypilot.commerce.offer.domain.OfferType;
import com.paypilot.commerce.offer.repo.OfferRepository;
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
 * Offer application end-to-end: seeded offers (WELCOME10, FLAT500, MEGA20),
 * discount math against live subtotals, validity/minimum/usage guards,
 * removal, and per-user isolation of usage counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "paypilot.security.rate-limit.auth-capacity-per-minute=100")
class OfferIntegrationTest {

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

    /** Adds qty units of a SKU to the user's cart (stock permitting). */
    private CartResponse addToCart(String token, String sku, int qty) {
        return post("/api/v1/cart/items",
                new AddItemRequest(productIdBySku(sku), qty), token);
    }

    private CartResponse post(String url, Object body, String token) {
        return http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), CartResponse.class).getBody();
    }

    private CartResponse delete(String url, String token) {
        return http.exchange(url, HttpMethod.DELETE,
                new HttpEntity<CartResponse>(null, bearer(token)), CartResponse.class)
                .getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** Pins a SKU to an exact price and ample stock so totals are deterministic. */
    private void fixture(String sku, long unitPaise) {
        tx.executeWithoutResult(s -> {
            productRepository.setPricePaise(productIdBySku(sku), unitPaise);
            inventoryRepository.setAvailable(productIdBySku(sku), 50);
        });
    }

    @Test
    void percentageOffer_discountsLiveSubtotal() {
        String token = newUser("pct");
        // WELCOME10: 10% capped at Rs 500, min cart Rs 1000. Force unit to Rs 1000.
        fixture("SHOE-NK-DOWN12", 100_000L);
        addToCart(token, "SHOE-NK-DOWN12", 2);

        CartResponse cart = post("/api/v1/cart/offers",
                new ApplyOfferRequest("welcome10"), token);

        assertThat(cart.appliedOfferCode()).isEqualToIgnoringCase("WELCOME10");
        assertThat(cart.subtotal()).isEqualByComparingTo("2000.00");
        assertThat(cart.discount()).isEqualByComparingTo("200.00");
        assertThat(cart.total()).isEqualByComparingTo("1800.00");
    }

    @Test
    void flatOffer_appliesFixedDiscount() {
        String token = newUser("flat");
        // FLAT500: flat Rs 500 off, min cart Rs 4000. Unit price Rs 2500 x2.
        fixture("SHOE-NK-DOWN12", 250_000L);
        addToCart(token, "SHOE-NK-DOWN12", 2);

        CartResponse cart = post("/api/v1/cart/offers",
                new ApplyOfferRequest("FLAT500"), token);

        assertThat(cart.discount()).isEqualByComparingTo("500.00");
        assertThat(cart.total()).isEqualByComparingTo("4500.00");
    }

    @Test
    void minCartNotMet_isRejected() {
        String token = newUser("mincart");
        // Subtotal Rs 3000 < FLAT500's Rs 4000 minimum.
        fixture("SHOE-NK-DOWN12", 150_000L);
        addToCart(token, "SHOE-NK-DOWN12", 2);

        ResponseEntity<String> response = http.exchange("/api/v1/cart/offers",
                HttpMethod.POST,
                new HttpEntity<>(new ApplyOfferRequest("FLAT500"), bearer(token)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("MIN_CART_NOT_MET");
    }

    @Test
    void expiredOffer_isRejected() {
        String token = newUser("expired");
        offerRepository.save(new Offer("DEAD10", OfferType.PERCENTAGE, 1000, null,
                0, Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS), 5, true));
        addToCart(token, "SHOE-NK-DOWN12", 1);

        ResponseEntity<String> response = http.exchange("/api/v1/cart/offers",
                HttpMethod.POST,
                new HttpEntity<>(new ApplyOfferRequest("DEAD10"), bearer(token)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("OFFER_EXPIRED");
    }

    @Test
    void usageLimitReached_isRejected() {
        String token = newUser("usedup");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        addToCart(token, "SHOE-NK-DOWN12", 1);

        // Simulate a fully-redeemed history for this user on FOOTWEAR15
        // (limit 2): two past redemptions need an order row each.
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM carts WHERE status='ACTIVE' ORDER BY id DESC LIMIT 1",
                Long.class);
        Long offerId = offerRepository.findByCodeIgnoreCase("FOOTWEAR15")
                .orElseThrow().getId();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 2; i++) {
                Long orderId = jdbc.queryForObject(
                        """
                        INSERT INTO orders (user_id, status, subtotal_paise, discount_paise,
                                            total_paise, offer_id, cart_snapshot)
                        VALUES (?, 'CONFIRMED', 100000, 15000, 85000, ?,
                                '[{"productId":%d,"quantity":1}]'::jsonb)
                        RETURNING id
                        """.formatted(productId),
                        Long.class, userId, offerId);
                jdbc.update("""
                        INSERT INTO offer_redemptions (offer_id, user_id, order_id, discount_paise)
                        VALUES (?, ?, ?, 15000)
                        """, offerId, userId, orderId);
            }
        });

        ResponseEntity<String> response = http.exchange("/api/v1/cart/offers",
                HttpMethod.POST,
                new HttpEntity<>(new ApplyOfferRequest("FOOTWEAR15"), bearer(token)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("USAGE_LIMIT_REACHED");
    }

    @Test
    void unknownCode_isNotFound() {
        String token = newUser("unknown-offer");
        addToCart(token, "SHOE-NK-DOWN12", 1);

        ResponseEntity<String> response = http.exchange("/api/v1/cart/offers",
                HttpMethod.POST,
                new HttpEntity<>(new ApplyOfferRequest("NOPE404"), bearer(token)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void emptyCart_apply_isRejected() {
        String token = newUser("emptycart");
        http.getForEntity("/api/v1/cart", String.class); // materialize cart

        ResponseEntity<String> response = http.exchange("/api/v1/cart/offers",
                HttpMethod.POST,
                new HttpEntity<>(new ApplyOfferRequest("WELCOME10"), bearer(token)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("EMPTY_CART");
    }

    @Test
    void removeOffer_restoresFullPrice() {
        String token = newUser("remove");
        fixture("SHOE-NK-DOWN12", 200_000L);
        addToCart(token, "SHOE-NK-DOWN12", 1);
        post("/api/v1/cart/offers", new ApplyOfferRequest("MEGA20"), token);

        CartResponse afterRemoval = delete("/api/v1/cart/offers", token);

        assertThat(afterRemoval.appliedOfferCode()).isNull();
        assertThat(afterRemoval.discount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRemoval.total()).isEqualByComparingTo(afterRemoval.subtotal());
    }
}
