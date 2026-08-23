package com.paypilot.commerce.payment;

import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.order.repo.OrderItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Moves reserved units at payment-lifecycle boundaries:
 * confirmSale on capture, releaseSale on failure/expiry/cancel.
 *
 * A zero-row settlement means reserved bookkeeping diverged from reality -
 * throwing rolls the caller's whole transaction back and surfaces loudly
 * rather than letting phantom stock drift silently.
 */
@Component
public class StockSettlement {

    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final Clock clock;

    public StockSettlement(OrderItemRepository orderItemRepository,
                           InventoryRepository inventoryRepository,
                           Clock clock) {
        this.orderItemRepository = orderItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmSale(Long orderId) {
        settle(orderId, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseSale(Long orderId) {
        settle(orderId, false);
    }

    private void settle(Long orderId, boolean captured) {
        var items = orderItemRepository.findByOrderId(orderId);
        for (var item : items) {
            int moved = captured
                    ? inventoryRepository.confirmSale(item.getProductId(), item.getQuantity(), clock.instant())
                    : inventoryRepository.release(item.getProductId(), item.getQuantity(), clock.instant());
            if (moved == 0) {
                throw new IllegalStateException(
                        "Stock settlement failed for product %d qty %d on order %d (%s)"
                                .formatted(item.getProductId(), item.getQuantity(),
                                        orderId, captured ? "capture" : "release"));
            }
        }
    }
}
