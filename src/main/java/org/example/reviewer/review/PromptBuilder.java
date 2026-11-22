package org.example.reviewer.review;

import java.util.StringJoiner;
import org.example.reviewer.config.ReviewerConfig;
import org.example.reviewer.config.Rule;
import org.example.reviewer.diff.ChangedFile;
import org.example.reviewer.vcs.PullRequest;

public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String system(ReviewerConfig config) {
        StringJoiner rules = new StringJoiner("\n");
        for (Rule rule : config.rules()) {
            rules.add("- id=%s severity=%s: %s".formatted(rule.id(), rule.severity(), rule.description()));
        }

        return """
                Ты — ревьюер кода в команде. Тебе дают diff одного файла из pull request'а.
                Твоя работа — найти нарушения правил команды, перечисленных ниже, и ничего больше.

                ПРАВИЛА КОМАНДЫ:
                %s

                ЖЁСТКИЕ ОГРАНИЧЕНИЯ:
                1. Комментируй ТОЛЬКО строки, помеченные в diff как добавленные, и указывай их номер
                   из заголовка хунка. Замечание на любую другую строку будет отброшено.
                2. Одно замечание — одно конкретное нарушение одного правила. Без общих рассуждений,
                   без похвалы, без пересказа кода.
                3. Если нарушение не очевидно из diff'а — молчи. Ложное замечание дороже пропущенного.
                4. Не придирайся к форматированию, именам и стилю, если на это нет правила выше.

                ФОРМАТ ОТВЕТА: только JSON-массив, без текста до и после, без markdown-ограждений.
                Схема элемента:
                  {"line": <номер добавленной строки>, "severity": "error|warning|info",
                   "ruleId": "<id правила из списка>", "message": "<что не так, 1-2 предложения>",
                   "suggestion": "<как починить, можно пустую строку>"}
                Если замечаний нет, ответь ровно: []
                """.formatted(rules.toString());
    }

    public static String user(PullRequest pr, ChangedFile file) {
        return """
                Pull request: %s
                Заголовок: %s
                Описание: %s

                Файл: %s (%s)
                Номера строк, к которым разрешено привязывать замечания: %s

                Diff файла:
                %s
                """.formatted(
                pr.slug(),
                blankToDash(pr.title()),
                blankToDash(pr.description()),
                file.path(),
                file.status(),
                file.addedLines(),
                file.patch());
    }

    /** Подсказка для единственного ретрая, когда модель ответила не JSON. */
    public static String retryHint() {
        return "\n\nПредыдущий ответ не был разобран. Ответь ТОЛЬКО JSON-массивом по схеме выше. "
                + "Если замечаний нет — ответь ровно [].";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
