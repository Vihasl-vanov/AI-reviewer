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
