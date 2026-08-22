package com.paypilot.security.jwt;

import com.paypilot.security.AuthenticatedUser;
import com.paypilot.security.domain.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-1234567890";
    private final JwtService jwtService = new JwtService(
            new JwtProperties(SECRET, 15), new MockEnvironment());

    private final AuthenticatedUser user =
            new AuthenticatedUser(42L, "pilot@example.com", Role.USER.name());

    @Test
    void issueThenParse_roundTripsPrincipal() {
        String token = jwtService.issue(user);

        AuthenticatedUser parsed = jwtService.parse(token);

        assertThat(parsed).isEqualTo(user);
    }

    @Test
    void tamperedToken_isRejected() {
        String token = jwtService.issue(user);
        String tampered = token.substring(0, token.length() - 3) + "xyz";

        assertThat(jwtService.parse(tampered)).isNull();
    }

    @Test
    void garbageToken_isRejected() {
        assertThat(jwtService.parse("not-a-jwt")).isNull();
    }

    @Test
    void expiredToken_isRejected() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .issuer("paypilot")
                .subject("42")
                .claim("email", user.email())
                .claim("role", Role.USER.name())
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() - 30_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parse(expired)).isNull();
    }

    @Test
    void shortSecret_failsFast() {
        var props = new JwtProperties("too-short", 15);
        var env = new MockEnvironment();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtService(props, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 characters");
    }
}
