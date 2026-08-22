package com.paypilot.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value in INR minor units (paise).
 *
 * All money in PayPilot is stored and computed as a {@code long} of paise.
 * Floating point types are forbidden for money: binary fractions cannot
 * represent decimal currency exactly, which eventually corrupts totals.
 *
 * Invariant for MVP commerce amounts: never negative. Refunds/credits will
 * be modelled explicitly by their own domain types rather than negative money,
 * so a sign error can never silently discount below zero.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public Money {
        if (paise < 0) {
            throw new IllegalArgumentException("Money must not be negative, got paise=" + paise);
        }
    }

    /** Factory from whole rupees. Use only when the amount is genuinely integral. */
    public static Money ofRupees(long rupees) {
        return new Money(Math.multiplyExact(rupees, 100L));
    }

    /** Canonical factory; prefer this at every system boundary (DB rows, APIs). */
    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.addExact(paise, other.paise));
    }

    /**
     * Subtraction with floor-at-zero is NOT provided deliberately:
     * discount logic must be an explicit pricing-domain decision, never an
     * accidental underflow hidden inside arithmetic.
     */
    public Money minus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money times(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got " + quantity);
        }
        return new Money(Math.multiplyExact(paise, quantity));
    }

    public boolean greaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isZero() {
        return paise == 0L;
    }

    /** Presentation-only conversion to rupee units; never use for computation. */
    public BigDecimal toRupees() {
        return BigDecimal.valueOf(paise, 2);
    }

    /** Presentation-only rupee value rounded down to 2 dp (e.g. from external feeds). */
    public static Money fromRupeeDecimal(BigDecimal rupees) {
        Objects.requireNonNull(rupees, "rupees");
        return new Money(rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact());
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    @Override
    public String toString() {
        return toRupees().toPlainString();
    }
}
