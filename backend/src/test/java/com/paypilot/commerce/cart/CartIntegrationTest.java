package com.paypilot.commerce.cart;

import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.cart.api.dto.UpdateItemRequest;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full cart lifecycle over HTTP against real PostgreSQL: lazy creation,
 * merge-on-add, quantity updates, stock and activity guards, per-user
 * isolation, clearing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
// Many registrations per class would otherwise collide with the production
// rate limiter (10/min/IP); this context lifts the cap for auth calls.
@TestPropertySource(properties = "paypilot.security.rate-limit.auth-capacity-per-minute=100")
class CartIntegrationTest {

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
    TransactionTemplate tx;

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** register + login, returns access token */
    private String newUser(String label) {
        String email = label + "-" + System.nanoTime() + "@example.com";
        var reg = http.postForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "sup3rsafe!"), Map.class);
        return (String) reg.getBody().get("accessToken");
    }

    private ResponseEntity<CartResponse> getCart(String token) {
        // HttpEntity has no (headers, body) ctor - (null, headers) is the
        // correct way to send header-only requests.
        return http.exchange("/api/v1/cart", HttpMethod.GET,
                new HttpEntity<CartResponse>(null, bearer(token)), CartResponse.class);
    }

    private ResponseEntity<CartResponse> post(String url, Object body, String token) {
        return http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), CartResponse.class);
    }

    /** POST variant when the caller cares about the error body, not the DTO. */
    private ResponseEntity<String> postRaw(String url, Object body, String token) {
        return http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), String.class);
    }

    private Long productIdBySku(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getId();
    }

    @Test
    void unauthenticatedView_isRejected() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/cart", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void firstView_createsEmptyCart() {
        String token = newUser("view");
        ResponseEntity<CartResponse> response = getCart(token);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        CartResponse cart = response.getBody();
        assertThat(cart.items()).isEmpty();
        assertThat(cart.subtotal()).isEqualByComparingTo(BigDecimal.ZERO);

        // Second view returns the SAME cart (no duplicate creation).
        assertThat(getCart(token).getBody().cartId())
                .isEqualTo(cart.cartId());
    }

    @Test
    void addItem_snapshotsPrice_andComputesTotals() {
        String token = newUser("add");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        BigDecimal catalogPrice = new BigDecimal(
                String.valueOf(productRepository.findById(productId).orElseThrow()
                        .getPricePaise())).movePointLeft(2);

        CartResponse cart = post("/api/v1/cart/items",
                new AddItemRequest(productId, 2), token).getBody();

        assertThat(cart.items()).hasSize(1);
        var line = cart.items().get(0);
        assertThat(line.productId()).isEqualTo(productId);
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.unitPrice()).isEqualByComparingTo(catalogPrice);
        assertThat(line.addedAtPrice()).isEqualByComparingTo(catalogPrice);
        assertThat(line.priceChanged()).isFalse();
        assertThat(cart.subtotal()).isEqualByComparingTo(catalogPrice.multiply(BigDecimal.valueOf(2)));
    }

    @Test
    void repeatedAdd_mergesQuantities() {
        String token = newUser("merge");
        Long productId = productIdBySku("SHOE-NK-DOWN12");

        post("/api/v1/cart/items", new AddItemRequest(productId, 2), token);
        CartResponse cart = post("/api/v1/cart/items",
                new AddItemRequest(productId, 3), token).getBody();

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(5);
    }

    @Test
    void exceedingTenUnitsPerLine_isRejected() {
        String token = newUser("limit");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        tx.executeWithoutResult(s -> inventoryRepository.setAvailable(productId, 50));

        post("/api/v1/cart/items", new AddItemRequest(productId, 6), token);
        var response = postRaw("/api/v1/cart/items",
                new AddItemRequest(productId, 6), token);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("QUANTITY_LIMIT");
    }

    @Test
    void addingBeyondStock_isConflict() {
        String token = newUser("stock");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        tx.executeWithoutResult(s -> inventoryRepository.setAvailable(productId, 2));

        var response = postRaw("/api/v1/cart/items",
                new AddItemRequest(productId, 3), token);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INSUFFICIENT_STOCK");
    }

    @Test
    void updateQuantity_absoluteSet_andZeroRemoves() {
        String token = newUser("update");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        post("/api/v1/cart/items", new AddItemRequest(productId, 2), token);

        CartResponse updated = http.exchange("/api/v1/cart/items/" + productId,
                HttpMethod.PATCH, new HttpEntity<>(new UpdateItemRequest(7),
                        bearer(token)), CartResponse.class)
                .getBody();
        assertThat(updated.items().get(0).quantity()).isEqualTo(7);

        CartResponse afterRemove = http.exchange("/api/v1/cart/items/" + productId,
                HttpMethod.PATCH, new HttpEntity<>(new UpdateItemRequest(0),
                        bearer(token)), CartResponse.class)
                .getBody();
        assertThat(afterRemove.items()).isEmpty();
    }

    @Test
    void unknownProduct_add_isNotFound() {
        String token = newUser("unknown-product");
        var response = postRaw("/api/v1/cart/items",
                new AddItemRequest(99_999_999L, 1), token);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void cartsAreIsolatedPerUser() {
        String tokenA = newUser("iso-a");
        String tokenB = newUser("iso-b");
        Long productId = productIdBySku("SHOE-NK-DOWN12");

        post("/api/v1/cart/items", new AddItemRequest(productId, 1), tokenA);

        assertThat(getCart(tokenB).getBody().items()).isEmpty();
        assertThat(getCart(tokenA).getBody().items()).hasSize(1);
    }

    @Test
    void clear_emptiesTheCart() {
        String token = newUser("clear");
        Long productId = productIdBySku("SHOE-NK-DOWN12");
        post("/api/v1/cart/items", new AddItemRequest(productId, 1), token);

        CartResponse cleared = http.exchange("/api/v1/cart", HttpMethod.DELETE,
                new HttpEntity<CartResponse>(null, bearer(token)), CartResponse.class)
                .getBody();

        assertThat(cleared.items()).isEmpty();
        assertThat(cleared.subtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
