package org.example.reviewer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Любой OpenAI-совместимый endpoint: сам OpenAI, прокси, локальные vLLM/Ollama. */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public OpenAiCompatibleClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            ArrayNode messages = body.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("authorization", "Bearer " + apiKey)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("LLM вернула " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Вызов LLM прерван");
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Вызов LLM не удался: " + e.getMessage());
        }
    }
}
