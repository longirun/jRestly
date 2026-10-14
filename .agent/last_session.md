# Handover Note: Сессия 76 (ADR-0016 шаг 5/6 — документация, валидация ControllerValidator, end-to-end WireMock тесты; DRAFT 5/6, 326/326; следующий ход — шаг 6: дистилляция в ROADMAP и зачистка DRAFT)

## Что сделано в этой сессии
- **Ход:** шаг 5 эпика ADR-0016 — документация, валидация (`ControllerValidator`), end-to-end WireMock тесты для LLM/SSE и line streaming. Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **Валидация (`ControllerValidator`):**
  - Расширен `ControllerValidationTest.StreamController`: добавлены синхронные и асинхронные методы `Stream<String>`, `Stream<ServerSentEvent>`, `CompletableFuture<Stream<String>>`, `CompletableFuture<Stream<ServerSentEvent>>`. Проверена валидация через `ControllerValidator.validate(...)` и создание контроллера через клиент (0 проблем).
  - В `TestController` добавлены методы `getLines()`, `getLinesAsync()`, `getEvents()`, `getEventsAsync()` для согласованности тестовой модели.
- **End-to-end WireMock тесты (`ServerSentEventsAndLineStreamingTest`):**
  - Реализован комплексный набор из 10 end-to-end тестов (+1 класс в сьюйте):
    - `syncLlmChatStreaming`: реальный LLM Chat Completions SSE-стрим (OpenAI/Anthropic формат) с `event: message`, `id: N`, `data: {"content": ...}`, распознавание `[DONE]` маркера через `event.isDone()`.
    - `asyncLlmChatStreaming`: асинхронный близнец `CompletableFuture<Stream<ServerSentEvent>>` с разбором токенов и маркера завершения `[DONE]`.
    - `syncNdjsonLineStreaming`: построчный стриминг NDJSON (`Stream<String>`) с заголовком `application/x-ndjson`.
    - `asyncNdjsonLineStreaming`: асинхронный близнец построчного стриминга `CompletableFuture<Stream<String>>`.
    - `lineStreamingDecodesCustomCharset`: разбор альтернативной кодировки (`windows-1251`) из заголовка `Content-Type`.
    - `sseOnErrorConsumesBodyAndThrowsHandledException`: `@OnError` статус (429 Rate Limit) для SSE-стрима вычитывает тело ошибки и декодирует его в `HandledException`.
    - `lineStreamOnErrorConsumesBodyAndThrowsHandledException`: `@OnError` статус (400 Bad Request) для построчного стрима вычитывает тело ошибки и декодирует в `HandledException`.
    - `sseUnexpectedStatusThrowsException`: неожиданный статус (502) для SSE выбрасывает `UnexpectedStatusException` с полным сырым телом ошибки.
    - `asyncUnexpectedStatusThrowsException`: неожиданный статус (500) для async twin завершает future `CompletionException`, оборачивающим `UnexpectedStatusException`.
    - `serverFaultDuringStream`: connection reset (`Fault.CONNECTION_RESET_BY_PEER`) корректно завершается `UncheckedIOException` или сетевым исключением `IOException` без зависаний.
- **Документация (`README.md`):**
  - Добавлен раздел `## Server-Sent Events & line streaming` с лаконичными примерами использования `Stream<ServerSentEvent>` для LLM Chat API и `Stream<String>` для NDJSON, правилами управления жизненным циклом через `try-with-resources`, соответствием WHATWG SSE спецификации и единым контрактом ошибок.
- **Гейты (clean rebuild & rerun):**
  - `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` — **326/326** (44 класса, +10 тестов, 0 fail/err по XML).
  - Warnings: **0 warnings** под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - `build.gradle` не менялся (0.8.0 unreleased).
- **DRAFT:** Шаг 5 отмечен `[x]`, сьюйт обновлен до 326/326.
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #76 и дополнением в итог дня.

## Контекст для следующей сессии (#77)

### Состояние: шаг 6 эпика ADR-0016 (дистилляция в ROADMAP.md + зачистка DRAFT.md)
- **Цель шага 6:**
  1. Дистилляция в `ROADMAP.md`:
     - Добавить секцию завершения эпика: «Server-Sent Events & line streaming (ADR-0016) — COMPLETE ✅ (2026-10-14, unreleased)».
     - Включить лаконичные ссылки на ADR-0016, сессии #72–#76, ключевые аспекты (Stream<String>, Stream<ServerSentEvent>, WHATWG SSE parser, isDone(), onClose lifecycle, единый контракт ошибок, zero-dep, 326 тестов, 0 warnings).
  2. Зачистить `DRAFT.md`:
     - Снять завершённый эпик.
     - Поднять кандидатов из активной зоны (кандидат №1: гибкость параметров запроса — class-level headers, nullable-параметры; кандидат №2: тонкая настройка пайплайна; кандидат №3: test-javadoc).
  3. Провести чистый проверочный прогон гейтов (`./gradlew test compileJava compileTestJava javadoc --rerun-tasks`).

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 326).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **326/326** (44 класса).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
