package com.paypilot.agent;

import java.util.List;

/**
 * Minimal chat-completion boundary. The agent package depends only on
 * this seam; concrete providers (openai-compatible today) live behind
 * it, and tests substitute a stub HTTP server - no SDK, no vendor lock.
 */
public interface LlmPort {

    /**
     * @param messages ordered conversation turns (system/user/assistant)
     * @return the assistant's reply text
     * @throws IllegalStateException on transport or protocol failure -
     *                               callers audit it as INTERNAL_ERROR and
     *                               end the run safely
     */
    String complete(List<LlmMessage> messages);

    record LlmMessage(String role, String content) {
        public static LlmMessage system(String content) {
            return new LlmMessage("system", content);
        }

        public static LlmMessage user(String content) {
            return new LlmMessage("user", content);
        }
    }
}
