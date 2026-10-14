# Handover Note: Сессия 73 (ADR-0016 шаг 2/6 — модель ServerSentEvent и парсер; DRAFT 2/6, 295/295; следующий ход — шаг 3: wire & engine seam)

## Что сделано в этой сессии
- **Ход:** шаг 2 эпика ADR-0016 — модель `ServerSentEvent` и потоковый парсер `ServerSentEventParser`. Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **TDD тесты (`ServerSentEventTest`):**
  - Реализован комплексный набор из 13 unit-тестов, строго специфицирующий поведение:
    - `recordAccessorsAndFactories`: проверка полей record'а `id`, `event`, `data`, `retry`, фабрик `ofData`, `of`, хелпера `isDone()`.
    - `parseSingleDataLine`: одиночное поле `data: hello world`.
    - `parseMultiLineData`: последовательные `data:` склеиваются через `\n`.
    - `parseAllFields`: разбор всех полей WHATWG SSE (`id:`, `event:`, `data:`, `retry:`).
    - `colonSpacingRules`: соответствие WHATWG SSE (удаление ровно одного ведущего пробела после `:`: `data: text` vs `data:text` vs `data:  two-spaces`, `data` без `:` дает пустую строку `""`).
    - `ignoreComments`: строки с префиксом `:` (`: ping`, `: keepalive`, `:`) полностью игнорируются и не порождают событий.
    - `ignoreUnrecognizedFields`: неизвестные поля игнорируются.
    - `ignoreMalformedRetry`: нечисловые или отрицательные `retry:` игнорируются.
    - `emptyLinesDoNotCreateEmptyEvents`: подряд идущие пустые строки не генерируют пустые события.
    - `dispatchTrailingEventWithoutEmptyLine`: завершение потока без хвостовой пустой строки корректно диспатчит накопленное событие.
    - `parseMultipleConsecutiveEvents`: последовательные события сбрасывают состояние буфера между собой.
    - `lazyEvaluation`: ленивое чтение строк upstream потока по требованию downstream потребителя (при `findFirst()` прочитано ровно 2 строки из 6).
    - `lifecycleOnClose`: вызов `eventStream.close()` вызывает `lineStream.close()`.
  - Red-фаза TDD подтверждена (37 ошибок компиляции до создания реализации).
- **Реализация:**
  - `ru.jrestly.http.ServerSentEvent`: public immutable record `(String id, String event, String data, Long retry)` с фабриками `ofData(String)`, `of(String, String)`, хелпером `isDone()` (для LLM sentinels типа `[DONE]`) и статическим парсером `parse(Stream<String>)`.
  - `ru.jrestly.http.ServerSentEventParser`: package-private streaming-парсер поверх `Spliterators.spliteratorUnknownSize` и `Iterator`, реализующий ленивую конвейерную обработку строк и `onClose(lines::close)`.
- **Гейты (clean rebuild & rerun):**
  - `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` — **295/295** (41 класс, +13 тестов, 0 fail/err по XML).
  - Warnings: **0 warnings** под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - `build.gradle` не менялся (0.8.0 unreleased).
- **DRAFT:** Шаг 2 отмечен `[x]`, сьюйт обновлен до 295/295.
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #73 и дополнением в итог дня.

## Контекст для следующей сессии (#74)

### Состояние: шаг 3 эпика ADR-0016 (wire & engine seam для Stream<String> и Stream<ServerSentEvent>)
- **Цель шага 3:**
  1. `HttpTemplateBuilder`:
     - Определение stream-режима (`streamBody = true`) для возвращаемых типов `Stream<String>` и `Stream<ServerSentEvent>` (а также когда они обёрнуты в `CompletableFuture`).
     - Сохранение информации о целевом stream-типе (например, enum `StreamType { BYTE, LINE, SSE }` или `Class<?> streamElementType`).
  2. `HttpTemplate.processResponse`:
     - Переиспользование существующего шва `response.stream()` (ADR-0015).
     - Разрешение кодировки `Charset` из заголовка `Content-Type` (дефолт `StandardCharsets.UTF_8`).
     - Для `Stream<String>`: `new BufferedReader(new InputStreamReader(response.stream(), charset)).lines().onClose(() -> closeQuietly(response.stream()))`.
     - Для `Stream<ServerSentEvent>`: `ServerSentEvent.parse(lines)`.
     - Логирование: `"streaming lines"` или `"streaming events"`.
  3. **TDD тесты:**
     - Создать юнит/wire тесты на построчный стриминг и SSE-стриминг в `HttpTemplate` (кодировки, `onClose()`, успешное получение стрима).

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 295).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **295/295** (41 класс).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
