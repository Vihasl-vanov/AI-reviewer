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
