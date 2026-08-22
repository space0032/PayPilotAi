package com.paypilot.commerce.cart;

import com.paypilot.common.error.BadRequestException;
import com.paypilot.common.error.ConflictException;
import com.paypilot.common.error.NotFoundException;
import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.CartItemResponse;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.cart.api.dto.UpdateItemRequest;
import com.paypilot.commerce.cart.domain.Cart;
import com.paypilot.commerce.cart.domain.CartItem;
import com.paypilot.commerce.cart.repo.CartItemRepository;
import com.paypilot.commerce.cart.repo.CartRepository;
import com.paypilot.commerce.catalog.domain.Product;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cart mutations are serialized per user via the pessimistic lock on the
 * active-cart row; every method here runs inside that lock's transaction.
 *
 * Pricing rule: clients never send prices. Snapshots record add-time price
 * for audit; displayed totals use live catalog prices, and checkout will
 * re-price again before payment (defense in depth against price drift).
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       ProductRepository productRepository,
                       InventoryRepository inventoryRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public CartResponse view(Long userId) {
        Cart cart = getOrCreateActiveCart(userId);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse add(Long userId, AddItemRequest request) {
        var product = productRepository.findById(request.productId())
                .orElseThrow(() -> new NotFoundException("Product", request.productId()));
        if (!product.isActive()) {
            throw new BadRequestException("INACTIVE_PRODUCT",
                    "Product is no longer available: " + product.getSku());
        }
        int available = inventoryRepository.findById(product.getId())
                .map(inv -> inv.getAvailable()).orElse(0);

        Cart cart = getOrCreateActiveCart(userId);
        CartItem item = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId())
                .orElse(null);
        int currentQty = item == null ? 0 : item.getQuantity();
        int newQty = currentQty + request.quantity();

        if (newQty > CartItem.MAX_QUANTITY) {
            throw new BadRequestException("QUANTITY_LIMIT",
                    "Maximum " + CartItem.MAX_QUANTITY + " units per product");
        }
        if (newQty > available) {
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Only " + available + " unit(s) available");
        }

        if (item == null) {
            cartItemRepository.save(new CartItem(cart.getId(), product.getId(),
                    newQty, product.getPricePaise()));
        } else {
            item.setQuantity(newQty);
        }
        return toResponse(cart);
    }

    @Transactional
    public CartResponse update(Long userId, Long productId, UpdateItemRequest request) {
        int quantity = request.quantity();
        Cart cart = requireActiveCart(userId);

        if (quantity == 0) {
            cartItemRepository.deleteByCartIdAndProductId(cart.getId(), productId);
            return toResponse(cart);
        }

        var item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new NotFoundException("Cart item", productId));
        if (quantity > CartItem.MAX_QUANTITY) {
            throw new BadRequestException("QUANTITY_LIMIT",
                    "Maximum " + CartItem.MAX_QUANTITY + " units per product");
        }
        int available = inventoryRepository.findById(productId)
                .map(inv -> inv.getAvailable()).orElse(0);
        if (quantity > available) {
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Only " + available + " unit(s) available");
        }
        item.setQuantity(quantity);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse remove(Long userId, Long productId) {
        Cart cart = requireActiveCart(userId);
        cartItemRepository.deleteByCartIdAndProductId(cart.getId(), productId);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse clear(Long userId) {
        Cart cart = requireActiveCart(userId);
        cartItemRepository.deleteByCartId(cart.getId());
        return toResponse(cart);
    }

    // ------------------------------------------------------------------

    /**
     * Fetch under lock; create when absent. The partial unique index makes a
     * duplicate ACTIVE cart impossible - if two requests race past the empty
     * select simultaneously, exactly one insert wins and the loser re-selects.
     */
    private Cart getOrCreateActiveCart(Long userId) {
        return cartRepository.findActiveCart(userId).orElseGet(() -> {
            try {
                return cartRepository.saveAndFlush(new Cart(userId));
            } catch (DataIntegrityViolationException e) {
                return cartRepository.findActiveCart(userId)
                        .orElseThrow(() -> e);
            }
        });
    }

    /** Mutations on an existing cart only - no implicit creation. */
    private Cart requireActiveCart(Long userId) {
        return cartRepository.findActiveCart(userId)
                .orElseThrow(() -> new NotFoundException("Cart", userId));
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());

        Map<Long, Product> products =
                productRepository.findAllById(
                        items.stream().map(CartItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CartItemResponse> lines = items.stream()
                .map(item -> {
                    var p = products.get(item.getProductId());
                    long unitPaise = p.getPricePaise();
                    return new CartItemResponse(
                            item.getProductId(),
                            p.getSku(),
                            p.getBrand(),
                            p.getTitle(),
                            item.getQuantity(),
                            inventoryRepository.findById(item.getProductId())
                                    .map(inv -> inv.getAvailable()).orElse(0),
                            BigDecimal.valueOf(unitPaise, 2),
                            BigDecimal.valueOf(unitPaise * item.getQuantity(), 2),
                            unitPaise != item.getPriceSnapshotPaise(),
                            BigDecimal.valueOf(item.getPriceSnapshotPaise(), 2));
                })
                .toList();

        long subtotalPaise = items.stream()
                .mapToLong(item -> {
                    var p = products.get(item.getProductId());
                    return p == null ? 0 : p.getPricePaise() * item.getQuantity();
                })
                .sum();

        return new CartResponse(cart.getId(), lines, items.size(),
                BigDecimal.valueOf(subtotalPaise, 2));
    }
}
