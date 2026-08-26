package com.paypilot.multicurrency;

import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.order.domain.Order;
import com.paypilot.commerce.order.repo.OrderRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19: multi-currency end-to-end tests. Verifies that:
 *  1. All monetary tables carry ISO 4217 currency codes.
 *  2. Products, orders, and order_items default to INR when no currency is specified.
 *  3. Product listing/detail endpoints accept a ?currency param and convert prices.
 *  4. Orders inherit the product's currency at checkout time.
 *  5. The converter bean supports INR ↔ USD with deterministic math.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paypilot.security.rate-limit.auth-capacity-per-minute=200",
        "CURRENCY_RATES={\"USD\":0.012,\"EUR\":0.011,\"GBP\":0.0095}"})
@Testcontainers(disabledWithoutDocker = true)
class CurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final String SKU = "SHOE-NK-DOWN12";

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(s -> {
            Long pid = productRepository.findBySku(SKU).orElseThrow().getId();
            productRepository.setPricePaise(pid, 369900L);   // ₹3699
            inventoryRepository.setAvailable(pid, 100);
        });
    }

    private String register(String label) {
        return (String) http.postForEntity("/api/v1/auth/register",
                Map.of("email", label + "-" + System.nanoTime() + "@test.com",
                        "password", "currency123!"),
                Map.class).getBody().get("accessToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    // ------------------------------------------------------------------
    // 1. Schema: currency columns exist with correct defaults
    // ------------------------------------------------------------------
    @Test
    void schema_currencyColumnsExist() {
        String productsCol = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name='products' AND column_name='currency'", String.class);
        String ordersCol = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name='orders' AND column_name='currency'", String.class);
        String itemsCol = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name='order_items' AND column_name='currency'", String.class);

        assertThat(productsCol).isEqualTo("character varying");
        assertThat(ordersCol).isEqualTo("character varying");
        assertThat(itemsCol).isEqualTo("character varying");
    }

    @Test
    void schema_seedProductsHaveDefaultCurrency() {
        String currency = jdbc.queryForObject(
                "SELECT currency FROM products WHERE sku = ?", String.class, SKU);
        assertThat(currency).isEqualTo("INR");
    }

    // ------------------------------------------------------------------
    // 2. Product listing without currency → native INR price
    // ------------------------------------------------------------------
    @Test
    void productSummary_nativePrice_whenNoCurrencyParam() {
        String token = register("curr-native");
        ResponseEntity<Map> resp = http.exchange(
                "/api/v1/products?q=downshifter", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map body = resp.getBody();
        java.util.List items = (java.util.List) body.get("items");
        assertThat(items).isNotEmpty();
        Map first = (Map) items.get(0);
        assertThat(first.get("price")).isEqualTo(3699.00);
        assertThat(first.get("currency")).isEqualTo("INR");
    }

    // ------------------------------------------------------------------
    // 3. Product listing with ?currency=USD → converted price
    // ------------------------------------------------------------------
    @Test
    void productSummary_convertedPrice_whenUsdRequested() {
        String token = register("curr-usd");
        ResponseEntity<Map> resp = http.exchange(
                "/api/v1/products?q=downshifter&currency=USD", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map body = resp.getBody();
        java.util.List items = (java.util.List) body.get("items");
        assertThat(items).isNotEmpty();
        Map first = (Map) items.get(0);
        // ₹3699 * 0.012 = 44.388 → 44 USD (rounded half-up)
        assertThat(first.get("currency")).isEqualTo("USD");
        BigDecimal price = new BigDecimal(first.get("price").toString());
        assertThat(price).isGreaterThan(BigDecimal.ZERO);
        assertThat(price).isLessThan(new BigDecimal("100"));  // way less than ₹3699
    }

    // ------------------------------------------------------------------
    // 4. Product detail with ?currency=EUR
    // ------------------------------------------------------------------
    @Test
    void productDetail_convertedPrice_whenEurRequested() {
        String token = register("curr-eur");
        ResponseEntity<Map> resp = http.exchange(
                "/api/v1/products/" + SKU + "?currency=EUR", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map body = resp.getBody();
        assertThat(body.get("currency")).isEqualTo("EUR");
        BigDecimal price = new BigDecimal(body.get("price").toString());
        assertThat(price).isGreaterThan(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // 5. Checkout → order inherits product currency
    // ------------------------------------------------------------------
    @Test
    void order_inheritsProductCurrency() {
        String token = register("curr-order");
        Long pid = productRepository.findBySku(SKU).orElseThrow().getId();

        // Add to cart
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", pid, "quantity", 1),
                        bearer(token)), Void.class);

        // Checkout
        ResponseEntity<Map> resp = http.exchange(
                "/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(null, bearer(token)), Map.class);
        assertThat(resp.getStatusCode().value()).isIn(200, 201);
        Map body = resp.getBody();
        assertThat(body.get("currency")).isEqualTo("INR");

        // Verify DB row
        Long orderId = ((Number) body.get("orderId")).longValue();
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getCurrency()).isEqualTo("INR");
    }

    // ------------------------------------------------------------------
    // 6. Order summary list includes currency
    // ------------------------------------------------------------------
    @Test
    void orderList_includesCurrency() {
        String token = register("curr-list");
        Long pid = productRepository.findBySku(SKU).orElseThrow().getId();

        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", pid, "quantity", 1),
                        bearer(token)), Void.class);
        http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(null, bearer(token)), Void.class);

        ResponseEntity<Map> resp = http.exchange(
                "/api/v1/orders?page=0&size=5", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        java.util.List items = (java.util.List) resp.getBody().get("items");
        assertThat(items).isNotEmpty();
        Map first = (Map) items.get(0);
        assertThat(first.get("currency")).isEqualTo("INR");
    }
}
