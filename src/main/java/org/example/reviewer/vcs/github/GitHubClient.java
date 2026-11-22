package org.example.reviewer.vcs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.example.reviewer.config.ConfigurationException;
import org.example.reviewer.vcs.Finding;
import org.example.reviewer.vcs.PullRequest;
import org.example.reviewer.vcs.PullRequestSource;
import org.example.reviewer.vcs.ReviewPublisher;

public final class GitHubClient implements PullRequestSource, ReviewPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String token;
    private final String apiBaseUrl;

    public GitHubClient(String token, String apiBaseUrl) {
        this.token = token;
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.github.com" : apiBaseUrl;
    }

    @Override
    public PullRequest fetchPullRequest(String owner, String repo, int number) {
        JsonNode node = parseJson(get(prUrl(owner, repo, number), "application/vnd.github+json"));
        return new PullRequest(
                owner, repo, number,
                node.path("title").asText(""),
                node.path("body").asText(""),
                node.path("head").path("sha").asText(""));
    }

    @Override
    public String fetchDiff(String owner, String repo, int number) {
        return get(prUrl(owner, repo, number), "application/vnd.github.v3.diff");
    }

    @Override
    public void publish(PullRequest pr, List<Finding> findings, String summary) {
        post(prUrl(pr.owner(), pr.repo(), pr.number()) + "/reviews", reviewBody(findings, summary));
    }

    /** Package-private: формат тела — единственная часть клиента, которую стоит закрепить тестом. */
    static String reviewBody(List<Finding> findings, String summary) {
        ObjectNode body = JSON.createObjectNode();
        body.put("event", "COMMENT");
        body.put("body", summary);
        ArrayNode comments = body.putArray("comments");
        for (Finding finding : findings) {
            ObjectNode comment = comments.addObject();
            comment.put("path", finding.file());
            comment.put("line", finding.line());
            comment.put("side", "RIGHT");
            comment.put("body", commentBody(finding));
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать ревью", e);
        }
    }

    private static String commentBody(Finding finding) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(finding.severity()).append("**");
        if (!finding.ruleId().isBlank()) {
            text.append(" · `").append(finding.ruleId()).append('`');
        }
        text.append("\n\n").append(finding.message());
        if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
            text.append("\n\n_Предложение:_ ").append(finding.suggestion());
        }
        return text.toString();
    }

    private String prUrl(String owner, String repo, int number) {
        return "%s/repos/%s/%s/pulls/%d".formatted(apiBaseUrl, owner, repo, number);
    }

    private String get(String url, String accept) {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("accept", accept)
                .GET()
                .build();
        return send(request);
    }

    private String post(String url, String jsonBody) {
        HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(url)))
                .header("accept", "application/vnd.github+json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(request);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder
                .header("authorization", "Bearer " + token)
                .header("x-github-api-version", "2022-11-28")
                .header("user-agent", "ai-reviewer")
                .timeout(Duration.ofSeconds(60));
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConfigurationException("GitHub вернул %d на %s: %s"
                        .formatted(response.statusCode(), request.uri().getPath(), response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigurationException("Запрос к GitHub прерван");
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigurationException("Запрос к GitHub не удался: " + e.getMessage(), e);
        }
    }

    private static JsonNode parseJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new ConfigurationException("Неожиданный ответ GitHub: " + e.getMessage(), e);
        }
    }
}
