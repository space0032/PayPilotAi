package com.paypilot.commerce.catalog.repo;

import com.paypilot.commerce.catalog.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /** Direct stock correction (restocks, test fixtures); reservations go through checkout. */
    @Modifying
    @Query("UPDATE Inventory i SET i.available = :available WHERE i.productId = :productId")
    void setAvailable(@Param("productId") Long productId, @Param("available") int available);
}
