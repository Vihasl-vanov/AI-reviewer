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
