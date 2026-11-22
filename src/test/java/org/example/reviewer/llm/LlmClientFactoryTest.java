package org.example.reviewer.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.example.reviewer.config.ConfigLoader;
import org.example.reviewer.config.ConfigurationException;
import org.example.reviewer.config.ReviewerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmClientFactoryTest {

    @Test
    void createsAnthropicClientByDefault() {
        LlmClient client = LlmClientFactory.create(new ReviewerConfig(), Map.of("ANTHROPIC_API_KEY", "k"));

        assertInstanceOf(AnthropicClient.class, client);
    }

    @Test
    void createsOpenAiClientWhenConfigured(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("c.yml");
        Files.writeString(config, "provider: openai\n");

        LlmClient client = LlmClientFactory.create(ConfigLoader.load(config), Map.of("OPENAI_API_KEY", "k"));

        assertInstanceOf(OpenAiCompatibleClient.class, client);
    }

    @Test
    void explainsWhichEnvironmentVariableIsMissing() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> LlmClientFactory.create(new ReviewerConfig(), Map.of()));

        assertTrue(thrown.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void rejectsUnknownProvider(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("c.yml");
        Files.writeString(config, "provider: telepathy\n");
        ReviewerConfig loaded = ConfigLoader.load(config);

        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> LlmClientFactory.create(loaded, Map.of("ANTHROPIC_API_KEY", "k")));

        assertTrue(thrown.getMessage().contains("telepathy"));
    }
}
