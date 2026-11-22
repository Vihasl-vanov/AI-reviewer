package org.example.reviewer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /** Нет файла — работаем на дефолтах: агент должен приносить пользу без настройки. */
    public static ReviewerConfig load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new ReviewerConfig();
        }
        try {
            ReviewerConfig config = YAML.readValue(Files.readString(path), ReviewerConfig.class);
            return config == null ? new ReviewerConfig() : config;
        } catch (IOException e) {
            throw new ConfigurationException("Не удалось прочитать конфиг " + path + ": " + e.getMessage(), e);
        }
    }
}
