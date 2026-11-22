package org.example.reviewer.llm;

/** Единственная точка общения с моделью. Реализации: Anthropic, OpenAI-совместимая, фейк в тестах. */
public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
