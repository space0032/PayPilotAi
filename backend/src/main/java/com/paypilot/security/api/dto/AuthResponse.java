package com.paypilot.security.api.dto;

/**
 * Token grant returned by register/login/refresh.
 * The raw refresh token appears exactly here and nowhere else ever again
 * (the database stores only its SHA-256 hash).
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        Long userId,
        String email,
        String role) {
}
