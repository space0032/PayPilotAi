package com.paypilot.common.money;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Wires the currency converter.  Rates arrive as a JSON string from the
 * {@code CURRENCY_RATES} environment variable so operators can set them
 * at deploy time without rebuilding the image.
 *
 * Example:
 *   CURRENCY_RATES='{"USD":0.012,"EUR":0.011,"GBP":0.0095}'
 *
 * If the variable is absent or empty, only INR→INR (identity) is available.
 */
@Configuration
public class MoneyConfig {

    @Bean
    CurrencyConverter currencyConverter(
            @Value("${CURRENCY_RATES:{}}") String ratesJson) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> rates = (ratesJson == null || ratesJson.isBlank())
                ? Map.of()
                : mapper.readValue(ratesJson, new TypeReference<>() {});
        return new InMemoryCurrencyConverter(rates);
    }
}
