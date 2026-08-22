package com.paypilot.commerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Razorpay-style webhook authentication: HMAC-SHA256 over the RAW request
 * body with a shared secret, hex-encoded in the X-Razorpay-Signature header.
 *
 * Verification is constant-time (MessageDigest.isEqual) so timing cannot
 * leak how much of a forged signature matched.
 *
 * sign() exists for the mock-gateway simulate endpoints only - production
 * signatures come from the real gateway, never from our own code.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${paypilot.payments.webhook-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(String rawBody, String signatureHex) {
        if (rawBody == null || signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, hexToBytes(signatureHex));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return hex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks HmacSHA256", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex signature");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
