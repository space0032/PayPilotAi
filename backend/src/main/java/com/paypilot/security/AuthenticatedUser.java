package com.paypilot.security;

/**
 * The authenticated principal carried in the security context after JWT
 * validation. Deliberately a small immutable record: services receive this
 * and derive ownership from {@code userId}, never from request payloads.
 */
public record AuthenticatedUser(Long userId, String email, String role) {
}
