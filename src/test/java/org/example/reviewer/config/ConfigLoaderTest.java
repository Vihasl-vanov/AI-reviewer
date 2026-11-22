package org.example.reviewer.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @Test
    void returnsDefaultsWhenFileMissing() {
        ReviewerConfig config = ConfigLoader.load(Path.of("no-such-file.yml"));

        assertEquals("anthropic", config.provider());
        assertEquals(25, config.maxFiles());
        assertEquals("error", config.failOn());
        assertFalse(config.rules().isEmpty(), "дефолтные правила должны быть, иначе агент бесполезен из коробки");
    }

    @Test
    void readsOverridesFromYaml(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".ai-reviewer.yml");
        Files.writeString(file, """
                provider: openai
                model: gpt-4o-mini
                maxFiles: 3
                maxFindingsPerFile: 2
                failOn: never
                unknownFutureField: ignored
                exclude:
                  - "**/target/**"
                rules:
                  - id: no-sout
                    severity: warning
                    description: Логировать через logger, не через System.out.
                """);

        ReviewerConfig config = ConfigLoader.load(file);

        assertEquals("openai", config.provider());
        assertEquals("gpt-4o-mini", config.model());
        assertEquals(3, config.maxFiles());
        assertEquals(2, config.maxFindingsPerFile());
        assertEquals("never", config.failOn());
        assertEquals(1, config.exclude().size());
        assertEquals(1, config.rules().size());
        assertEquals("no-sout", config.rules().get(0).id());
        assertEquals(Severity.WARNING, config.rules().get(0).severity());
    }

    @Test
    void failsLoudlyOnBrokenYaml(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.yml");
        Files.writeString(file, "rules: [this is not: valid: yaml");

        assertThrows(ConfigurationException.class, () -> ConfigLoader.load(file));
    }

    @Test
    void parsesSeverityCaseInsensitivelyAndFallsBackToInfo() {
        assertEquals(Severity.ERROR, Severity.parse("ERROR"));
        assertEquals(Severity.WARNING, Severity.parse("warning"));
        assertEquals(Severity.INFO, Severity.parse("nonsense"));
        assertEquals(Severity.INFO, Severity.parse(null));
    }
}
