package com.paypilot.commerce.catalog.repo;

import com.paypilot.commerce.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    /** Price correction (admin ops, test fixtures). Cart snapshots stay historical. */
    @Modifying
    @Query("UPDATE Product p SET p.pricePaise = :pricePaise WHERE p.id = :id")
    void setPricePaise(@Param("id") Long id, @Param("pricePaise") long pricePaise);

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
     *
     * Sort is a whitelisted string, never raw SQL: 'price_asc'/'price_desc'
     * activate CASE branches; anything else falls through to the default
     * relevance order (rating desc, price asc). Injection-proof by design.
     */
    @Query(nativeQuery = true, value = """
            SELECT p.*
            FROM products p
            WHERE p.active = TRUE
              AND (:categoryId IS NULL OR p.category_id = CAST(:categoryId AS bigint))
              AND (:minPricePaise IS NULL OR p.price_paise >= CAST(:minPricePaise AS bigint))
              AND (:maxPricePaise IS NULL OR p.price_paise <= CAST(:maxPricePaise AS bigint))
              AND (:term IS NULL
                   OR p.title ILIKE '%' || CAST(:term AS text) || '%' ESCAPE '\\'
                   OR p.brand ILIKE '%' || CAST(:term AS text) || '%' ESCAPE '\\')
            ORDER BY
              CASE WHEN CAST(:sort AS text) = 'price_asc'  THEN p.price_paise END ASC NULLS LAST,
              CASE WHEN CAST(:sort AS text) = 'price_desc' THEN p.price_paise END DESC NULLS LAST,
              p.rating DESC NULLS LAST,
              p.price_paise ASC,
              p.id ASC
            LIMIT CAST(:limit AS int) OFFSET CAST(:offset AS int)
            """)
    List<Product> searchCatalog(@Param("term") String term,
                                @Param("categoryId") Long categoryId,
                                @Param("minPricePaise") Long minPricePaise,
                                @Param("maxPricePaise") Long maxPricePaise,
                                @Param("sort") String sort,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    /** Same predicates as {@link #searchCatalog}, for pagination metadata. */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
            FROM products p
            WHERE p.active = TRUE
              AND (:categoryId IS NULL OR p.category_id = CAST(:categoryId AS bigint))
              AND (:minPricePaise IS NULL OR p.price_paise >= CAST(:minPricePaise AS bigint))
              AND (:maxPricePaise IS NULL OR p.price_paise <= CAST(:maxPricePaise AS bigint))
              AND (:term IS NULL
                   OR p.title ILIKE '%' || CAST(:term AS text) || '%' ESCAPE '\\'
                   OR p.brand ILIKE '%' || CAST(:term AS text) || '%' ESCAPE '\\')
            """)
    long countCatalog(@Param("term") String term,
                      @Param("categoryId") Long categoryId,
                      @Param("minPricePaise") Long minPricePaise,
                      @Param("maxPricePaise") Long maxPricePaise);
}
