# AI PR Reviewer

CLI-агент, который забирает diff пул-реквеста на GitHub, проверяет его LLM по правилам
команды из `.ai-reviewer.yml` и публикует inline-комментарии прямо к строкам кода.

Задача, которую он решает: снять с ревьюеров повторяющиеся замечания — те, что команда
проговаривает в каждом втором PR. Такие замечания формулируются один раз в конфиге
и дальше проверяются автоматически.

## Сборка

```bash
mvn package
```

Результат — `target/ai-reviewer.jar` (исполняемый, зависимости внутри).

## Запуск

```bash
export GITHUB_TOKEN=ghp_...
export ANTHROPIC_API_KEY=sk-ant-...

# посмотреть, что агент скажет, ничего не публикуя
java -jar target/ai-reviewer.jar acme/api 42 --dry-run

# опубликовать ревью в PR
java -jar target/ai-reviewer.jar acme/api 42
```

Опции:

| Опция | Смысл |
|---|---|
| `--dry-run` | отчёт в stdout, в GitHub ничего не пишется |
| `--config <path>` | путь к конфигу, по умолчанию `.ai-reviewer.yml` |

Переменные окружения:

| Переменная | Нужна когда |
|---|---|
| `GITHUB_TOKEN` | всегда — и для чтения PR, и для публикации |
| `ANTHROPIC_API_KEY` | `provider: anthropic` |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` | `provider: openai`, в том числе прокси и локальные модели |
| `GITHUB_API_URL` | GitHub Enterprise |

Коды возврата: `0` — чисто, `1` — есть замечания не ниже порога `failOn`, `2` — ошибка запуска.
Поэтому агента можно воткнуть в CI блокирующим шагом.

## Правила команды

Всё, что агент ищет, задано в `.ai-reviewer.yml`. Добавить новое повторяющееся
замечание — значит дописать правило; пересобирать ничего не нужно.

```yaml
rules:
  - id: no-sout
    severity: warning        # error | warning | info
    description: Отладочный вывод через System.out вместо логгера.
```

`severity` влияет и на вид комментария, и на код возврата через `failOn`.
`exclude` — глобы путей, которые не проверяются (сгенерированный код, локи, вендоринг).
`maxFiles` и `maxFindingsPerFile` держат в узде стоимость и шум.

## В GitHub Actions

```yaml
name: AI review
on: pull_request
jobs:
  review:
    runs-on: ubuntu-latest
    permissions:
      pull-requests: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - run: mvn -q -DskipTests package
      - run: java -jar target/ai-reviewer.jar ${{ github.repository }} ${{ github.event.pull_request.number }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
```

## Как это устроено

```
Main (cli)
  └─ ConfigLoader ──────────── .ai-reviewer.yml → ReviewerConfig (правила, лимиты, порог)
  └─ GitHubClient (vcs) ────── мета PR + unified diff
  └─ UnifiedDiffParser (diff) ─ diff → ChangedFile с номерами добавленных строк
  └─ ReviewEngine (review) ─── фильтры → параллельно по файлу:
  │     PromptBuilder → LlmClient → FindingParser → findings
  └─ GitHubClient.publish ──── review с inline-комментариями + summary
     либо MarkdownReport ───── отчёт в stdout при --dry-run
```

Три решения, которые стоит знать, читая код:

**Вызов LLM на каждый файл, а не один на весь PR.** Один запрос на PR проще, но ломается
на больших diff'ах: контекст переполняется, а внимание модели размазывается по десяткам
файлов. Пофайловые запросы идут параллельно (виртуальные потоки), упавший файл не уносит
с собой всё ревью, и привязка к строкам заметно точнее.

**Замечания на строки вне diff'а отбрасываются.** `FindingParser` сверяет номер строки
со списком добавленных строк файла. Это главный источник шума у таких агентов: модель
охотно комментирует строки, которых в PR нет. Проверка дешёвая, а эффект большой.

**Внешний мир спрятан за три интерфейса.** `PullRequestSource`, `ReviewPublisher`,
`LlmClient`. Поэтому ядро полностью тестируется без сети: `ReviewEngineTest` прогоняет
сквозной сценарий на рукописном фейке LLM — слияние замечаний, фильтры, ретрай на
не-JSON ответе, дедуп, лимит файлов.

## Тесты

```bash
mvn test
```

46 тестов, сети не требуют. HTTP-клиенты намеренно не покрыты: они тонкие, вся логика
из них вынесена (у `GitHubClient` тестом закреплён формат тела review-запроса).

## Куда это растёт

- **Webhook-сервис вместо CLI.** `ReviewEngine` вызывается из обработчика события
  `pull_request`; ядро менять не нужно.
- **Tool-use.** Чтобы модель видела контекст за пределами diff'а, ей нужны инструменты
  `read_file` / `list_files`. Это новая реализация `LlmClient` плюс цикл вызовов
  инструментов — остальной pipeline не меняется.
- **Другая платформа.** GitLab или Bitbucket — это реализация `PullRequestSource`
  и `ReviewPublisher`, больше ничего.
- **Детерминированные проверки до LLM.** Часть правил (`no-sout`, `todo-without-ticket`)
  надёжнее и бесплатнее ловится regex'ом по добавленным строкам; LLM тогда остаётся
  то, что действительно требует понимания кода.

## Документы

- Дизайн: `docs/superpowers/specs/2026-11-22-ai-pr-reviewer-design.md`
- План реализации по задачам: `docs/superpowers/plans/2026-11-22-ai-pr-reviewer.md`
