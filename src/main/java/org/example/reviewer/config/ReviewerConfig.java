package org.example.reviewer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Конфиг агента. Мутабельный POJO, а не record: Jackson должен уметь
 * переопределить часть полей, оставив остальным значения по умолчанию.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewerConfig {

    private String provider = "anthropic";
    private String model = "claude-sonnet-5-5";
    private int maxFiles = 25;
    private int maxFindingsPerFile = 5;
    private String failOn = "error";
    private List<String> exclude = new ArrayList<>(List.of(
            "**/target/**", "**/build/**", "**/*.lock", "**/*.min.js", "**/generated/**"));
    private List<RuleYaml> rules = defaultRules();

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public int maxFiles() {
        return maxFiles;
    }

    public int maxFindingsPerFile() {
        return maxFindingsPerFile;
    }

    public String failOn() {
        return failOn;
    }

    public List<String> exclude() {
        return List.copyOf(exclude);
    }

    public List<Rule> rules() {
        return rules.stream()
                .map(r -> new Rule(r.id, Severity.parse(r.severity), r.description))
                .toList();
    }

    @JsonProperty("provider")
    void setProvider(String value) {
        this.provider = value;
    }

    @JsonProperty("model")
    void setModel(String value) {
        this.model = value;
    }

    @JsonProperty("maxFiles")
    void setMaxFiles(int value) {
        this.maxFiles = value;
    }

    @JsonProperty("maxFindingsPerFile")
    void setMaxFindingsPerFile(int value) {
        this.maxFindingsPerFile = value;
    }

    @JsonProperty("failOn")
    void setFailOn(String value) {
        this.failOn = value;
    }

    @JsonProperty("exclude")
    void setExclude(List<String> value) {
        this.exclude = new ArrayList<>(value);
    }

    @JsonProperty("rules")
    void setRules(List<RuleYaml> value) {
        this.rules = new ArrayList<>(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RuleYaml {
        @JsonProperty("id")
        String id;
        @JsonProperty("severity")
        String severity;
        @JsonProperty("description")
        String description;
    }

    private static List<RuleYaml> defaultRules() {
        List<RuleYaml> defaults = new ArrayList<>();
        defaults.add(rule("npe-risk", "error",
                "Разыменование значения, которое может быть null: результат поиска, ответ внешнего сервиса, Optional.get() без проверки."));
        defaults.add(rule("swallowed-exception", "error",
                "Исключение поймано и проглочено: пустой catch, catch без логирования или без повторного выброса."));
        defaults.add(rule("resource-leak", "error",
                "Закрываемый ресурс (поток, соединение, ExecutorService) создан без try-with-resources или без гарантированного закрытия."));
        defaults.add(rule("secret-in-code", "error",
                "Токен, пароль, ключ или строка подключения зашиты в коде вместо конфигурации или переменной окружения."));
        defaults.add(rule("no-sout", "warning",
                "Отладочный вывод через System.out/System.err вместо логгера."));
        defaults.add(rule("missing-test", "warning",
                "Добавлена или изменена нетривиальная логика, но тестов на неё в этом PR нет."));
        defaults.add(rule("todo-without-ticket", "info",
                "TODO или FIXME без ссылки на тикет."));
        return defaults;
    }

    private static RuleYaml rule(String id, String severity, String description) {
        RuleYaml yaml = new RuleYaml();
        yaml.id = id;
        yaml.severity = severity;
        yaml.description = description;
        return yaml;
    }
}
