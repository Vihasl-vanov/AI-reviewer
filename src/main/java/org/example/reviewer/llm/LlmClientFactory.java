package org.example.reviewer.llm;

import java.util.Map;
import org.example.reviewer.config.ConfigurationException;
import org.example.reviewer.config.ReviewerConfig;

public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    /** env передаётся параметром, чтобы фабрику можно было проверить тестом. */
    public static LlmClient create(ReviewerConfig config, Map<String, String> env) {
        String provider = config.provider() == null ? "" : config.provider().trim().toLowerCase();
        return switch (provider) {
            case "anthropic" -> new AnthropicClient(
                    require(env, "ANTHROPIC_API_KEY"), config.model(), env.get("ANTHROPIC_BASE_URL"));
            case "openai" -> new OpenAiCompatibleClient(
                    require(env, "OPENAI_API_KEY"), config.model(), env.get("OPENAI_BASE_URL"));
            default -> throw new ConfigurationException(
                    "Неизвестный provider в конфиге: " + config.provider() + ". Допустимо: anthropic, openai.");
        };
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("Не задана переменная окружения " + name + ".");
        }
        return value;
    }
}
