package com.paypilot.commerce.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Speaks Razorpay's REST API (Orders + Refunds) over plain
 * java.net.http - no SDK. Activated only when
 * paypilot.payments.gateway=razorpay; the mock adapter remains the
 * default so the whole test suite runs account-free.
 *
 * Money never enters this class blind: PaymentService re-verifies the
 * echoed amount before persisting anything, and webhook truth (capture,
 * failure) still arrives exclusively through the signed-webhook pipeline.
 */
@Component
@ConditionalOnProperty(name = "paypilot.payments.gateway",
        havingValue = "razorpay")
public class LiveRazorpayAdapter implements PaymentGatewayPort {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String basicAuth;
    private final ObjectMapper mapper;

    public LiveRazorpayAdapter(
            @Value("${paypilot.payments.razorpay.base-url:https://api.razorpay.com/v1}")
            String baseUrl,
            @Value("${paypilot.payments.razorpay.key-id}") String keyId,
            @Value("${paypilot.payments.razorpay.key-secret}") String keySecret,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.mapper = mapper;
    }

    @Override
    public GatewayOrder createOrder(GatewayOrderRequest request) {
        JsonNode body = send("POST", "/orders", Map.of(
                "amount", request.amountPaise(),
                "currency", request.currency(),
                "receipt", request.receipt()));
        return new GatewayOrder(
                required(body, "id").asText(),
                required(body, "amount").asLong(),
                required(body, "currency").asText(),
                body.path("status").asText("created"));
    }

    @Override
    public GatewayRefund refund(String gatewayPaymentId, long amountPaise) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            throw new GatewayException(GatewayException.Kind.REJECTED,
                    "Cannot refund without a gateway payment id");
        }
        JsonNode body = send("POST",
                "/payments/" + gatewayPaymentId + "/refund",
                Map.of("amount", amountPaise));
        return new GatewayRefund(
                required(body, "id").asText(),
                required(body, "amount").asLong(),
                body.path("status").asText("processed"));
    }

    // ------------------------------------------------------------------

    private JsonNode send(String method, String path, Object payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json");
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload)));
            } else {
                builder.GET();
            }
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 500) {
                throw new GatewayException(GatewayException.Kind.DOWN,
                        "Razorpay " + status + ": " + truncate(response.body()));
            }
            if (status < 200 || status >= 300) {
                // 4xx: our request was wrong - retrying unchanged won't help.
                throw new GatewayException(GatewayException.Kind.REJECTED,
                        "Razorpay " + status + ": " + truncate(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (GatewayException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new GatewayException(GatewayException.Kind.DOWN,
                    "Razorpay unreachable at " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Kind.DOWN,
                    "Interrupted calling Razorpay" + path, e);
        }
    }

    private static JsonNode required(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode()) {
            throw new GatewayException(GatewayException.Kind.REJECTED,
                    "Razorpay reply missing '" + field + "'");
        }
        return node;
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
