package com.paypilot.security;

import com.paypilot.common.error.ConflictException;
import com.paypilot.common.error.NotFoundException;
import com.paypilot.common.error.UnauthorizedException;
import com.paypilot.security.api.dto.AuthResponse;
import com.paypilot.security.api.dto.LoginRequest;
import com.paypilot.security.api.dto.RegisterRequest;
import com.paypilot.security.domain.User;
import com.paypilot.security.jwt.JwtService;
import com.paypilot.security.refresh.RefreshTokenService;
import com.paypilot.security.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration, credential verification and token grants.
 *
 * Security invariants:
 *  - "Invalid credentials" is identical whether or not the email exists.
 *  - Password hashes never leave this class.
 *  - Refresh tokens are only ever issued together with a verified identity.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password())));
        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return issueTokens(user);
    }

    public AuthResponse refresh(String rawRefreshToken) {
        Long userId = refreshTokenService.rotate(rawRefreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User", userId));
        return issueTokens(user);
    }

    private AuthResponse issueTokens(User user) {
        var principal = new AuthenticatedUser(
                user.getId(), user.getEmail(), user.getRole().name());
        String accessToken = jwtService.issue(principal);
        String rawRefresh = refreshTokenService.issue(user.getId());
        return new AuthResponse(accessToken, rawRefresh, jwtService.accessTokenTtlSeconds(),
                user.getId(), user.getEmail(), user.getRole().name());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
