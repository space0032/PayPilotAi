package com.paypilot.agent;

import java.util.Map;

/**
 * One planned tool call. The action receives a shared context map holding
 * every prior step's output so later steps can reference earlier results
 * (e.g. the productId chosen by the search step).
 */
public record AgentStep(String tool,
                        Map<String, Object> arguments,
                        StepAction action) {

    @FunctionalInterface
    public interface StepAction {
        Object execute(AgentTools tools, Map<String, Object> ctx) throws Exception;
    }
}
