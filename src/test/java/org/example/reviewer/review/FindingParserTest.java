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
