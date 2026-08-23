package com.paypilot.commerce.order.api;

import com.paypilot.commerce.catalog.api.dto.PageResponse;
import com.paypilot.commerce.order.OrderService;
import com.paypilot.commerce.order.api.dto.OrderResponse;
import com.paypilot.commerce.order.api.dto.OrderSummary;
import com.paypilot.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints. Ownership is enforced in the service layer: a userId is
 * always the authenticated principal, never client input.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse checkout(@AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.checkout(user.userId());
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable Long orderId) {
        return orderService.get(user.userId(), orderId);
    }

    @GetMapping
    public PageResponse<OrderSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return orderService.list(user.userId(), page, size);
    }

    /** Cancel an unpaid order; releases its stock reservation atomically. */
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable Long orderId) {
        return orderService.cancel(user.userId(), orderId);
    }
}
