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
