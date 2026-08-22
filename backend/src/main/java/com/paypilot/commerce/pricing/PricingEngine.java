package com.paypilot.commerce.pricing;

import com.paypilot.commerce.offer.domain.OfferType;
import org.springframework.stereotype.Component;

/**
 * Pure discount arithmetic - no I/O, no clock, fully unit-testable.
 *
 * Rounding policy: PERCENTAGE discounts FLOOR to the whole paise. Flooring
 * can only ever under-discount by one rounding step, never over-discount -
 * the safe direction for money the merchant collects. (Overflow is not a
 * concern: even a ₹9.2-trillion cart in paise times 10000 bp stays inside
 * a signed long.)
 */
@Component
public class PricingEngine {

    private static final long BASIS_POINTS = 10_000L;

    /** Discount in paise for the given offer parameters against a subtotal. */
    public long quote(OfferType type,
                      long discountValue,
                      Long maxDiscountPaise,
                      long subtotalPaise) {
        if (subtotalPaise < 0) {
            throw new IllegalArgumentException("subtotal must be non-negative");
        }
        long raw = switch (type) {
            case PERCENTAGE -> Math.floorDiv(subtotalPaise * discountValue, BASIS_POINTS);
            case FLAT -> discountValue;
        };
        if (maxDiscountPaise != null && maxDiscountPaise >= 0) {
            raw = Math.min(raw, maxDiscountPaise);
        }
        return Math.min(raw, subtotalPaise);
    }
}
