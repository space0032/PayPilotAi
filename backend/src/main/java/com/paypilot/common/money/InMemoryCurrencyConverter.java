package com.paypilot.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory currency converter seeded from a JSON map of rates relative
 * to a base currency (INR by default).  Rates are configured at deploy
 * time via the {@code CURRENCY_RATES} environment variable:
 *
 * <pre>
 *   CURRENCY_RATES='{"USD":0.012,"EUR":0.011,"GBP":0.0095}'
 * </pre>
 *
 * The base currency (INR) is always implied at 1.0.  This keeps the
 * converter dependency-free for MVP; swap in an external API-backed
 * implementation without changing any caller.
 *
 * Thread-safe: the rate map is built once at construction and never mutated.
 */
public class InMemoryCurrencyConverter implements CurrencyConverter {

    private static final String BASE = "INR";

    private final Map<String, BigDecimal> ratesToBase;

    /**
     * @param rates map of currency code → rate-per-1-INR (e.g. "USD" → 0.012)
     */
    public InMemoryCurrencyConverter(Map<String, String> rates) {
        Map<String, BigDecimal> map = new HashMap<>();
        map.put(BASE, BigDecimal.ONE);                       // INR → INR = 1.0
        if (rates != null) {
            rates.forEach((code, rate) -> {
                String upper = code.toUpperCase(Locale.ROOT);
                map.put(upper, new BigDecimal(rate));
            });
        }
        this.ratesToBase = Map.copyOf(map);
    }

    @Override
    public long convert(long amountPaise, String from, String to) {
        String fromUpper = from.toUpperCase(Locale.ROOT);
        String toUpper   = to.toUpperCase(Locale.ROOT);
        if (!ratesToBase.containsKey(fromUpper)) {
            throw new IllegalArgumentException("Unknown source currency: " + from);
        }
        if (!ratesToBase.containsKey(toUpper)) {
            throw new IllegalArgumentException("Unknown target currency: " + to);
        }
        // amount in base currency (INR) = amountPaise / rateFrom
        BigDecimal inBase = BigDecimal.valueOf(amountPaise)
                .divide(ratesToBase.get(fromUpper), 10, RoundingMode.HALF_UP);
        // amount in target = inBase * rateTo
        BigDecimal converted = inBase.multiply(ratesToBase.get(toUpper));
        return converted.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public boolean supports(String from, String to) {
        return ratesToBase.containsKey(from.toUpperCase(Locale.ROOT))
                && ratesToBase.containsKey(to.toUpperCase(Locale.ROOT));
    }
}
