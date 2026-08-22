package com.paypilot.commerce.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public catalog API against real PostgreSQL seeded by Flyway (32 products,
 * 6 categories). Covers listing, filtering, sorting, pagination metadata,
 * validation rejections and the ILIKE escaping contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CatalogApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void anonymousList_returnsPaginatedEnvelope() {
        ResponseEntity<Map<String, Object>> response = exchange("/api/v1/products");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("totalElements")).isEqualTo(32);
        assertThat(body.get("totalPages")).isEqualTo(2);
        assertThat(body.get("hasNext")).isEqualTo(true);
        assertThat((Integer) body.get("size")).isEqualTo(20);
    }

    @Test
    void searchTerm_matchesTitleOrBrand() {
        Map<String, Object> body = exchange("/api/v1/products?q=running").getBody();

        long total = ((Number) body.get("totalElements")).longValue();
        assertThat(total).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) body.get("items");
        assertThat(items).allSatisfy(item -> {
            String title = String.valueOf(item.get("title"));
            String brand = String.valueOf(item.get("brand"));
            assertThat(title.toLowerCase() + " " + brand.toLowerCase())
                    .contains("running");
        });
    }

    @Test
    void categoryAndPriceFilter_withPriceAscSort() {
        Map<String, Object> body =
                exchange("/api/v1/products?category=footwear&maxPrice=5000&sort=price_asc")
                        .getBody();

        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) body.get("items");
        assertThat(items).isNotEmpty();
        BigDecimal previous = null;
        for (Map<String, Object> item : items) {
            BigDecimal price = new BigDecimal(String.valueOf(item.get("price")));
            assertThat(price).isLessThanOrEqualTo(new BigDecimal("5000.00"));
            if (previous != null) {
                assertThat(price).isGreaterThanOrEqualTo(previous);
            }
            previous = price;
        }
    }

    @Test
    void wildcardMetacharacters_areTreatedLiterally() {
        // A raw '%' would match everything unescaped; escaped it matches nothing.
        Map<String, Object> body = exchange("/api/v1/products?q=%25").getBody();
        assertThat(body.get("totalElements")).isEqualTo(0);
    }

    @Test
    void invalidSort_isRejectedWithStableCode() {
        ResponseEntity<Map<String, Object>> response = exchange("/api/v1/products?sort=bogus");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_SORT");
    }

    @Test
    void invertedPriceRange_isRejected() {
        ResponseEntity<Map<String, Object>> response =
                exchange("/api/v1/products?minPrice=100&maxPrice=50");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_PRICE_RANGE");
    }

    @Test
    void unknownCategory_isNotFound() {
        ResponseEntity<Map<String, Object>> response =
                exchange("/api/v1/products?category=does-not-exist");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void detailBySku_includesCategoryAndStock() {
        // Raw-body assertions pin the wire contract exactly: prices must be
        // two-decimal literals. (Map/JsonNode bindings coerce 3499.00 to a
        // double and destroy the scale we're verifying.)
        ResponseEntity<String> response =
                http.getForEntity("/api/v1/products/SHOE-NK-DOWN12", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).contains("\"sku\":\"SHOE-NK-DOWN12\"");
        assertThat(body).containsPattern("\"price\":\\d+\\.\\d{2}");
        assertThat(body).contains("\"available\":");
        assertThat(body).contains("\"slug\":\"footwear\"");
    }

    @Test
    void detailUnknownSku_isNotFound() {
        ResponseEntity<Map<String, Object>> response = exchange("/api/v1/products/NOPE-123");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    private ResponseEntity<Map<String, Object>> exchange(String url) {
        return http.exchange(url, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });
    }
}
