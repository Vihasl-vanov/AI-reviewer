# AI PR Reviewer — дизайн первой рабочей версии

Дата: 2026-11-22

## Задача

Агент автоматически проверяет поступающие pull request'ы и снимает с людей
повторяющиеся замечания. Первая версия должна приносить пользу сразу после
запуска: её можно повесить на реальный PR и получить осмысленные
inline-комментарии по правилам команды.

## Решения и их обоснование

| Решение | Почему |
|---|---|
| GitHub как платформа | Там живут PR команды; REST API даёт и diff, и inline-комментарии. |
| CLI, а не webhook-сервис | Запускается руками и из CI одной строкой, не требует публичного адреса. Webhook — следующий шаг, ядро к нему готово. |
| Пофайловые параллельные вызовы LLM | Устойчиво к размеру PR, точнее привязка к строкам, естественные ретраи. Один вызов на весь PR ломается на больших diff'ах. |
| Правила в `.ai-reviewer.yml` | Команда растит чек-лист повторяющихся замечаний без пересборки агента. Это и есть основная ценность. |
| `LlmClient` за интерфейсом | Два провайдера (Anthropic, OpenAI-совместимый) и фейк для тестов. |
| Без Spring/DI/БД | Час на реализацию; ничего из этого задача не требует. |

## Архитектура

Пакеты под `org.example.reviewer`:

- `cli` — `Main`: разбор аргументов (`owner/repo`, номер PR, `--dry-run`, `--config`).
- `config` — `ReviewerConfig`, `ConfigLoader`. Читает YAML; при отсутствии файла — дефолты.
- `vcs` — интерфейсы `PullRequestSource`, `ReviewPublisher`; модель `PullRequest`, `ChangedFile`, `DiffHunk`, `Finding`. Реализация `vcs.github.GitHubClient`. Эти интерфейсы — точка расширения на GitLab/Bitbucket.
- `diff` — `UnifiedDiffParser`: unified diff → файлы и хунки с номерами строк новой версии.
- `llm` — `LlmClient`, `AnthropicClient`, `OpenAiCompatibleClient`, `LlmClientFactory`. HTTP — `java.net.http`.
- `review` — `ReviewEngine`, `PromptBuilder`, `FindingParser`.
- `report` — `MarkdownReport` (summary и вывод dry-run).

## Поток данных

1. CLI разбирает аргументы, `ConfigLoader` читает конфиг.
2. `GitHubClient` отдаёт мету PR и unified diff (`Accept: application/vnd.github.v3.diff`).
3. `UnifiedDiffParser` превращает diff в файлы/хунки с номерами строк.
4. Фильтр: `exclude`-глобы, `maxFiles`, пропуск бинарных и удалённых файлов.
5. На каждый файл параллельно (ограниченный пул): `PromptBuilder` собирает промпт из правил и diff'а файла → `LlmClient` → JSON-массив findings.
6. `FindingParser` валидирует: строка должна присутствовать среди изменённых строк файла, иначе finding отбрасывается.
7. Дедупликация и лимит замечаний.
8. Публикация: GitHub review с inline-комментариями и summary в теле. При `--dry-run` — markdown в stdout, без записи в GitHub.

## Конфигурация (`.ai-reviewer.yml`)

```yaml
provider: anthropic        # anthropic | openai
model: claude-sonnet-5-5
maxFiles: 25
maxFindingsPerFile: 5
failOn: error              # error | warning | never
exclude:
  - "**/target/**"
  - "**/*.lock"
rules:
  - id: no-sout
    severity: warning
    description: Логирование через logger, не через System.out.println.
```

## Обработка ошибок

- Ошибка на одном файле не валит ревью: ошибки копятся и попадают в summary.
- Не-JSON ответ модели → один ретрай с требованием «только JSON», затем файл пропускается.
- Нет `GITHUB_TOKEN` или ключа LLM → внятное сообщение, exit code 2.
- Findings на строки вне diff отбрасываются — главный источник шума у таких агентов.
- Exit code: 0 — нет блокирующих замечаний, 1 — есть (по `failOn`), 2 — ошибка конфигурации/доступа.

## Тестирование (TDD, JUnit 5)

- `UnifiedDiffParser`: много хунков, новый файл, удаление, переименование, бинарник.
- `FindingParser`: мусор вокруг JSON, ```json-забор, строки вне diff, невалидная severity.
- `ReviewEngine`: сквозной сценарий на `FakeLlmClient` и фейковом VCS — без сети.
- `ConfigLoader`: дефолты при отсутствии файла, переопределения.
- HTTP-клиенты сетью не тестируются: они тонкие и скрыты за интерфейсом.

## Вне объёма (YAGNI)

Spring и DI-фреймворк, БД и кэш, webhook-сервер, реализации GitLab/Bitbucket,
tool-use и агентный цикл, библиотеки ретраев. Зависимости: Jackson
(`databind`, `dataformat-yaml`), JUnit 5, `maven-shade-plugin`.

## Куда растёт

- Webhook-сервис: `ReviewEngine` вызывается из обработчика события вместо CLI.
- Tool-use (модель сама читает файлы репозитория): новая реализация `LlmClient` и расширение `PromptBuilder`, остальное не меняется.
- Другая платформа: реализовать `PullRequestSource` и `ReviewPublisher`.
