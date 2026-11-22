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
                "src/Bad.java", java.util.Collections.singletonList(null),
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
