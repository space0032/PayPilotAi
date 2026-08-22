package com.paypilot.commerce.catalog.repo;

import com.paypilot.commerce.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    /**
     * Catalog search used by both REST endpoints (Phase 4) and the agent's
     * searchProducts tool.
     *
     * The ILIKE predicates are served by the pg_trgm GIN index created in V2,
     * so '%term%' matching stays index-assisted instead of degenerating into
     * a full scan as the catalog grows.
     *
     * Explicit CASTs keep PostgreSQL happy about untyped JDBC parameters
     * appearing inside IS NULL branches.
     */
    @Query(nativeQuery = true, value = """
            SELECT p.*
            FROM products p
            WHERE p.active = TRUE
              AND (:categoryId IS NULL OR p.category_id = CAST(:categoryId AS bigint))
              AND (:minPricePaise IS NULL OR p.price_paise >= CAST(:minPricePaise AS bigint))
              AND (:maxPricePaise IS NULL OR p.price_paise <= CAST(:maxPricePaise AS bigint))
              AND (:term IS NULL
                   OR p.title ILIKE '%' || CAST(:term AS text) || '%'
                   OR p.brand ILIKE '%' || CAST(:term AS text) || '%')
            ORDER BY p.rating DESC NULLS LAST, p.price_paise ASC
            LIMIT CAST(:limit AS int) OFFSET CAST(:offset AS int)
            """)
    List<Product> searchCatalog(@Param("term") String term,
                                @Param("categoryId") Long categoryId,
                                @Param("minPricePaise") Long minPricePaise,
                                @Param("maxPricePaise") Long maxPricePaise,
                                @Param("limit") int limit,
                                @Param("offset") int offset);
}
