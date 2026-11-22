package org.example.reviewer.vcs;

import org.example.reviewer.config.Severity;

public record Finding(String file, int line, Severity severity, String ruleId, String message, String suggestion) {
}
