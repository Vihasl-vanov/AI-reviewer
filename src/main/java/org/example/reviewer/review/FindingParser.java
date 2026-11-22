package org.example.reviewer.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.reviewer.config.Severity;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.vcs.Finding;

/**
 * Превращает ответ модели в замечания и отсекает шум.
 * Главная проверка — строка должна реально присутствовать среди добавленных строк diff'а:
 * без неё модель регулярно комментирует строки, которых в PR нет.
 */
public final class FindingParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FindingParser() {
    }

    public static List<Finding> parse(String llmResponse, ChangedFile file, int maxFindings) {
        String array = extractJsonArray(llmResponse);
        JsonNode root;
        try {
            root = JSON.readTree(array);
        } catch (Exception e) {
            throw new FindingParseException("Ответ модели не разбирается как JSON: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new FindingParseException("Ожидался JSON-массив, получено: " + root.getNodeType());
        }

        Set<Integer> allowed = new HashSet<>(file.addedLines());
        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : root) {
            if (findings.size() >= maxFindings) {
                break;
            }
            int line = node.path("line").asInt(-1);
            String message = node.path("message").asText("");
            if (!allowed.contains(line) || message.isBlank()) {
                continue;
            }
            findings.add(new Finding(
                    file.path(),
                    line,
                    Severity.parse(node.path("severity").asText(null)),
                    node.path("ruleId").asText(""),
                    message.trim(),
                    node.path("suggestion").asText("")));
        }
        return findings;
    }

    private static String extractJsonArray(String response) {
        if (response == null) {
            throw new FindingParseException("Пустой ответ модели");
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new FindingParseException("В ответе модели нет JSON-массива");
        }
        return response.substring(start, end + 1);
    }
}
