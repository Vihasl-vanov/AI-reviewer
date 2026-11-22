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
        assertTrue(summary.contains("проверено файлов: 2") || summary.contains("Проверено файлов: 2"));
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
