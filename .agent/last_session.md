# Handover Note: Сессия 74 (ADR-0016 шаг 3/6 — wire & engine seam для Stream<String> и Stream<ServerSentEvent>; DRAFT 3/6, 305/305; следующий ход — шаг 4: контракт ошибок и async twins)

## Что сделано в этой сессии
- **Ход:** шаг 3 эпика ADR-0016 — wire & engine seam для `Stream<String>` и `Stream<ServerSentEvent>`. Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **TDD тесты (`HttpTemplateLineAndEventStreamTest`):**
  - Реализован комплексный набор из 10 unit-тестов (+1 класс в сьюйте):
    - `builderDetectsLineStreamAndAsyncTwin`: проверка включения `streamBody = true` для `Stream<String>` и `CompletableFuture<Stream<String>>`.
    - `builderDetectsEventStreamAndAsyncTwin`: проверка включения `streamBody = true` для `Stream<ServerSentEvent>` и `CompletableFuture<Stream<ServerSentEvent>>`.
    - `builderLeavesStreamBodyOffForOtherTypes`: проверка, что неподдерживаемые типы (`Stream<Integer>`, raw `Stream`, `List<String>`, `String`) оставляют `streamBody = false`.
    - `resolveCharsetRules`: исчерпывающий разбор кодировки из `Content-Type` (стандартные UTF-8, ISO-8859-1, US-ASCII; кавычки `charset="ISO-8859-1"`; пробелы; невалидные/пустые fallback на UTF-8; отсутствие заголовка/параметра fallback на UTF-8).
    - `lineStreamingYieldsLinesWithCorrectCharset`: `exchange()` возвращает `Stream<String>`, декодирует строки с правильной кодировкой (UTF-8, кириллица), поток не закрывается преждевременно во время чтения.
    - `lineStreamingClosesUnderlyingStreamOnClose`: вызов `lines.close()` закрывает underlying сетевой `InputStream`.
    - `lineStreamingLogsStreamingLinesWithAndWithoutContentLength`: логирование `"streaming lines, Content-Length: 5"` и `"streaming lines (length unknown)"`.
    - `eventStreamingYieldsParsedEvents`: `exchange()` возвращает `Stream<ServerSentEvent>`, корректно парсит WHATWG SSE события (id, event, data), игнорирует комментарии `: ping`.
    - `eventStreamingClosesUnderlyingStreamOnClose`: вызов `events.close()` закрывает underlying сетевой `InputStream`.
    - `eventStreamingLogsStreamingEventsWithAndWithoutContentLength`: логирование `"streaming events, Content-Length: 10"` и `"streaming events (length unknown)"`.
  - Red-фаза TDD подтверждена (12 ошибок компиляции до реализации).
- **Реализация:**
  - `ru.jrestly.http.HttpTemplateBuilder`:
    - `resolveReturnType`: определение `Stream<String>` и `Stream<ServerSentEvent>` (включая unwrap из `CompletableFuture`) и установка `streamBody = true`.
  - `ru.jrestly.http.HttpTemplate`:
    - `isStreamType`: package-private хелпер проверки `Type` на `Stream<T>`.
    - `resolveCharset` / `parseCharsetFromContentType`: package-private парсинг кодировки из `Content-Type` с защитой от сбоев и дефолтом UTF-8.
    - `closeQuietly`: безопасное закрытие `InputStream` с логом ошибки вместо выброса исключения; переиспользуется в `closeIntermediateStream` и `onClose`.
    - `processResponse`: ветки для `Stream<String>` и `Stream<ServerSentEvent>` с ленивым декодированием через `BufferedReader.lines()` и `ServerSentEvent.parse()`, регистрация `onClose(() -> closeQuietly(stream))`.
    - `streamingPayloadLine`: префиксы `"streaming lines"` и `"streaming events"`.
    - Javadoc на классе и методе `exchange()` лаконично дополнен упоминанием `Stream<String>` и `Stream<ServerSentEvent>`.
- **Гейты (clean rebuild & rerun):**
  - `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` — **305/305** (42 класса, +10 тестов, 0 fail/err по XML).
  - Warnings: **0 warnings** под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - `build.gradle` не менялся (0.8.0 unreleased).
- **DRAFT:** Шаг 3 отмечен `[x]`, сьюйт обновлен до 305/305.
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #74 и дополнением в итог дня.

## Контекст для следующей сессии (#75)

### Состояние: шаг 4 эпика ADR-0016 (контракт ошибок и Async twins)
- **Цель шага 4:**
  1. Ошибочные пути для `Stream<String>` и `Stream<ServerSentEvent>`:
     - Ошибки 4xx/5xx вычитывают поток ответа, декодируют `@OnError` в `HandledException` или сохраняют raw body в `UnexpectedStatusException`.
     - Проверка, что при ошибке сетевой `InputStream` закрывается (`consumePayload` уже закрывает stream через try-with-resources).
  2. Async twins:
     - `CompletableFuture<Stream<String>>` и `CompletableFuture<Stream<ServerSentEvent>>`.
     - Future завершается активным стримом на успехе и exceptional completion на ошибках (симметрично синхронному контракту).
  3. **TDD тесты:**
     - Создать тестовый класс `HttpTemplateLineAndEventStreamErrorAndAsyncTest` (или расширить `HttpTemplateLineAndEventStreamTest`):
       - sync error paths: `@OnError` JSON-декодирование с line/SSE return types, unexpected status с сырым телом;
       - async success: `future.join()` возвращает живой `Stream<String>` / `Stream<ServerSentEvent>`, читающий элементы;
       - async error paths: `HandledException` и `UnexpectedStatusException` как причина `CompletionException` / `ExecutionException`.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 305).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **305/305** (42 класса).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
