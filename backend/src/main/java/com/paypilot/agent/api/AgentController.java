package com.paypilot.agent.api;

import com.paypilot.agent.AgentService;
import com.paypilot.agent.api.dto.SessionTranscriptResponse;
import com.paypilot.agent.api.dto.StartSessionRequest;
import com.paypilot.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent endpoints. Sessions run synchronously and pause for the human:
 * a live run stops at REQUESTED consent, the user answers through
 * consent/confirm or consent/cancel, then POST /run continues the
 * journey from the persisted trace. Ownership is enforced in the
 * service - a transcript is only ever visible to its own user.
 */
@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionTranscriptResponse start(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestBody StartSessionRequest request) {
        return agentService.startAndRun(user.userId(), request);
    }

    /** Resume/continue a paused session (after consent was answered). */
    @PostMapping("/{sessionId}/run")
    public SessionTranscriptResponse run(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable Long sessionId) {
        return agentService.run(user.userId(), sessionId);
    }

    @PostMapping("/{sessionId}/consent/confirm")
    public SessionTranscriptResponse confirmConsent(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long sessionId) {
        return agentService.confirmConsent(user.userId(), sessionId);
    }

    @PostMapping("/{sessionId}/consent/cancel")
    public SessionTranscriptResponse cancelConsent(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long sessionId) {
        return agentService.cancelConsent(user.userId(), sessionId);
    }

    @GetMapping("/{sessionId}")
    public SessionTranscriptResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable Long sessionId) {
        return agentService.get(user.userId(), sessionId);
    }
}
