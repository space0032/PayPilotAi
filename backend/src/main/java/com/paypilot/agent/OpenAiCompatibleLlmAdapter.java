package com.paypilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Speaks the de-facto /chat/completions dialect (OpenAI, and every
 * self-hosted server that clones it) over plain java.net.http - no SDK.
 * Activated only when paypilot.agent.llm.provider=openai-compatible; with
 * the default "none" no LLM bean exists and the mock planner runs alone.
 */
@Component
@ConditionalOnProperty(name = "paypilot.agent.llm.provider",
        havingValue = "openai-compatible")
public class OpenAiCompatibleLlmAdapter implements LlmPort {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;

    public OpenAiCompatibleLlmAdapter(
            @Value("${paypilot.agent.llm.base-url}") String baseUrl,
            @Value("${paypilot.agent.llm.api-key}") String apiKey,
            @Value("${paypilot.agent.llm.model}") String model,
            ObjectMapper mapper) {
        this.endpoint = baseUrl.replaceAll("/$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public String complete(List<LlmMessage> messages) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages", messages.stream()
                            .map(m -> Map.of("role", m.role(), "content", m.content()))
                            .toList());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LLM HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
            }
            JsonNode content = mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || !content.isTextual()) {
                throw new IllegalStateException("LLM reply missing choices[0]"
                        + ".message.content: " + truncate(response.body()));
            }
            return content.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM call failed: " + e, e);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
