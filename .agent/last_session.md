# Last Session: #142 (2026-10-15)

## Что сделано

**Аудит (вопрос Антона №2), срез «SSE-парсинг против WHATWG dispatch algorithm»** — найдено и исправлено 4 отклонения `ServerSentEventParser` от HTML §9.2.5.

1. **Фантомные события (главная находка):** блок только с `id:`/`event:`/`retry:` (без `data:`) диспатчился как событие с `data == null`. Реальные серверы шлют так control/keep-alive блоки (`retry: 3000\n\n`, `event: ping\n\n`) → пользовательские пайплайны `.map(e -> codec.deserialize(e.data(), ...))` падали NPE. По WHATWG пустой data-буфер отменяет dispatch и сбрасывает event type buffer.
2. **Last-Event-ID теперь stream-level:** персистится между событиями до перезаписи (`EventSource.lastEventId`), empty `id:` сбрасывает; `[DONE]` больше не теряет последний id.
3. **`id` с U+0000** игнорируется (WHATWG).
4. **`retry` только ASCII-цифры** (`+5`/`-7` раньше молча принимались `Long.parseLong`) и персистится как эффективное reconnect-время.

**TDD:** 5 новых кейсов в `ServerSentEventTest`; red-first подтверждён прогоном на старом парсере через `git stash` (6 assertions FAILED). Обновлены ожидания: `ServerSentEventTest.parseMultipleConsecutiveEvents`, `ServerSentEventsAndLineStreamingTest.syncLlmChatStreaming`, `HttpTemplateLineAndEventStreamTest.eventStreamingYieldsParsedEvents` (сравнение целых record'ов!).

**Документация:** `docs/0.9.0/ADR-0016-sse-line-events.md` — секция «Amendment 2026-10-15 (session #142)»; Javadoc `ServerSentEvent` (stream-level семантика id/retry) и `ServerSentEventParser` (dispatch algorithm).

## Гейты

- `./gradlew test` → **1052/1052** (0 failures, 0 errors, +5 тестов).
- `compileJava`/`compileTestJava` → 0 warnings под `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под `-Xdoclint:all -Werror`.
- Zero-dep POM: без изменений (0 runtime зависимостей).

## Контекст для следующей сессии (#143)

### Следующий шаг:
- Кандидат №3 из активной зоны DRAFT.md: OkHttpEngine / ApacheHttpEngine external modules + сравнительные бенчмарки (развилка Антона №1, ADR-0058 — следующий свободный).
- Или остаток аудита: edge cases `HttpTemplate`, гонки lazy-init, `WireRequest`/`WireResponse`.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **Фильтрованный прогон — через `:test`**: `./gradlew :test --tests 'X'` не задевает `:benchmarks:test`.
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск): не использовать для order-sensitive данных.
- **Полные record-сравнения `new ServerSentEvent(...)` в тестах** не ловятся grep'ом по `.id()` — при смене семантики полей искать `new ServerSentEvent(` по всему `src/test`.
- **Не запускать фоновые гейты и `git stash` одновременно** — прогон поймал «красный» парсер, дав ложный FAIL (сессия #142).
- **SSE-семантика (новая, WHATWG):** data-less блоки не диспатчатся; last-event-ID и retry персистятся; `retry` — только цифры.
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно **1052**).
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать.
- **Next free ADR number:** ADR-0058.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1052/1052**.
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
