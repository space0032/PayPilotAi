package com.paypilot.commerce.catalog.repo;

import com.paypilot.commerce.catalog.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /** Direct stock correction (restocks, test fixtures); reservations go through checkout. */
    @Modifying
    @Query("UPDATE Inventory i SET i.available = :available WHERE i.productId = :productId")
    void setAvailable(@Param("productId") Long productId, @Param("available") int available);

    /**
     * The oversell guard. Single conditional statement: succeeds only when
     * enough stock is un-reserved right now, under the row lock the UPDATE
     * itself takes. Returns 0 instead of throwing when short - callers
     * decide rollback semantics. Bulk JPQL bypasses @UpdateTimestamp,
     * hence the explicit timestamp.
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.available = i.available - :qty,
                i.reserved = i.reserved + :qty,
                i.updatedAt = :now
            WHERE i.productId = :productId AND i.available >= :qty
            """)
    int reserve(@Param("productId") Long productId,
                @Param("qty") int qty,
                @Param("now") Instant now);

    /** Payment captured: reserved units leave the building for good. */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.reserved = i.reserved - :qty,
                i.updatedAt = :now
            WHERE i.productId = :productId AND i.reserved >= :qty
            """)
    int confirmSale(@Param("productId") Long productId, @Param("qty") int qty,
                    @Param("now") Instant now);

    /** Payment failed/cancelled: give the reserved units back to shoppers. */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.available = i.available + :qty,
                i.reserved = i.reserved - :qty,
                i.updatedAt = :now
            WHERE i.productId = :productId AND i.reserved >= :qty
            """)
    int release(@Param("productId") Long productId, @Param("qty") int qty,
                @Param("now") Instant now);
}
