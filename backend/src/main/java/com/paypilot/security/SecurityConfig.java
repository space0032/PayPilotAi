package com.paypilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.common.logging.CorrelationIdFilter;
import com.paypilot.security.cors.CorsProperties;
import com.paypilot.security.jwt.JwtAuthFilter;
import com.paypilot.security.ratelimit.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Stateless security: no sessions, no CSRF (no cookies), bearer JWTs only.
 *
 * Public surface: actuator health, OpenAPI docs, /api/v1/auth/**.
 * Everything else requires a valid access token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtAuthFilter jwtAuthFilter,
                                    RateLimitFilter rateLimitFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Catalog browsing is public; carts and checkout are not.
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/categories").permitAll()
                        // Gateway webhooks authenticate via HMAC signature on
                        // the raw body, not bearer tokens - verified in the
                        // controller/service, never by the JWT filter.
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/payments/webhook").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticatedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Rate limit runs BEFORE authentication so bad actors pay the
                // cost of brute-forcing even without valid tokens.
                .addFilterBefore(rateLimitFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private final CorsProperties corsProperties;

    public SecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(corsProperties.allowedOrigins()));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, ex) ->
                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHENTICATED", "Authentication required");
    }

    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, ex) ->
                writeProblem(response, HttpServletResponse.SC_FORBIDDEN,
                        "FORBIDDEN", "Insufficient permissions");
    }

    /**
     * Security-layer rejections speak the same RFC-7807 dialect as
     * GlobalExceptionHandler so clients handle one error shape everywhere.
     * Written via the raw output stream: getWriter() would make Tomcat
     * append ";charset=ISO-8859-1" to the content type.
     */
    private void writeProblem(HttpServletResponse response, int status,
                              String code, String detail) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "title", status == 401 ? "Unauthorized" : "Forbidden",
                "status", status,
                "detail", detail,
                "code", code,
                "requestId", String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY)));
        byte[] payload = new ObjectMapper().writeValueAsBytes(body);
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
    }
}
