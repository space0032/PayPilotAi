package com.paypilot.security.api;

import com.paypilot.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity echo - the canonical smoke test for the auth pipeline and a
 * convenient hook for frontend session bootstrapping.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record MeResponse(Long userId, String email, String role) {
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.email(), user.role());
    }
}
