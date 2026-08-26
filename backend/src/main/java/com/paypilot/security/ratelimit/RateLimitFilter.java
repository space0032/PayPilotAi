package com.paypilot.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-per-IP token-bucket rate limiting on the public auth endpoints -
 * the brute-force / credential-stuffing front door.
 *
 * MVP scope: single-node in-memory buckets. Horizontal scaling requires a
 * Redis-backed bucket map (tracked in the technical-debt log).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private final Map<String, Bucket> bucketsByIp = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final TrustedProxyResolver proxyResolver;

    public RateLimitFilter(RateLimitProperties properties,
                           TrustedProxyResolver proxyResolver) {
        this.properties = properties;
        this.proxyResolver = proxyResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Preflights carry no credentials and browsers send them before we
        // could ever authenticate - counting them would tax CORS itself.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith(AUTH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = proxyResolver.clientIp(request);
        Bucket bucket = bucketsByIp.computeIfAbsent(ip, ignored -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit exceeded for auth request from {}", ip);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        byte[] payload = """
                {"title":"Too Many Requests","status":429,\
                "detail":"Too many authentication attempts; slow down",\
                "code":"RATE_LIMITED"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
    }

    private Bucket newBucket() {
        long capacity = properties.authCapacityPerMinute();
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
