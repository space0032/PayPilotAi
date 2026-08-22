package com.paypilot.commerce.catalog.repo;

import com.paypilot.commerce.catalog.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
}
