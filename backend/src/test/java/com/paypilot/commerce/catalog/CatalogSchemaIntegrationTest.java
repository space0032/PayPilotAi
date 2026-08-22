package com.paypilot.commerce.catalog;

import com.paypilot.commerce.catalog.domain.Product;
import com.paypilot.commerce.catalog.repo.CategoryRepository;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 schema contract tests against real PostgreSQL:
 * migrations apply cleanly, seed data is present, search semantics hold,
 * JSONB round-trips, and database-level invariants are enforced.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CatalogSchemaIntegrationTest {

    private static final long RS_5000_IN_PAISE = 500_000L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsAppliedAndSeedLoaded() {
        assertThat(categoryRepository.count()).isEqualTo(6);
        assertThat(productRepository.count()).isEqualTo(32);
        assertThat(inventoryRepository.count()).isEqualTo(32);
        Integer offerCount = jdbc.queryForObject("SELECT count(*) FROM offers", Integer.class);
        assertThat(offerCount).isEqualTo(5);
    }

    @Test
    void runningShoesUnderBudget_excludePremiumModel() {
        Long footwearId = categoryRepository.findBySlug("footwear").orElseThrow().getId();

        List<Product> results = productRepository.searchCatalog(
                "running", footwearId, null, RS_5000_IN_PAISE, 20, 0);

        // "Trail-Ready Runner" deliberately does NOT match ILIKE '%running%';
        // Pegasus matches neither the term nor the budget.
        assertThat(results).hasSize(5);
        assertThat(results).allMatch(p -> p.getPricePaise() <= RS_5000_IN_PAISE);
        assertThat(results).extracting(Product::getSku)
                .doesNotContain("SHOE-NK-PEG41", "SHOE-AD-FALCN5")
                .contains("SHOE-NK-DOWN12", "SHOE-AD-GALAXY6");
    }

    @Test
    void unfilteredSearch_returnsWholeActiveCatalog() {
        List<Product> all = productRepository.searchCatalog(null, null, null, null, 100, 0);
        assertThat(all).hasSize(32);
    }

    @Test
    void jsonbAttributes_roundTrip() {
        Product shoe = productRepository.findBySku("SHOE-NK-DOWN12").orElseThrow();
        Map<String, Object> attrs = shoe.getAttributes();
        assertThat(attrs).containsEntry("use_case", "daily running");
        assertThat(attrs.get("size")).isNotNull();
    }

    @Test
    void negativePrice_rejectedByDatabaseCheck() {
        Long categoryId = categoryRepository.findBySlug("footwear").orElseThrow().getId();
        Product bad = new Product(categoryId, "SKU-BAD", "X", "Bad Product",
                null, -1L, BigDecimal.ONE, Map.of());

        assertThatThrownBy(() -> productRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void criticalConstraintsAndIndexes_exist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                "uq_carts_one_active_per_user",
                "uq_products_sku",
                "idx_products_title_trgm",
                "uq_offers_code",
                "idx_orders_user_created",
                "uq_payment_events_event_id",
                "idx_reservations_open_expiry");
    }
}
