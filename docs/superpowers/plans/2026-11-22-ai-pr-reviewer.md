# AI PR Reviewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CLI-агент, который по номеру pull request'а на GitHub забирает diff, проверяет его LLM по правилам команды из `.ai-reviewer.yml` и публикует inline-комментарии с summary.

**Architecture:** Чистый pipeline без фреймворков: `Main` → `ConfigLoader` → `GitHubClient` (мета PR + unified diff) → `UnifiedDiffParser` → `ReviewEngine` (параллельно по файлу: `PromptBuilder` → `LlmClient` → `FindingParser`) → `GitHubClient.publish` либо `MarkdownReport` в stdout. Внешние системы спрятаны за тремя интерфейсами: `PullRequestSource`, `ReviewPublisher`, `LlmClient` — поэтому ядро тестируется без сети.

**Tech Stack:** Java 21, Maven, Jackson (databind + dataformat-yaml), `java.net.http`, JUnit 5, maven-shade-plugin.

**Spec:** `docs/superpowers/specs/2026-11-22-ai-pr-reviewer-design.md`

## Global Constraints

- Java 21 (`maven.compiler.release=21`), кодировка UTF-8.
- Зависимости только: `jackson-databind`, `jackson-dataformat-yaml`, `junit-jupiter`. HTTP — встроенный `java.net.http`. Никаких Spring, DI, логгер-библиотек, mock-фреймворков.
- Базовый пакет: `org.example.reviewer`.
- Severity объявляется в порядке `ERROR, WARNING, INFO` — порядок ordinal используется для сравнения порогов.
- Тесты не ходят в сеть. Фейки пишутся руками, без Mockito.
- Проект сейчас **не** git-репозиторий. Шаги «Commit» выполняются только если пользователь попросил `git init` и коммиты; иначе шаг пропускается, а его место занимает прогон `mvn -q test`.
- Секреты только из переменных окружения: `GITHUB_TOKEN`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`. Никогда не логировать их значения.

---

## File Structure

| Файл | Ответственность |
|---|---|
| `pom.xml` | Java 21, зависимости, shade-плагин, main-class |
| `src/main/java/org/example/reviewer/config/Severity.java` | ERROR/WARNING/INFO |
| `src/main/java/org/example/reviewer/config/Rule.java` | одно правило команды |
| `src/main/java/org/example/reviewer/config/ReviewerConfig.java` | конфиг с дефолтами |
| `src/main/java/org/example/reviewer/config/ConfigLoader.java` | чтение YAML |
| `src/main/java/org/example/reviewer/config/ConfigurationException.java` | ошибки конфигурации/доступа |
| `src/main/java/org/example/reviewer/diff/FileStatus.java` | ADDED/MODIFIED/DELETED/RENAMED/BINARY |
| `src/main/java/org/example/reviewer/diff/ChangedFile.java` | путь, статус, patch, номера добавленных строк |
| `src/main/java/org/example/reviewer/diff/UnifiedDiffParser.java` | unified diff → `List<ChangedFile>` |
| `src/main/java/org/example/reviewer/vcs/PullRequest.java` | мета PR |
| `src/main/java/org/example/reviewer/vcs/Finding.java` | одно замечание |
| `src/main/java/org/example/reviewer/vcs/PullRequestSource.java` | интерфейс чтения PR |
| `src/main/java/org/example/reviewer/vcs/ReviewPublisher.java` | интерфейс публикации |
| `src/main/java/org/example/reviewer/vcs/github/GitHubClient.java` | реализация обоих для GitHub |
| `src/main/java/org/example/reviewer/llm/LlmClient.java` | интерфейс LLM |
| `src/main/java/org/example/reviewer/llm/AnthropicClient.java` | Messages API |
| `src/main/java/org/example/reviewer/llm/OpenAiCompatibleClient.java` | chat/completions |
| `src/main/java/org/example/reviewer/llm/LlmClientFactory.java` | выбор по конфигу и env |
| `src/main/java/org/example/reviewer/review/PromptBuilder.java` | системный и пользовательский промпты |
| `src/main/java/org/example/reviewer/review/FindingParser.java` | ответ LLM → findings, с валидацией строк |
| `src/main/java/org/example/reviewer/review/FindingParseException.java` | «ответ не JSON» |
| `src/main/java/org/example/reviewer/review/ReviewResult.java` | findings + ошибки + счётчик файлов |
| `src/main/java/org/example/reviewer/review/ReviewEngine.java` | оркестрация, фильтры, параллельность, ретрай, дедуп |
| `src/main/java/org/example/reviewer/report/MarkdownReport.java` | summary-markdown |
| `src/main/java/org/example/reviewer/cli/Main.java` | аргументы, сборка графа, exit code |
| `.ai-reviewer.yml` | пример конфига в корне |
| `README.md` | как запустить |
| тесты | `src/test/java/org/example/reviewer/...` + фикстуры в `src/test/resources/diffs/` |

---

### Task 1: Каркас сборки

**Files:**
- Modify: `pom.xml`
- Delete: `src/main/java/org/example/App.java`, `src/test/java/org/example/AppTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces: собирающийся Maven-проект на Java 21 с JUnit 5 и shade-плагином; main-class `org.example.reviewer.cli.Main`.

- [ ] **Step 1: Удалить архетипные классы**

```bash
rm src/main/java/org/example/App.java src/test/java/org/example/AppTest.java
```

- [ ] **Step 2: Переписать pom.xml**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.example</groupId>
  <artifactId>AI-reviewer</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>AI-reviewer</name>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <jackson.version>2.18.2</jackson.version>
    <junit.version>5.11.4</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.dataformat</groupId>
      <artifactId>jackson-dataformat-yaml</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <finalName>ai-reviewer</finalName>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>org.example.reviewer.cli.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Проверить, что проект собирается**

Run: `mvn -q test`
Expected: BUILD SUCCESS, тестов нет.

- [ ] **Step 4: Commit** (или `mvn -q test`, если репозиторий не инициализирован)

```bash
git add pom.xml && git commit -m "chore: Java 21, Jackson, JUnit 5, shade"
```

---

### Task 2: Модель и парсер unified diff

**Files:**
- Create: `src/main/java/org/example/reviewer/diff/FileStatus.java`, `ChangedFile.java`, `UnifiedDiffParser.java`
- Test: `src/test/java/org/example/reviewer/diff/UnifiedDiffParserTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces:
  - `enum FileStatus { ADDED, MODIFIED, DELETED, RENAMED, BINARY }`
  - `record ChangedFile(String path, FileStatus status, String patch, List<Integer> addedLines)`
  - `static List<ChangedFile> UnifiedDiffParser.parse(String diff)`

- [ ] **Step 1: Написать падающий тест**

```java
package org.example.reviewer.diff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    @Test
    void parsesModifiedFileWithAddedLineNumbers() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                index 1111111..2222222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -10,3 +10,4 @@ class A {
                     int x = 1;
                +    int y = 2;
                     int z = 3;
                -    int old = 4;
                """;

        List<ChangedFile> files = UnifiedDiffParser.parse(diff);

        assertEquals(1, files.size());
        ChangedFile file = files.get(0);
        assertEquals("src/A.java", file.path());
        assertEquals(FileStatus.MODIFIED, file.status());
        assertEquals(List.of(11), file.addedLines());
        assertTrue(file.patch().contains("@@ -10,3 +10,4 @@"));
    }

    @Test
    void numbersLinesFromEachHunkHeader() {
        String diff = """
                diff --git a/A.txt b/A.txt
                --- a/A.txt
                +++ b/A.txt
                @@ -1,2 +1,3 @@
                 keep
                +added at 2
                 keep
                @@ -40,2 +41,3 @@
                 keep
                +added at 42
                 keep
                """;

        assertEquals(List.of(2, 42), UnifiedDiffParser.parse(diff).get(0).addedLines());
    }

    @Test
    void marksNewFileAsAdded() {
        String diff = """
                diff --git a/New.java b/New.java
                new file mode 100644
                --- /dev/null
                +++ b/New.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.ADDED, file.status());
        assertEquals(List.of(1, 2), file.addedLines());
    }

    @Test
    void marksDeletedFileAndCollectsNoAddedLines() {
        String diff = """
                diff --git a/Gone.java b/Gone.java
                deleted file mode 100644
                --- a/Gone.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -line one
                -line two
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.DELETED, file.status());
        assertTrue(file.addedLines().isEmpty());
    }

    @Test
    void marksBinaryFile() {
        String diff = """
                diff --git a/logo.png b/logo.png
                index 1111111..2222222 100644
                Binary files a/logo.png and b/logo.png differ
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.BINARY, file.status());
        assertTrue(file.addedLines().isEmpty());
    }

    @Test
    void usesNewPathForRenamedFile() {
        String diff = """
                diff --git a/old/Name.java b/new/Name.java
                similarity index 95%
                rename from old/Name.java
                rename to new/Name.java
                --- a/old/Name.java
                +++ b/new/Name.java
                @@ -1,1 +1,2 @@
                 keep
                +added
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.RENAMED, file.status());
        assertEquals("new/Name.java", file.path());
        assertEquals(List.of(2), file.addedLines());
    }

    @Test
    void parsesSeveralFilesInOneDiff() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,1 +1,2 @@
                 keep
                +a
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -1,1 +1,2 @@
                 keep
                +b
                """;

        List<ChangedFile> files = UnifiedDiffParser.parse(diff);
        assertEquals(List.of("A.java", "B.java"), files.stream().map(ChangedFile::path).toList());
    }

    @Test
    void returnsEmptyListForBlankDiff() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty());
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=UnifiedDiffParserTest test`
Expected: FAIL — компиляция не проходит, `FileStatus`/`ChangedFile`/`UnifiedDiffParser` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.diff;

public enum FileStatus {
    ADDED, MODIFIED, DELETED, RENAMED, BINARY
}
```

```java
package org.example.reviewer.diff;

import java.util.List;

/**
 * Один файл из diff'а. {@code addedLines} — номера строк в НОВОЙ версии файла,
 * только для добавленных строк: именно к ним GitHub разрешает привязать inline-комментарий.
 */
public record ChangedFile(String path, FileStatus status, String patch, List<Integer> addedLines) {

    public boolean reviewable() {
        return status != FileStatus.DELETED && status != FileStatus.BINARY && !addedLines.isEmpty();
    }
}
```

```java
package org.example.reviewer.reviewer_placeholder;
```

Настоящий парсер:

```java
package org.example.reviewer.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбирает unified diff (вывод GitHub `Accept: application/vnd.github.v3.diff`). */
public final class UnifiedDiffParser {

    private static final Pattern HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private UnifiedDiffParser() {
    }

    public static List<ChangedFile> parse(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return files;
        }

        Builder current = null;
        int newLineNo = 0;

        for (String line : diff.split("\n", -1)) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new Builder(header.group(2));
                newLineNo = 0;
                continue;
            }
            if (current == null) {
                continue;
            }

            if (line.startsWith("new file mode")) {
                current.status = FileStatus.ADDED;
            } else if (line.startsWith("deleted file mode")) {
                current.status = FileStatus.DELETED;
            } else if (line.startsWith("rename to ")) {
                current.status = FileStatus.RENAMED;
                current.path = line.substring("rename to ".length()).trim();
            } else if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                current.status = FileStatus.BINARY;
            } else if (line.startsWith("index ") || line.startsWith("similarity index ")
                    || line.startsWith("rename from ") || line.startsWith("old mode")
                    || line.startsWith("new mode") || line.startsWith("--- ") || line.startsWith("+++ ")) {
                // служебные строки в промпт не нужны
            } else {
                Matcher hunk = HUNK.matcher(line);
                if (hunk.matches()) {
                    newLineNo = Integer.parseInt(hunk.group(1));
                    current.patch.add(line);
                } else if (line.startsWith("+")) {
                    current.addedLines.add(newLineNo++);
                    current.patch.add(line);
                } else if (line.startsWith("-")) {
                    current.patch.add(line);
                } else if (line.startsWith(" ")) {
                    newLineNo++;
                    current.patch.add(line);
                } else if (line.startsWith("\\")) {
                    current.patch.add(line);
                }
            }
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    private static final class Builder {
        private String path;
        private FileStatus status = FileStatus.MODIFIED;
        private final List<String> patch = new ArrayList<>();
        private final List<Integer> addedLines = new ArrayList<>();

        private Builder(String path) {
            this.path = path;
        }

        private ChangedFile build() {
            return new ChangedFile(path, status, String.join("\n", patch), List.copyOf(addedLines));
        }
    }
}
```

> Строка `package org.example.reviewer.reviewer_placeholder;` в Step 3 — опечатка плана; файл с таким содержимым не создаётся. Создаются ровно три файла: `FileStatus.java`, `ChangedFile.java`, `UnifiedDiffParser.java`.

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=UnifiedDiffParserTest test`
Expected: PASS, 8 тестов.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/reviewer/diff src/test/java/org/example/reviewer/diff
git commit -m "feat: unified diff parser with new-side line numbers"
```

---

### Task 3: Конфигурация

**Files:**
- Create: `src/main/java/org/example/reviewer/config/Severity.java`, `Rule.java`, `ReviewerConfig.java`, `ConfigLoader.java`, `ConfigurationException.java`
- Create: `.ai-reviewer.yml`
- Test: `src/test/java/org/example/reviewer/config/ConfigLoaderTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces:
  - `enum Severity { ERROR, WARNING, INFO }` + `static Severity Severity.parse(String raw)` (регистронезависимо, неизвестное → `INFO`)
  - `record Rule(String id, Severity severity, String description)`
  - `class ReviewerConfig` с геттерами `provider()`, `model()`, `maxFiles()`, `maxFindingsPerFile()`, `failOn()`, `exclude()`, `rules()`
  - `static ReviewerConfig ConfigLoader.load(Path path)` — нет файла → дефолты
  - `class ConfigurationException extends RuntimeException`

- [ ] **Step 1: Написать падающий тест**

```java
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
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: FAIL — классы `config` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.config;

/** Порядок важен: ordinal используется как порог строгости (ERROR самый строгий). */
public enum Severity {
    ERROR, WARNING, INFO;

    public static Severity parse(String raw) {
        if (raw == null) {
            return INFO;
        }
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(raw.trim())) {
                return severity;
            }
        }
        return INFO;
    }
}
```

```java
package org.example.reviewer.config;

public record Rule(String id, Severity severity, String description) {
}
```

```java
package org.example.reviewer.config;

public class ConfigurationException extends RuntimeException {
    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
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
```

```java
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
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: PASS, 4 теста.

- [ ] **Step 5: Положить пример конфига в корень**

`.ai-reviewer.yml`:

```yaml
# Правила ревью команды. Агент читает этот файл на каждом запуске —
# чтобы добавить новое повторяющееся замечание, пересобирать ничего не нужно.
provider: anthropic
model: claude-sonnet-5-5
maxFiles: 25
maxFindingsPerFile: 5
failOn: error        # error | warning | never

exclude:
  - "**/target/**"
  - "**/*.lock"
  - "**/generated/**"

rules:
  - id: npe-risk
    severity: error
    description: Разыменование значения, которое может быть null.
  - id: swallowed-exception
    severity: error
    description: Пустой catch или catch без логирования и без повторного выброса.
  - id: no-sout
    severity: warning
    description: Отладочный вывод через System.out вместо логгера.
  - id: missing-test
    severity: warning
    description: Нетривиальная логика без тестов в этом же PR.
  - id: todo-without-ticket
    severity: info
    description: TODO или FIXME без ссылки на тикет.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/reviewer/config src/test/java/org/example/reviewer/config .ai-reviewer.yml
git commit -m "feat: YAML config with team rules and sane defaults"
```

---

### Task 4: Модель замечания и парсер ответа LLM

**Files:**
- Create: `src/main/java/org/example/reviewer/vcs/Finding.java`
- Create: `src/main/java/org/example/reviewer/review/FindingParser.java`, `FindingParseException.java`
- Test: `src/test/java/org/example/reviewer/review/FindingParserTest.java`

**Interfaces:**
- Consumes: `Severity`, `ChangedFile`.
- Produces:
  - `record Finding(String file, int line, Severity severity, String ruleId, String message, String suggestion)`
  - `static List<Finding> FindingParser.parse(String llmResponse, ChangedFile file, int maxFindings)` — бросает `FindingParseException`, если JSON-массив не найден
  - `class FindingParseException extends RuntimeException`

- [ ] **Step 1: Написать падающий тест**

```java
package org.example.reviewer.review;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.reviewer.config.Severity;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.diff.FileStatus;
import org.example.reviewer.vcs.Finding;
import org.junit.jupiter.api.Test;

class FindingParserTest {

    private final ChangedFile file =
            new ChangedFile("src/A.java", FileStatus.MODIFIED, "@@ -1,1 +1,3 @@", List.of(11, 12));

    @Test
    void parsesPlainJsonArray() {
        String response = """
                [{"line": 11, "severity": "error", "ruleId": "npe-risk",
                  "message": "Возможен NPE", "suggestion": "Проверить на null"}]
                """;

        List<Finding> findings = FindingParser.parse(response, file, 5);

        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals("src/A.java", finding.file());
        assertEquals(11, finding.line());
        assertEquals(Severity.ERROR, finding.severity());
        assertEquals("npe-risk", finding.ruleId());
        assertEquals("Возможен NPE", finding.message());
        assertEquals("Проверить на null", finding.suggestion());
    }

    @Test
    void parsesJsonWrappedInMarkdownFenceAndProse() {
        String response = """
                Вот что я нашёл:
                ```json
                [{"line": 12, "severity": "warning", "ruleId": "no-sout", "message": "System.out"}]
                ```
                Больше замечаний нет.
                """;

        assertEquals(1, FindingParser.parse(response, file, 5).size());
    }

    @Test
    void dropsFindingsOnLinesOutsideTheDiff() {
        String response = """
                [{"line": 999, "severity": "error", "ruleId": "npe-risk", "message": "вне diff"},
                 {"line": 11, "severity": "error", "ruleId": "npe-risk", "message": "в diff"}]
                """;

        List<Finding> findings = FindingParser.parse(response, file, 5);

        assertEquals(1, findings.size());
        assertEquals(11, findings.get(0).line());
    }

    @Test
    void dropsFindingsWithBlankMessage() {
        String response = """
                [{"line": 11, "severity": "error", "ruleId": "npe-risk", "message": "   "}]
                """;

        assertTrue(FindingParser.parse(response, file, 5).isEmpty());
    }

    @Test
    void fallsBackToInfoForUnknownSeverity() {
        String response = """
                [{"line": 11, "severity": "catastrophic", "ruleId": "x", "message": "что-то"}]
                """;

        assertEquals(Severity.INFO, FindingParser.parse(response, file, 5).get(0).severity());
    }

    @Test
    void truncatesToMaxFindings() {
        String response = """
                [{"line": 11, "severity": "info", "ruleId": "a", "message": "один"},
                 {"line": 12, "severity": "info", "ruleId": "b", "message": "два"}]
                """;

        assertEquals(1, FindingParser.parse(response, file, 1).size());
    }

    @Test
    void acceptsEmptyArrayAsNoFindings() {
        assertTrue(FindingParser.parse("[]", file, 5).isEmpty());
    }

    @Test
    void throwsWhenResponseHasNoJsonArray() {
        assertThrows(FindingParseException.class,
                () -> FindingParser.parse("Я не нашёл проблем, всё хорошо!", file, 5));
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=FindingParserTest test`
Expected: FAIL — `Finding`, `FindingParser`, `FindingParseException` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.vcs;

import org.example.reviewer.config.Severity;

public record Finding(String file, int line, Severity severity, String ruleId, String message, String suggestion) {
}
```

```java
package org.example.reviewer.review;

public class FindingParseException extends RuntimeException {
    public FindingParseException(String message) {
        super(message);
    }
}
```

```java
package org.example.reviewer.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.reviewer.config.Severity;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.vcs.Finding;

/**
 * Превращает ответ модели в замечания и отсекает шум.
 * Главная проверка — строка должна реально присутствовать среди добавленных строк diff'а:
 * без неё модель регулярно комментирует строки, которых в PR нет.
 */
public final class FindingParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FindingParser() {
    }

    public static List<Finding> parse(String llmResponse, ChangedFile file, int maxFindings) {
        String array = extractJsonArray(llmResponse);
        JsonNode root;
        try {
            root = JSON.readTree(array);
        } catch (Exception e) {
            throw new FindingParseException("Ответ модели не разбирается как JSON: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new FindingParseException("Ожидался JSON-массив, получено: " + root.getNodeType());
        }

        Set<Integer> allowed = new HashSet<>(file.addedLines());
        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : root) {
            if (findings.size() >= maxFindings) {
                break;
            }
            int line = node.path("line").asInt(-1);
            String message = node.path("message").asText("");
            if (!allowed.contains(line) || message.isBlank()) {
                continue;
            }
            findings.add(new Finding(
                    file.path(),
                    line,
                    Severity.parse(node.path("severity").asText(null)),
                    node.path("ruleId").asText(""),
                    message.trim(),
                    node.path("suggestion").asText("")));
        }
        return findings;
    }

    private static String extractJsonArray(String response) {
        if (response == null) {
            throw new FindingParseException("Пустой ответ модели");
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new FindingParseException("В ответе модели нет JSON-массива");
        }
        return response.substring(start, end + 1);
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=FindingParserTest test`
Expected: PASS, 8 тестов.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/reviewer/vcs/Finding.java src/main/java/org/example/reviewer/review src/test/java/org/example/reviewer/review
git commit -m "feat: parse LLM findings and drop hallucinated line numbers"
```

---

### Task 5: Промпты

**Files:**
- Create: `src/main/java/org/example/reviewer/vcs/PullRequest.java`
- Create: `src/main/java/org/example/reviewer/review/PromptBuilder.java`
- Test: `src/test/java/org/example/reviewer/review/PromptBuilderTest.java`

**Interfaces:**
- Consumes: `ReviewerConfig`, `ChangedFile`.
- Produces:
  - `record PullRequest(String owner, String repo, int number, String title, String description, String headSha)`
  - `static String PromptBuilder.system(ReviewerConfig config)`
  - `static String PromptBuilder.user(PullRequest pr, ChangedFile file)`
  - `static String PromptBuilder.retryHint()`

- [ ] **Step 1: Написать падающий тест**

```java
package org.example.reviewer.review;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.diff.FileStatus;
import org.example.reviewer.vcs.PullRequest;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    @Test
    void systemPromptListsEveryRuleWithIdAndSeverity() {
        String prompt = PromptBuilder.system(new ReviewerConfig());

        assertTrue(prompt.contains("npe-risk"), "правило должно попасть в промпт по id");
        assertTrue(prompt.contains("ERROR"));
        assertTrue(prompt.contains("Отладочный вывод"), "описание правила должно попасть в промпт");
    }

    @Test
    void systemPromptDemandsJsonArrayAndForbidsLinesOutsideDiff() {
        String prompt = PromptBuilder.system(new ReviewerConfig());

        assertTrue(prompt.contains("JSON"));
        assertTrue(prompt.contains("ruleId"));
        assertTrue(prompt.contains("[]"), "модель должна знать, как сказать «замечаний нет»");
    }

    @Test
    void userPromptCarriesPullRequestContextAndThePatch() {
        PullRequest pr = new PullRequest("acme", "api", 42, "Добавил кэш", "Описание PR", "abc123");
        ChangedFile file = new ChangedFile("src/Cache.java", FileStatus.MODIFIED,
                "@@ -1,1 +1,2 @@\n+    int x = 1;", List.of(2));

        String prompt = PromptBuilder.user(pr, file);

        assertTrue(prompt.contains("Добавил кэш"));
        assertTrue(prompt.contains("Описание PR"));
        assertTrue(prompt.contains("src/Cache.java"));
        assertTrue(prompt.contains("int x = 1;"));
        assertTrue(prompt.contains("2"), "номера строк, к которым можно привязаться");
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=PromptBuilderTest test`
Expected: FAIL — `PullRequest` и `PromptBuilder` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.vcs;

public record PullRequest(String owner, String repo, int number, String title, String description, String headSha) {

    public String slug() {
        return owner + "/" + repo + "#" + number;
    }
}
```

```java
package org.example.reviewer.review;

import java.util.StringJoiner;
import org.example.reviewer.config.Rule;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.vcs.PullRequest;

public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String system(ReviewerConfig config) {
        StringJoiner rules = new StringJoiner("\n");
        for (Rule rule : config.rules()) {
            rules.add("- id=%s severity=%s: %s".formatted(rule.id(), rule.severity(), rule.description()));
        }

        return """
                Ты — ревьюер кода в команде. Тебе дают diff одного файла из pull request'а.
                Твоя работа — найти нарушения правил команды, перечисленных ниже, и ничего больше.

                ПРАВИЛА КОМАНДЫ:
                %s

                ЖЁСТКИЕ ОГРАНИЧЕНИЯ:
                1. Комментируй ТОЛЬКО строки, помеченные в diff как добавленные, и указывай их номер
                   из заголовка хунка. Замечание на любую другую строку будет отброшено.
                2. Одно замечание — одно конкретное нарушение одного правила. Без общих рассуждений,
                   без похвалы, без пересказа кода.
                3. Если нарушение не очевидно из diff'а — молчи. Ложное замечание дороже пропущенного.
                4. Не придирайся к форматированию, именам и стилю, если на это нет правила выше.

                ФОРМАТ ОТВЕТА: только JSON-массив, без текста до и после, без markdown-ограждений.
                Схема элемента:
                  {"line": <номер добавленной строки>, "severity": "error|warning|info",
                   "ruleId": "<id правила из списка>", "message": "<что не так, 1-2 предложения>",
                   "suggestion": "<как починить, можно пустую строку>"}
                Если замечаний нет, ответь ровно: []
                """.formatted(rules.toString());
    }

    public static String user(PullRequest pr, ChangedFile file) {
        return """
                Pull request: %s
                Заголовок: %s
                Описание: %s

                Файл: %s (%s)
                Номера строк, к которым разрешено привязывать замечания: %s

                Diff файла:
                %s
                """.formatted(
                pr.slug(),
                blankToDash(pr.title()),
                blankToDash(pr.description()),
                file.path(),
                file.status(),
                file.addedLines(),
                file.patch());
    }

    /** Подсказка для единственного ретрая, когда модель ответила не JSON'ом. */
    public static String retryHint() {
        return "\n\nПредыдущий ответ не был разобран. Ответь ТОЛЬКО JSON-массивом по схеме выше. "
                + "Если замечаний нет — ответь ровно [].";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=PromptBuilderTest test`
Expected: PASS, 3 теста.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/reviewer/vcs/PullRequest.java src/main/java/org/example/reviewer/review/PromptBuilder.java src/test/java/org/example/reviewer/review/PromptBuilderTest.java
git commit -m "feat: review prompts built from team rules"
```

---

### Task 6: Движок ревью

**Files:**
- Create: `src/main/java/org/example/reviewer/llm/LlmClient.java`
- Create: `src/main/java/org/example/reviewer/review/ReviewResult.java`, `ReviewEngine.java`
- Test: `src/test/java/org/example/reviewer/review/ReviewEngineTest.java`

**Interfaces:**
- Consumes: `LlmClient`, `ReviewerConfig`, `PullRequest`, `ChangedFile`, `FindingParser`, `PromptBuilder`.
- Produces:
  - `interface LlmClient { String complete(String systemPrompt, String userPrompt); }`
  - `record ReviewResult(List<Finding> findings, List<String> errors, int filesReviewed, int filesSkipped)`
  - `class ReviewEngine` с конструктором `ReviewEngine(LlmClient llm, ReviewerConfig config)` и методом `ReviewResult review(PullRequest pr, List<ChangedFile> files)`

- [ ] **Step 1: Написать падающий тест**

```java
package org.example.reviewer.review;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.diff.FileStatus;
import org.example.reviewer.llm.LlmClient;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;
import org.junit.jupiter.api.Test;

class ReviewEngineTest {

    private static final PullRequest PR = new PullRequest("acme", "api", 7, "T", "D", "sha");

    /** Фейк: отдаёт заранее заданный ответ по имени файла, встреченному в user-промпте. */
    private static final class FakeLlm implements LlmClient {
        private final Map<String, List<String>> responsesByFile;
        private final Map<String, Integer> calls = new ConcurrentHashMap<>();

        FakeLlm(Map<String, List<String>> responsesByFile) {
            this.responsesByFile = responsesByFile;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            for (var entry : responsesByFile.entrySet()) {
                if (userPrompt.contains(entry.getKey())) {
                    int index = calls.merge(entry.getKey(), 1, Integer::sum) - 1;
                    List<String> responses = entry.getValue();
                    String response = responses.get(Math.min(index, responses.size() - 1));
                    if (response == null) {
                        throw new IllegalStateException("LLM недоступна");
                    }
                    return response;
                }
            }
            return "[]";
        }

        int callsFor(String file) {
            return calls.getOrDefault(file, 0);
        }
    }

    private static ChangedFile file(String path, FileStatus status, int... addedLines) {
        List<Integer> lines = java.util.Arrays.stream(addedLines).boxed().toList();
        return new ChangedFile(path, status, "@@ -1,1 +1,9 @@", lines);
    }

    private static String finding(int line, String ruleId) {
        return "[{\"line\": %d, \"severity\": \"warning\", \"ruleId\": \"%s\", \"message\": \"замечание\"}]"
                .formatted(line, ruleId);
    }

    @Test
    void mergesFindingsFromAllFiles() {
        FakeLlm llm = new FakeLlm(Map.of(
                "src/A.java", List.of(finding(1, "no-sout")),
                "src/B.java", List.of(finding(2, "npe-risk"))));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig()).review(PR, List.of(
                file("src/A.java", FileStatus.MODIFIED, 1),
                file("src/B.java", FileStatus.MODIFIED, 2)));

        assertEquals(2, result.findings().size());
        assertEquals(2, result.filesReviewed());
        assertTrue(result.errors().isEmpty());
        assertEquals(List.of("src/A.java", "src/B.java"),
                result.findings().stream().map(Finding::file).toList(),
                "замечания должны быть отсортированы по файлу и строке");
    }

    @Test
    void skipsDeletedBinaryAndUnchangedFiles() {
        FakeLlm llm = new FakeLlm(Map.of("src/A.java", List.of(finding(1, "no-sout"))));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig()).review(PR, List.of(
                file("src/A.java", FileStatus.MODIFIED, 1),
                file("src/Gone.java", FileStatus.DELETED),
                file("logo.png", FileStatus.BINARY)));

        assertEquals(1, result.filesReviewed());
        assertEquals(2, result.filesSkipped());
        assertEquals(0, llm.callsFor("src/Gone.java"));
    }

    @Test
    void skipsExcludedPaths() {
        FakeLlm llm = new FakeLlm(Map.of("target/Generated.java", List.of(finding(1, "no-sout"))));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig())
                .review(PR, List.of(file("target/Generated.java", FileStatus.MODIFIED, 1)));

        assertEquals(0, result.filesReviewed());
        assertTrue(result.findings().isEmpty());
        assertEquals(0, llm.callsFor("target/Generated.java"));
    }

    @Test
    void retriesOnceWhenModelDoesNotAnswerWithJson() {
        FakeLlm llm = new FakeLlm(Map.of(
                "src/A.java", List.of("Всё выглядит отлично!", finding(1, "no-sout"))));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig())
                .review(PR, List.of(file("src/A.java", FileStatus.MODIFIED, 1)));

        assertEquals(1, result.findings().size());
        assertEquals(2, llm.callsFor("src/A.java"));
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void recordsErrorForOneFileAndStillReviewsTheRest() {
        FakeLlm llm = new FakeLlm(Map.of(
                "src/Bad.java", List.of((String) null),
                "src/Good.java", List.of(finding(1, "no-sout"))));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig()).review(PR, List.of(
                file("src/Bad.java", FileStatus.MODIFIED, 1),
                file("src/Good.java", FileStatus.MODIFIED, 1)));

        assertEquals(1, result.findings().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("src/Bad.java"));
    }

    @Test
    void deduplicatesIdenticalFindings() {
        String duplicated = """
                [{"line": 1, "severity": "warning", "ruleId": "no-sout", "message": "раз"},
                 {"line": 1, "severity": "warning", "ruleId": "no-sout", "message": "два"}]
                """;
        FakeLlm llm = new FakeLlm(Map.of("src/A.java", List.of(duplicated)));

        ReviewResult result = new ReviewEngine(llm, new ReviewerConfig())
                .review(PR, List.of(file("src/A.java", FileStatus.MODIFIED, 1)));

        assertEquals(1, result.findings().size());
    }

    @Test
    void honoursMaxFilesLimit() {
        ReviewerConfig config = org.example.reviewer.config.ConfigLoader.load(writeMaxFilesConfig());
        FakeLlm llm = new FakeLlm(Map.of());

        ReviewResult result = new ReviewEngine(llm, config).review(PR, List.of(
                file("src/A.java", FileStatus.MODIFIED, 1),
                file("src/B.java", FileStatus.MODIFIED, 1),
                file("src/C.java", FileStatus.MODIFIED, 1)));

        assertEquals(1, result.filesReviewed());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("maxFiles")),
                "усечение списка файлов должно быть видно в отчёте, а не молча");
    }

    private static java.nio.file.Path writeMaxFilesConfig() {
        try {
            java.nio.file.Path path = java.nio.file.Files.createTempFile("cfg", ".yml");
            java.nio.file.Files.writeString(path, "maxFiles: 1\n");
            return path;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=ReviewEngineTest test`
Expected: FAIL — `LlmClient`, `ReviewResult`, `ReviewEngine` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.llm;

/** Единственная точка общения с моделью. Реализации: Anthropic, OpenAI-совместимая, фейк в тестах. */
public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
```

```java
package org.example.reviewer.review;

import java.util.List;
import org.example.reviewer.config.Severity;
import org.example.reviewer.vcs.Finding;

public record ReviewResult(List<Finding> findings, List<String> errors, int filesReviewed, int filesSkipped) {

    public boolean hasAtLeast(Severity threshold) {
        return findings.stream().anyMatch(f -> f.severity().ordinal() <= threshold.ordinal());
    }

    public long countOf(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }
}
```

```java
package org.example.reviewer.review;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.llm.LlmClient;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;

/**
 * Ревьюит PR по одному файлу за вызов, файлы — параллельно.
 * Пофайловый вызов выбран вместо одного вызова на весь PR: устойчиво к размеру diff'а,
 * точнее привязка к строкам, и упавший файл не уносит с собой всё ревью.
 */
public final class ReviewEngine {

    private final LlmClient llm;
    private final ReviewerConfig config;

    public ReviewEngine(LlmClient llm, ReviewerConfig config) {
        this.llm = llm;
        this.config = config;
    }

    public ReviewResult review(PullRequest pr, List<ChangedFile> files) {
        List<String> errors = new CopyOnWriteArrayList<>();
        List<PathMatcher> excludes = config.exclude().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();

        List<ChangedFile> candidates = files.stream()
                .filter(ChangedFile::reviewable)
                .filter(file -> !excluded(file, excludes))
                .toList();
        int skipped = files.size() - candidates.size();

        List<ChangedFile> toReview = candidates;
        if (candidates.size() > config.maxFiles()) {
            errors.add("В PR %d подходящих файлов, проверены первые %d (ограничение maxFiles)."
                    .formatted(candidates.size(), config.maxFiles()));
            toReview = candidates.subList(0, config.maxFiles());
        }

        String systemPrompt = org.example.reviewer.review.PromptBuilder.system(config);
        List<Finding> collected = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (ChangedFile file : toReview) {
                futures.add(pool.submit(() -> {
                    try {
                        collected.addAll(reviewFile(pr, file, systemPrompt));
                    } catch (RuntimeException e) {
                        errors.add("Файл %s не проверен: %s".formatted(file.path(), e.getMessage()));
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    errors.add("Сбой задачи ревью: " + e.getMessage());
                }
            }
        }

        return new ReviewResult(dedupe(collected), List.copyOf(errors), toReview.size(), skipped);
    }

    private List<Finding> reviewFile(PullRequest pr, ChangedFile file, String systemPrompt) {
        String userPrompt = PromptBuilder.user(pr, file);
        try {
            return FindingParser.parse(llm.complete(systemPrompt, userPrompt), file, config.maxFindingsPerFile());
        } catch (FindingParseException first) {
            String retried = llm.complete(systemPrompt, userPrompt + PromptBuilder.retryHint());
            return FindingParser.parse(retried, file, config.maxFindingsPerFile());
        }
    }

    private static boolean excluded(ChangedFile file, List<PathMatcher> excludes) {
        Path path = Path.of(file.path());
        return excludes.stream().anyMatch(matcher -> matcher.matches(path));
    }

    /** Одна строка + одно правило = одно замечание: модель любит повторяться. */
    private static List<Finding> dedupe(List<Finding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> unique = new ArrayList<>();
        for (Finding finding : findings) {
            if (seen.add(finding.file() + ":" + finding.line() + ":" + finding.ruleId())) {
                unique.add(finding);
            }
        }
        unique.sort(Comparator.comparing(Finding::file).thenComparingInt(Finding::line));
        return List.copyOf(unique);
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=ReviewEngineTest test`
Expected: PASS, 7 тестов.

- [ ] **Step 5: Прогнать весь набор**

Run: `mvn -q test`
Expected: PASS, все тесты зелёные.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/reviewer/llm/LlmClient.java src/main/java/org/example/reviewer/review src/test/java/org/example/reviewer/review
git commit -m "feat: per-file parallel review engine with retry and dedupe"
```

---

### Task 7: Клиенты LLM

**Files:**
- Create: `src/main/java/org/example/reviewer/llm/AnthropicClient.java`, `OpenAiCompatibleClient.java`, `LlmClientFactory.java`
- Test: `src/test/java/org/example/reviewer/llm/LlmClientFactoryTest.java`

**Interfaces:**
- Consumes: `LlmClient`, `ReviewerConfig`, `ConfigurationException`.
- Produces: `static LlmClient LlmClientFactory.create(ReviewerConfig config, Map<String,String> env)` — env передаётся параметром, чтобы фабрика была тестируемой без мутации окружения процесса.

- [ ] **Step 1: Написать падающий тест**

```java
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
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=LlmClientFactoryTest test`
Expected: FAIL — клиентов и фабрики нет.

- [ ] **Step 3: Минимальная реализация**

```java
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
```

```java
package org.example.reviewer.llm;

public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }
}
```

```java
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
```

```java
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
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=LlmClientFactoryTest test`
Expected: PASS, 4 теста.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/reviewer/llm src/test/java/org/example/reviewer/llm
git commit -m "feat: Anthropic and OpenAI-compatible LLM clients behind a factory"
```

---

### Task 8: Клиент GitHub

**Files:**
- Create: `src/main/java/org/example/reviewer/vcs/PullRequestSource.java`, `ReviewPublisher.java`, `vcs/github/GitHubClient.java`
- Test: `src/test/java/org/example/reviewer/vcs/github/GitHubClientTest.java`

**Interfaces:**
- Consumes: `PullRequest`, `Finding`, `ConfigurationException`.
- Produces:
  - `interface PullRequestSource { PullRequest fetchPullRequest(String owner, String repo, int number); String fetchDiff(String owner, String repo, int number); }`
  - `interface ReviewPublisher { void publish(PullRequest pr, List<Finding> findings, String summary); }`
  - `class GitHubClient implements PullRequestSource, ReviewPublisher` с конструктором `GitHubClient(String token, String apiBaseUrl)` и package-private `static String GitHubClient.reviewBody(List<Finding> findings, String summary)` для теста формата запроса.

- [ ] **Step 1: Написать падающий тест**

Сеть не трогаем — проверяем единственную нетривиальную часть: сборку тела review-запроса.

```java
package org.example.reviewer.vcs.github;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.example.reviewer.config.Severity;
import org.example.reviewer.vcs.Finding;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsReviewWithInlineCommentsOnTheNewSide() throws Exception {
        List<Finding> findings = List.of(
                new Finding("src/A.java", 11, Severity.ERROR, "npe-risk", "Возможен NPE", "Проверить null"));

        JsonNode body = json.readTree(GitHubClient.reviewBody(findings, "Итог ревью"));

        assertEquals("COMMENT", body.get("event").asText(), "агент комментирует, но не блокирует merge сам");
        assertEquals("Итог ревью", body.get("body").asText());
        JsonNode comment = body.get("comments").get(0);
        assertEquals("src/A.java", comment.get("path").asText());
        assertEquals(11, comment.get("line").asInt());
        assertEquals("RIGHT", comment.get("side").asText());
        assertTrue(comment.get("body").asText().contains("npe-risk"));
        assertTrue(comment.get("body").asText().contains("Возможен NPE"));
        assertTrue(comment.get("body").asText().contains("Проверить null"));
    }

    @Test
    void omitsCommentsArrayWhenThereAreNoFindings() throws Exception {
        JsonNode body = json.readTree(GitHubClient.reviewBody(List.of(), "Замечаний нет"));

        assertEquals("Замечаний нет", body.get("body").asText());
        assertTrue(body.get("comments").isEmpty());
    }

    @Test
    void skipsSuggestionSectionWhenEmpty() throws Exception {
        List<Finding> findings = List.of(
                new Finding("src/A.java", 5, Severity.INFO, "todo-without-ticket", "TODO без тикета", ""));

        JsonNode body = json.readTree(GitHubClient.reviewBody(findings, "s"));

        assertFalse(body.get("comments").get(0).get("body").asText().contains("Предложение"));
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest=GitHubClientTest test`
Expected: FAIL — `GitHubClient` не существует.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.vcs;

public interface PullRequestSource {
    PullRequest fetchPullRequest(String owner, String repo, int number);

    String fetchDiff(String owner, String repo, int number);
}
```

```java
package org.example.reviewer.vcs;

import java.util.List;

public interface ReviewPublisher {
    void publish(PullRequest pr, List<Finding> findings, String summary);
}
```

```java
package org.example.reviewer.vcs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.example.reviewer.config.ConfigurationException;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;
import org.example.reviewer.vcs.PullRequestSource;
import org.example.reviewer.vcs.ReviewPublisher;

public final class GitHubClient implements PullRequestSource, ReviewPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String token;
    private final String apiBaseUrl;

    public GitHubClient(String token, String apiBaseUrl) {
        this.token = token;
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.github.com" : apiBaseUrl;
    }

    @Override
    public PullRequest fetchPullRequest(String owner, String repo, int number) {
        JsonNode node = JSON_PARSE(get(prUrl(owner, repo, number), "application/vnd.github+json"));
        return new PullRequest(
                owner, repo, number,
                node.path("title").asText(""),
                node.path("body").asText(""),
                node.path("head").path("sha").asText(""));
    }

    @Override
    public String fetchDiff(String owner, String repo, int number) {
        return get(prUrl(owner, repo, number), "application/vnd.github.v3.diff");
    }

    @Override
    public void publish(PullRequest pr, List<Finding> findings, String summary) {
        post(prUrl(pr.owner(), pr.repo(), pr.number()) + "/reviews", reviewBody(findings, summary));
    }

    /** Package-private: формат тела — единственная часть клиента, которую стоит закрепить тестом. */
    static String reviewBody(List<Finding> findings, String summary) {
        ObjectNode body = JSON.createObjectNode();
        body.put("event", "COMMENT");
        body.put("body", summary);
        ArrayNode comments = body.putArray("comments");
        for (Finding finding : findings) {
            ObjectNode comment = comments.addObject();
            comment.put("path", finding.file());
            comment.put("line", finding.line());
            comment.put("side", "RIGHT");
            comment.put("body", commentBody(finding));
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать ревью", e);
        }
    }

    private static String commentBody(Finding finding) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(finding.severity()).append("**");
        if (!finding.ruleId().isBlank()) {
            text.append(" · `").append(finding.ruleId()).append('`');
        }
        text.append("\n\n").append(finding.message());
        if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
            text.append("\n\n_Предложение:_ ").append(finding.suggestion());
        }
        return text.toString();
    }

    private String prUrl(String owner, String repo, int number) {
        return "%s/repos/%s/%s/pulls/%d".formatted(apiBaseUrl, owner, repo, number);
    }

    private String get(String url, String accept) {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("accept", accept)
                .GET()
                .build();
        return send(request);
    }

    private String post(String url, String jsonBody) {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("accept", "application/vnd.github+json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(request);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder
                .header("authorization", "Bearer " + token)
                .header("x-github-api-version", "2022-11-28")
                .header("user-agent", "ai-reviewer")
                .timeout(Duration.ofSeconds(60));
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConfigurationException("GitHub вернул %d на %s: %s"
                        .formatted(response.statusCode(), request.uri().getPath(), response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigurationException("Запрос к GitHub прерван");
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigurationException("Запрос к GitHub не удался: " + e.getMessage(), e);
        }
    }

    private static JsonNode JSON_PARSE(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new ConfigurationException("Неожиданный ответ GitHub: " + e.getMessage(), e);
        }
    }
}
```

> Имя `JSON_PARSE` нарушает соглашения Java. При реализации назвать метод `parseJson` и так же вызвать его в `fetchPullRequest`.

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q -Dtest=GitHubClientTest test`
Expected: PASS, 3 теста.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/reviewer/vcs src/test/java/org/example/reviewer/vcs
git commit -m "feat: GitHub client for PR diff and review publishing"
```

---

### Task 9: Отчёт, CLI и запуск

**Files:**
- Create: `src/main/java/org/example/reviewer/report/MarkdownReport.java`, `src/main/java/org/example/reviewer/cli/Main.java`, `README.md`
- Test: `src/test/java/org/example/reviewer/report/MarkdownReportTest.java`, `src/test/java/org/example/reviewer/cli/MainExitCodeTest.java`

**Interfaces:**
- Consumes: всё предыдущее.
- Produces:
  - `static String MarkdownReport.summary(PullRequest pr, ReviewResult result)`
  - `static String MarkdownReport.console(PullRequest pr, ReviewResult result)`
  - `static int Main.exitCode(ReviewResult result, String failOn)`
  - исполняемый `target/ai-reviewer.jar`

- [ ] **Step 1: Написать падающие тесты**

```java
package org.example.reviewer.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.reviewer.config.Severity;
import org.example.reviewer.review.ReviewResult;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;
import org.junit.jupiter.api.Test;

class MarkdownReportTest {

    private static final PullRequest PR = new PullRequest("acme", "api", 7, "T", "D", "sha");

    @Test
    void summaryCountsFindingsBySeverityAndNamesFilesReviewed() {
        ReviewResult result = new ReviewResult(List.of(
                new Finding("A.java", 1, Severity.ERROR, "npe-risk", "m", ""),
                new Finding("B.java", 2, Severity.WARNING, "no-sout", "m", "")),
                List.of(), 2, 1);

        String summary = MarkdownReport.summary(PR, result);

        assertTrue(summary.contains("2"), "общее число замечаний");
        assertTrue(summary.contains("ERROR"));
        assertTrue(summary.contains("WARNING"));
        assertTrue(summary.contains("проверено файлов: 2"));
    }

    @Test
    void summarySaysSoWhenNothingFound() {
        String summary = MarkdownReport.summary(PR, new ReviewResult(List.of(), List.of(), 3, 0));

        assertTrue(summary.contains("Замечаний нет"));
    }

    @Test
    void summaryShowsErrorsHonestly() {
        ReviewResult result = new ReviewResult(List.of(), List.of("Файл X не проверен: таймаут"), 1, 0);

        assertTrue(MarkdownReport.summary(PR, result).contains("таймаут"));
    }

    @Test
    void consoleOutputListsEachFindingWithFileAndLine() {
        ReviewResult result = new ReviewResult(List.of(
                new Finding("src/A.java", 11, Severity.ERROR, "npe-risk", "Возможен NPE", "Проверить")),
                List.of(), 1, 0);

        String console = MarkdownReport.console(PR, result);

        assertTrue(console.contains("src/A.java:11"));
        assertTrue(console.contains("Возможен NPE"));
    }
}
```

```java
package org.example.reviewer.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.reviewer.config.Severity;
import org.example.reviewer.review.ReviewResult;
import org.example.reviewer.vcs.Finding;
import org.junit.jupiter.api.Test;

class MainExitCodeTest {

    private static ReviewResult withSeverity(Severity severity) {
        return new ReviewResult(List.of(new Finding("A.java", 1, severity, "r", "m", "")), List.of(), 1, 0);
    }

    @Test
    void failsOnErrorWhenThresholdIsError() {
        assertEquals(1, Main.exitCode(withSeverity(Severity.ERROR), "error"));
    }

    @Test
    void passesOnWarningWhenThresholdIsError() {
        assertEquals(0, Main.exitCode(withSeverity(Severity.WARNING), "error"));
    }

    @Test
    void failsOnWarningWhenThresholdIsWarning() {
        assertEquals(1, Main.exitCode(withSeverity(Severity.WARNING), "warning"));
    }

    @Test
    void neverFailsWhenThresholdIsNever() {
        assertEquals(0, Main.exitCode(withSeverity(Severity.ERROR), "never"));
    }

    @Test
    void passesWhenThereAreNoFindings() {
        assertEquals(0, Main.exitCode(new ReviewResult(List.of(), List.of(), 1, 0), "error"));
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `mvn -q -Dtest='MarkdownReportTest,MainExitCodeTest' test`
Expected: FAIL — `MarkdownReport` и `Main` не существуют.

- [ ] **Step 3: Минимальная реализация**

```java
package org.example.reviewer.report;

import org.example.reviewer.config.Severity;
import org.example.reviewer.review.ReviewResult;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;

public final class MarkdownReport {

    private MarkdownReport() {
    }

    /** Тело review-комментария в PR. */
    public static String summary(PullRequest pr, ReviewResult result) {
        StringBuilder text = new StringBuilder("### 🤖 AI-ревью\n\n");
        if (result.findings().isEmpty()) {
            text.append("Замечаний нет по текущему набору правил.\n");
        } else {
            text.append("Замечаний: **").append(result.findings().size()).append("** — ")
                    .append("ERROR: ").append(result.countOf(Severity.ERROR))
                    .append(", WARNING: ").append(result.countOf(Severity.WARNING))
                    .append(", INFO: ").append(result.countOf(Severity.INFO))
                    .append(".\n");
        }
        text.append("\nПроверено файлов: ").append(result.filesReviewed())
                .append(", пропущено: ").append(result.filesSkipped()).append(".\n");

        if (!result.errors().isEmpty()) {
            text.append("\n<details><summary>Проблемы при проверке</summary>\n\n");
            for (String error : result.errors()) {
                text.append("- ").append(error).append('\n');
            }
            text.append("\n</details>\n");
        }
        text.append("\n<sub>Правила берутся из `.ai-reviewer.yml` в этом репозитории.</sub>\n");
        return text.toString();
    }

    /** Вывод для --dry-run: то же самое плюс сами замечания, так как в PR они не уйдут. */
    public static String console(PullRequest pr, ReviewResult result) {
        StringBuilder text = new StringBuilder("AI-ревью " + pr.slug() + "\n\n");
        for (Finding finding : result.findings()) {
            text.append("%s  %s:%d  [%s]\n    %s\n".formatted(
                    finding.severity(), finding.file(), finding.line(), finding.ruleId(), finding.message()));
            if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
                text.append("    → ").append(finding.suggestion()).append('\n');
            }
            text.append('\n');
        }
        return text + summary(pr, result);
    }
}
```

```java
package org.example.reviewer.cli;

import java.nio.file.Path;
import java.util.List;
import org.example.reviewer.config.ConfigLoader;
import org.example.reviewer.config.ConfigurationException;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.config.Severity;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.diff.UnifiedDiffParser;
import org.example.reviewer.llm.LlmClient;
import org.example.reviewer.llm.LlmClientFactory;
import org.example.reviewer.report.MarkdownReport;
import org.example.reviewer.review.ReviewEngine;
import org.example.reviewer.review.ReviewResult;
import org.example.reviewer.vcs.PullRequest;
import org.example.reviewer.vcs.github.GitHubClient;

public final class Main {

    private static final String USAGE = """
            AI PR Reviewer

            Использование:
              ai-reviewer <owner/repo> <pr-number> [--dry-run] [--config <path>]

            Опции:
              --dry-run         не публиковать в PR, вывести отчёт в stdout
              --config <path>   путь к конфигу (по умолчанию .ai-reviewer.yml)

            Переменные окружения:
              GITHUB_TOKEN        обязательна (кроме --dry-run без публикации всё равно нужна для чтения PR)
              ANTHROPIC_API_KEY   для provider: anthropic
              OPENAI_API_KEY      для provider: openai (плюс OPENAI_BASE_URL для прокси)

            Коды возврата: 0 — чисто, 1 — есть блокирующие замечания, 2 — ошибка запуска.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (ConfigurationException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(2);
        }
    }

    private static int run(String[] args) {
        if (args.length < 2 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(USAGE);
            return 2;
        }

        String[] repoParts = args[0].split("/");
        if (repoParts.length != 2) {
            throw new ConfigurationException("Первый аргумент должен быть owner/repo, получено: " + args[0]);
        }
        int prNumber;
        try {
            prNumber = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Номер PR должен быть числом, получено: " + args[1]);
        }

        boolean dryRun = false;
        Path configPath = Path.of(".ai-reviewer.yml");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new ConfigurationException("--config требует путь к файлу");
                    }
                    configPath = Path.of(args[++i]);
                }
                default -> throw new ConfigurationException("Неизвестная опция: " + args[i]);
            }
        }

        ReviewerConfig config = ConfigLoader.load(configPath);
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new ConfigurationException("Не задана переменная окружения GITHUB_TOKEN.");
        }

        GitHubClient github = new GitHubClient(token, System.getenv("GITHUB_API_URL"));
        LlmClient llm = LlmClientFactory.create(config, System.getenv());

        PullRequest pr = github.fetchPullRequest(repoParts[0], repoParts[1], prNumber);
        List<ChangedFile> files = UnifiedDiffParser.parse(
                github.fetchDiff(repoParts[0], repoParts[1], prNumber));

        ReviewResult result = new ReviewEngine(llm, config).review(pr, files);

        System.out.println(MarkdownReport.console(pr, result));
        if (!dryRun) {
            github.publish(pr, result.findings(), MarkdownReport.summary(pr, result));
            System.out.println("Ревью опубликовано в " + pr.slug());
        }
        return exitCode(result, config.failOn());
    }

    /** Package-private, чтобы закрепить тестом: именно этот код читает CI. */
    static int exitCode(ReviewResult result, String failOn) {
        if (failOn == null || failOn.equalsIgnoreCase("never")) {
            return 0;
        }
        return result.hasAtLeast(Severity.parse(failOn)) ? 1 : 0;
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `mvn -q test`
Expected: PASS, все тесты всех задач зелёные.

- [ ] **Step 5: Собрать исполняемый jar и проверить справку**

```bash
mvn -q -DskipTests package
java -jar target/ai-reviewer.jar --help
```

Expected: печатается USAGE, код возврата 2.

- [ ] **Step 6: Написать README.md**

Содержание: что делает агент, как собрать, как запустить (`--dry-run` и публикация), какие переменные окружения нужны, как править правила в `.ai-reviewer.yml`, коды возврата, пример шага GitHub Actions, раздел «как это устроено» со схемой pipeline и раздел «куда растёт» (webhook, tool-use, другие платформы).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/reviewer/report src/main/java/org/example/reviewer/cli src/test/java/org/example/reviewer/report src/test/java/org/example/reviewer/cli README.md
git commit -m "feat: markdown report and CLI entry point"
```

---

## Self-Review

**1. Покрытие спеки.** Все разделы спеки покрыты: архитектура — Tasks 2–9 по пакетам; поток данных — Task 9 (`Main`) и Task 6 (`ReviewEngine`); конфигурация — Task 3 вместе с `.ai-reviewer.yml`; обработка ошибок — Task 6 (ошибка файла не валит ревью, ретрай), Task 4 (отсечение строк вне diff), Task 7/8 (понятные сообщения и exit 2), Task 9 (exit code по `failOn`); тестирование — тесты в Tasks 2, 3, 4, 5, 6, 7, 8, 9; «вне объёма» — зафиксировано в Global Constraints.

**2. Плейсхолдеры.** Нет TBD/TODO и шагов без кода. Два места в плане помечены как опечатки и исправляются при реализации: лишний `package ... reviewer_placeholder;` в Task 2 Step 3 (файл не создаётся) и имя метода `JSON_PARSE` в Task 8 (переименовать в `parseJson`).

**3. Согласованность типов.** `Severity.parse`, `ChangedFile.reviewable()`, `ReviewResult.hasAtLeast/countOf`, `PullRequest.slug()`, `PromptBuilder.system/user/retryHint`, `FindingParser.parse(String, ChangedFile, int)`, `LlmClient.complete(String, String)`, `LlmClientFactory.create(ReviewerConfig, Map)`, `GitHubClient.reviewBody(List, String)`, `MarkdownReport.summary/console`, `Main.exitCode(ReviewResult, String)` — объявлены в блоках Interfaces и используются в тестах и реализации одинаково.
