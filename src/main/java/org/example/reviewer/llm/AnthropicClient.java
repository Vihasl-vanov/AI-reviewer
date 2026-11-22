package org.example.reviewer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Anthropic Messages API. */
public final class AnthropicClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public AnthropicClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 2000);
            body.put("system", systemPrompt);
            ObjectNode message = body.putArray("messages").addObject();
            message.put("role", "user");
            message.put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("Anthropic вернул " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body()).path("content").path(0).path("text").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Вызов Anthropic прерван");
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Вызов Anthropic не удался: " + e.getMessage());
        }
    }
}
