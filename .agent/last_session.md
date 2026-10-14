# Handover Note: Сессия 75 (ADR-0016 шаг 4/6 — контракт ошибок и async twins для Stream<String> и Stream<ServerSentEvent>; DRAFT 4/6, 316/316; следующий ход — шаг 5: документация, валидация ControllerValidator, end-to-end WireMock тесты)

## Что сделано в этой сессии
- **Ход:** шаг 4 эпика ADR-0016 — контракт ошибок и Async twins (`CompletableFuture<Stream<String>>`, `CompletableFuture<Stream<ServerSentEvent>>`). Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **TDD тесты (`HttpTemplateLineAndEventStreamErrorAndAsyncTest`):**
  - Реализован комплексный набор из 11 unit-тестов (+1 класс в сьюйте):
    - `syncLineStreamOnErrorConsumesAndDecodes`: `@OnError` статус (429) для `Stream<String>` полностью вычитывает поток ошибки, декодирует JSON в `HandledException`, передает заголовки ответа и гарантированно закрывает underlying сетевой `InputStream`.
    - `syncEventStreamOnErrorEmptyBodyCarriesNullDetails`: `@OnError` статус (400) для `Stream<ServerSentEvent>` с пустым телом возвращает `HandledException` с `null` details, закрывает стрим и не обращается к кодеку.
    - `syncLineStreamUnexpectedStatusCarriesRawBody`: неожиданный статус (404) для `Stream<String>` выбрасывает `UnexpectedStatusException` с полным сырым телом и закрывает `InputStream`.
    - `syncEventStreamUnexpectedStatusWithEmptyStream`: неожиданный статус (500) для `Stream<ServerSentEvent>` с пустым телом выбрасывает `UnexpectedStatusException` с пустой строкой в `rawBody` и закрывает стрим.
    - `probeTreatsStreamReturnAsDecodeProof`: `probePayloadDecode` для `Stream<String>` и `Stream<ServerSentEvent>` возвращает `true` (decode-proof) без обращения к JSON-кодеку (ADR-0011 / ADR-0016 Aspect 3).
    - `asyncLineStreamSuccessCompletesWithLiveStream`: `CompletableFuture<Stream<String>>` завершается живым читаемым `Stream<String>`, чтение строк декодирует данные, закрытие `lines.close()` закрывает underlying `InputStream`.
    - `asyncEventStreamSuccessCompletesWithParsedEvents`: `CompletableFuture<Stream<ServerSentEvent>>` завершается читаемым `Stream<ServerSentEvent>`, возвращает распарсенные события, `events.close()` закрывает underlying `InputStream`.
    - `asyncEmptyStreamYieldsEmptyStream`: пустые потоки для async twins завершаются пустыми `Stream<String>` и `Stream<ServerSentEvent>` без зависаний.
    - `asyncOnErrorFailsWithHandledException`: async twin на `@OnError` статусе завершается exceptional completion с `HandledException` в качестве cause `CompletionException`, поток ошибки вычитан и закрыт.
    - `asyncUnexpectedStatusFailsWithRawPayload`: async twin на неожиданном статусе завершается exceptional completion с `UnexpectedStatusException` в качестве cause `CompletionException`, сырое тело сохранено, поток закрыт.
    - `asyncTransportFailureWrapsIOException`: async twin на транспортной ошибке (`IOException`) завершается exceptional completion с `RuntimeException`, оборачивающим исходный `IOException` (симметрично синхронному контракту).
- **Гейты (clean rebuild & rerun):**
  - `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` — **316/316** (43 класса, +11 тестов, 0 fail/err по XML).
  - Warnings: **0 warnings** под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - `build.gradle` не менялся (0.8.0 unreleased).
- **DRAFT:** Шаг 4 отмечен `[x]`, сьюйт обновлен до 316/316.
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #75 и дополнением в итог дня.

## Контекст для следующей сессии (#76)

### Состояние: шаг 5 эпика ADR-0016 (документация, валидация ControllerValidator, end-to-end WireMock тесты)
- **Цель шага 5:**
  1. `ControllerValidator`:
     - Проверить валидацию методов с `Stream<String>`, `Stream<ServerSentEvent>`, `CompletableFuture<Stream<String>>`, `CompletableFuture<Stream<ServerSentEvent>>`.
     - Добавить unit-тесты валидации (например, в `ControllerValidationTest`), если требуется.
  2. End-to-end WireMock тесты (`ServerSentEventsAndLineStreamingTest` или `SseStreamingTest`):
     - End-to-end интеграция через WireMock:
       - SSE chat completion endpoint (эмуляция OpenAI/Anthropic token streaming) с `data: [DONE]`.
       - Line streaming (NDJSON / newline text).
       - Error paths (400 Bad Request с `@OnError`, 500 UnexpectedStatusException).
       - Connection fault / reset.
  3. Документация:
     - `README.md`: лаконичный раздел с примерами использования `Stream<ServerSentEvent>` для LLM и `Stream<String>` для line streaming, указание `onClose()` / try-with-resources.
     - Javadoc на затронутых публичных типах (`ServerSentEvent`, `HttpTemplate`).

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 316).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **316/316** (43 класса).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
