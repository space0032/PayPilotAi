package com.paypilot.commerce.cart.api;

import com.paypilot.commerce.cart.CartService;
import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.cart.api.dto.UpdateItemRequest;
import com.paypilot.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart endpoints. The userId comes exclusively from the authenticated
 * principal - request bodies can never reference another user's cart.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse view(@AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.view(user.userId());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse add(@AuthenticationPrincipal AuthenticatedUser user,
                            @Valid @RequestBody AddItemRequest request) {
        return cartService.add(user.userId(), request);
    }

    @PatchMapping("/items/{productId}")
    public CartResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable Long productId,
                               @Valid @RequestBody UpdateItemRequest request) {
        return cartService.update(user.userId(), productId, request);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse remove(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable Long productId) {
        return cartService.remove(user.userId(), productId);
    }

    @DeleteMapping
    public CartResponse clear(@AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.clear(user.userId());
    }
}
