package com.paypilot.common.money;

/**
 * Currency conversion port. The commerce module prices products in a single
 * base currency (INR by default); this interface lets callers view prices in
 * any supported currency without polluting the domain model with rate-fetching
 * concerns.
 *
 * Implementations must be thread-safe and rate-limit-aware: the same converter
 * instance may be called from catalog reads, agent chat, and webhook
 * confirmation emails concurrently.
 */
public interface CurrencyConverter {

    /**
     * Convert an amount from one ISO 4217 currency to another.  If either
     * currency is unknown, throw {@link IllegalArgumentException} — callers
     * must not silently guess.
     *
     * @param amountPaise amount in minor units of the source currency
     * @param from        source currency code, e.g. "INR"
     * @param to          target currency code, e.g. "USD"
     * @return converted amount in minor units of the target currency,
     *         rounded half-up to the nearest whole minor unit
     */
    long convert(long amountPaise, String from, String to);

    /** Returns true if the converter has a rate for this currency pair. */
    boolean supports(String from, String to);
}
