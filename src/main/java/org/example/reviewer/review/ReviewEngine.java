package org.example.reviewer.review;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.llm.LlmClient;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;

/**
 * Ревьюит PR по одному файлу за вызов, файлы — параллельно.
 * Пофайловый вызов выбран вместо одного вызова на весь PR: устойчиво к размеру diff'а,
 * точнее привязка к строкам, и упавший файл не уносит с собой всё ревью.
 */
public final class ReviewEngine {

    private final LlmClient llm;
    private final ReviewerConfig config;

    public ReviewEngine(LlmClient llm, ReviewerConfig config) {
        this.llm = llm;
        this.config = config;
    }

    public ReviewResult review(PullRequest pr, List<ChangedFile> files) {
        List<String> errors = new CopyOnWriteArrayList<>();
        List<PathMatcher> excludes = compileExcludes(config.exclude());

        List<ChangedFile> candidates = files.stream()
                .filter(ChangedFile::reviewable)
                .filter(file -> !excluded(file, excludes))
                .toList();
        int skipped = files.size() - candidates.size();

        List<ChangedFile> toReview = candidates;
        if (candidates.size() > config.maxFiles()) {
            errors.add("В PR %d подходящих файлов, проверены первые %d (ограничение maxFiles)."
                    .formatted(candidates.size(), config.maxFiles()));
            toReview = candidates.subList(0, config.maxFiles());
        }

        String systemPrompt = PromptBuilder.system(config);
        List<Finding> collected = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (ChangedFile file : toReview) {
                futures.add(pool.submit(() -> {
                    try {
                        collected.addAll(reviewFile(pr, file, systemPrompt));
                    } catch (RuntimeException e) {
                        errors.add("Файл %s не проверен: %s".formatted(file.path(), e.getMessage()));
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    errors.add("Сбой задачи ревью: " + e.getMessage());
                }
            }
        }

        return new ReviewResult(dedupe(collected), List.copyOf(errors), toReview.size(), skipped);
    }

    private List<Finding> reviewFile(PullRequest pr, ChangedFile file, String systemPrompt) {
        String userPrompt = PromptBuilder.user(pr, file);
        try {
            return FindingParser.parse(llm.complete(systemPrompt, userPrompt), file, config.maxFindingsPerFile());
        } catch (FindingParseException first) {
            String retried = llm.complete(systemPrompt, userPrompt + PromptBuilder.retryHint());
            return FindingParser.parse(retried, file, config.maxFindingsPerFile());
        }
    }

    /**
     * `**\/target/**` в PathMatcher НЕ матчит `target/Generated.java`: `**\/` требует
     * минимум один сегмент слева. Люди пишут такой глоб, подразумевая и корневой каталог,
     * поэтому для каждого шаблона с префиксом `**\/` компилируем ещё и вариант без него.
     */
    private static List<PathMatcher> compileExcludes(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
            }
        }
        return List.copyOf(matchers);
    }

    private static boolean excluded(ChangedFile file, List<PathMatcher> excludes) {
        Path path = Path.of(file.path());
        return excludes.stream().anyMatch(matcher -> matcher.matches(path));
    }

    /** Одна строка + одно правило = одно замечание: модель любит повторяться. */
    private static List<Finding> dedupe(List<Finding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> unique = new ArrayList<>();
        for (Finding finding : findings) {
            if (seen.add(finding.file() + ":" + finding.line() + ":" + finding.ruleId())) {
                unique.add(finding);
            }
        }
        unique.sort(Comparator.comparing(Finding::file).thenComparingInt(Finding::line));
        return List.copyOf(unique);
    }
}
