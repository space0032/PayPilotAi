package com.paypilot.commerce.order;

import com.paypilot.common.error.BadRequestException;
import com.paypilot.common.error.ConflictException;
import com.paypilot.common.error.NotFoundException;
import com.paypilot.commerce.cart.domain.Cart;
import com.paypilot.commerce.cart.domain.CartItem;
import com.paypilot.commerce.cart.repo.CartItemRepository;
import com.paypilot.commerce.cart.repo.CartRepository;
import com.paypilot.commerce.catalog.domain.Product;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import com.paypilot.commerce.offer.OfferPolicy;
import com.paypilot.commerce.offer.domain.Offer;
import com.paypilot.commerce.offer.domain.OfferRedemption;
import com.paypilot.commerce.offer.repo.OfferRedemptionRepository;
import com.paypilot.commerce.offer.repo.OfferRepository;
import com.paypilot.commerce.order.api.dto.OrderItemResponse;
import com.paypilot.commerce.order.api.dto.OrderResponse;
import com.paypilot.commerce.order.domain.Order;
import com.paypilot.commerce.order.domain.OrderItem;
import com.paypilot.commerce.order.repo.OrderItemRepository;
import com.paypilot.commerce.order.repo.OrderRepository;
import com.paypilot.commerce.pricing.PricingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checkout: the only place stock is reserved and orders are born.
 *
 * Everything runs in ONE transaction guarded by the cart's pessimistic lock:
 *  - any failure (short stock, invalid offer) rolls back reservations made
 *    earlier in the same attempt - partial checkouts are impossible;
 *  - the offer is re-validated authoritatively here, even if it passed at
 *    apply-time (prices drift, offers expire between the two calls);
 *  - the cart snapshot freezes the exact purchase for audit and disputes.
 */
@Service
public class OrderService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final OfferRepository offerRepository;
    private final OfferRedemptionRepository redemptionRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PricingEngine pricingEngine;
    private final OfferPolicy offerPolicy;
    private final Clock clock;

    public OrderService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository,
                        InventoryRepository inventoryRepository,
                        OfferRepository offerRepository,
                        OfferRedemptionRepository redemptionRepository,
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        PricingEngine pricingEngine,
                        OfferPolicy offerPolicy,
                        Clock clock) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.offerRepository = offerRepository;
        this.redemptionRepository = redemptionRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.pricingEngine = pricingEngine;
        this.offerPolicy = offerPolicy;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse checkout(Long userId) {
        // Pessimistic lock: serializes checkout against concurrent cart edits.
        Cart cart = cartRepository.findActiveCart(userId)
                .orElseThrow(() -> new NotFoundException("Cart", userId));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            throw new BadRequestException("EMPTY_CART", "Cart is empty");
        }

        Map<Long, Product> products = loadProducts(items);

        long subtotalPaise = items.stream()
                .mapToLong(item -> products.get(item.getProductId()).getPricePaise()
                        * item.getQuantity())
                .sum();

        Long offerId = null;
        String offerCode = null;
        long discountPaise = 0;
        if (cart.getAppliedOfferId() != null) {
            Offer offer = offerRepository.findById(cart.getAppliedOfferId())
                    .orElse(null);
            if (offer != null) {
                offerPolicy.validate(offer, subtotalPaise,
                        redemptionRepository.countByOfferIdAndUserId(offer.getId(), userId));
                discountPaise = pricingEngine.quote(offer.getType(),
                        offer.getDiscountValue(), offer.getMaxDiscountPaise(), subtotalPaise);
                offerId = offer.getId();
                offerCode = offer.getCode();
            }
        }
        long totalPaise = subtotalPaise - discountPaise;

        reserveAll(items, products);

        Map<String, Object> snapshot =
                buildSnapshot(items, products, offerCode, subtotalPaise, discountPaise, totalPaise);
        Order order = orderRepository.save(
                new Order(userId, subtotalPaise, discountPaise, totalPaise, offerId, snapshot));

        List<OrderItem> savedItems = new ArrayList<>();
        for (CartItem item : items) {
            Product p = products.get(item.getProductId());
            savedItems.add(orderItemRepository.save(new OrderItem(
                    order.getId(), p.getId(), item.getQuantity(), p.getPricePaise())));
        }

        if (discountPaise > 0 && offerId != null) {
            redemptionRepository.save(
                    new OfferRedemption(offerId, userId, order.getId(), discountPaise));
        }

        cart.markOrdered();

        return toResponse(order, savedItems, products);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        List<Long> productIds = orderItems.stream().map(OrderItem::getProductId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return toResponse(order, orderItems, products);
    }

    // ------------------------------------------------------------------

    /**
     * Conditional per-line reservation. A zero-row result means someone else
     * took the stock between add-to-cart and now; throwing rolls back every
     * earlier successful reservation in this same transaction.
     */
    private void reserveAll(List<CartItem> items, Map<Long, Product> products) {
        for (CartItem item : items) {
            int updated = inventoryRepository.reserve(
                    item.getProductId(), item.getQuantity(), clock.instant());
            if (updated == 0) {
                Product p = products.get(item.getProductId());
                throw new ConflictException("INSUFFICIENT_STOCK",
                        "Not enough stock for " + p.getSku());
            }
        }
    }

    private void validateActive(List<CartItem> items, Map<Long, Product> products) {
        for (CartItem item : items) {
            Product p = products.get(item.getProductId());
            if (p == null || !p.isActive()) {
                throw new BadRequestException("INACTIVE_PRODUCT",
                        "Product is no longer available" +
                                (p == null ? "" : ": " + p.getSku()));
            }
        }
    }

    private Map<String, Object> buildSnapshot(List<CartItem> items,
                                              Map<Long, Product> products,
                                              String offerCode,
                                              long subtotalPaise,
                                              long discountPaise,
                                              long totalPaise) {
        validateActive(items, products);
        List<Map<String, Object>> lines = items.stream()
                .map(item -> {
                    Product p = products.get(item.getProductId());
                    Map<String, Object> line = new LinkedHashMap<String, Object>();
                    line.put("productId", p.getId());
                    line.put("sku", p.getSku());
                    line.put("title", p.getTitle());
                    line.put("quantity", item.getQuantity());
                    line.put("unitPricePaise", p.getPricePaise());
                    return line;
                })
                .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("items", lines);
        if (offerCode != null) {
            snapshot.put("offerCode", offerCode);
        }
        snapshot.put("subtotalPaise", subtotalPaise);
        snapshot.put("discountPaise", discountPaise);
        snapshot.put("totalPaise", totalPaise);
        return snapshot;
    }

    private Map<Long, Product> loadProducts(List<CartItem> items) {
        return productRepository.findAllById(
                        items.stream().map(CartItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private OrderResponse toResponse(Order order,
                                     List<OrderItem> items,
                                     Map<Long, Product> products) {
        List<OrderItemResponse> lines = items.stream()
                .map(item -> {
                    Product p = products.get(item.getProductId());
                    return new OrderItemResponse(
                            item.getProductId(),
                            p.getSku(),
                            p.getBrand(),
                            p.getTitle(),
                            item.getQuantity(),
                            BigDecimal.valueOf(item.getUnitPricePaise(), 2),
                            BigDecimal.valueOf(item.getUnitPricePaise() * item.getQuantity(), 2));
                })
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                lines,
                BigDecimal.valueOf(order.getSubtotalPaise(), 2),
                BigDecimal.valueOf(order.getDiscountPaise(), 2),
                BigDecimal.valueOf(order.getTotalPaise(), 2),
                order.getCreatedAt());
    }
}
