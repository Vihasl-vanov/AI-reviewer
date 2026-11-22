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
              GITHUB_TOKEN        обязательна: нужна и для чтения PR, и для публикации
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
