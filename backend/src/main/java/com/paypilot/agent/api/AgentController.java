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
 * Agent endpoints. Sessions run synchronously for now (mock planner);
 * ownership is enforced in the service - a transcript is only ever
 * visible to the user whose goal produced it.
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

    @GetMapping("/{sessionId}")
    public SessionTranscriptResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable Long sessionId) {
        return agentService.get(user.userId(), sessionId);
    }
}
