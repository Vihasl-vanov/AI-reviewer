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
