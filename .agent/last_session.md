# Handover Note: Сессия 72 (ADR-0016 шаг 1/6 — концепт SSE & line streaming для LLM; DRAFT 1/6, 282/282; следующий ход — шаг 2: модель ServerSentEvent и парсер)

## Что сделано в этой сессии
- **Ход:** шаг 1 эпика ADR-0016 — концепт-сессия. Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **Исследование JDK 21 `BodyHandlers.ofLines()`:**
  - Байт-код `ResponseSubscribers.createLineStream` показал, что JDK буквально использует `HttpResponseInputStream` и оборачивает его в `BufferedReader.lines().onClose(...)`.
  - Решение: шов стриминга ADR-0015 (`WireResponse.stream`, `ofInputStream()`, закрытие промежуточных стримов, вычитка при `@OnError`/unexpected status) полностью переиспользуется без изменения ABI `WireResponse`!
- **Дизайн (ADR-0016, `docs/0.9.0/ADR-0016-sse-line-events.md`):**
  - **Surface (Aspect 1):** Типы возврата: `Stream<String>` (линии, NDJSON, CSV) и `Stream<ServerSentEvent>` (WHATWG SSE), плюс `CompletableFuture<Stream<...>>`. Тип возврата — контракт (ADR-0004), маркерных аннотаций нет.
  - **Engine (Aspect 2):** Базируется на шве `WireResponse.stream` ADR-0015.
  - **Contract & Codec (Aspect 3):** JSON by return type — успех отдает живой стрим, ошибочные пути вычитывают stream и бросают `HandledException` / `UnexpectedStatusException`.
  - **SSE Framing (Aspect 4):** Спецификация WHATWG SSE (`data:`, `event:`, `id:`, `retry:`, комментарии `:` игнорируются, multi-line data конкатенируется через `\n`, разделитель `""`). Ленивый streaming-парсер.
  - **Lifecycle & Charset (Aspect 5):** `Stream<T>` реализует `AutoCloseable`, `onClose()` закрывает `BufferedReader` и `InputStream`. Кодировка из `Content-Type` (дефолт `UTF-8`).
  - **Logging & Retry (Aspect 6):** Лог `"streaming lines / events, Content-Length: N"`, без преждевременного чтения тела.
- **DRAFT:** Открыт эпик ADR-0016 (шаг 1 `[x]`, шаги 2–6 расписаны).
- **Гейты (clean rebuild & rerun):**
  - `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` — **282/282** (40 классов, 0 fail/err по XML).
  - Warnings: **0 warnings** под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - `build.gradle` не менялся (0.8.0 unreleased).
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #72 и дополнением в итог дня.

## Контекст для следующей сессии (#73)

### Состояние: шаг 2 эпика ADR-0016 (модель ServerSentEvent и парсер)
- **Цель шага 2:** реализовать `ru.jrestly.http.ServerSentEvent` (immutable record) и потоковый парсер `ServerSentEventParser` (или `ServerSentEvent.parse(Stream<String>)`).
- **TDD тесты:**
  - Одиночные `data: hello`.
  - Мультистрочный `data:` (`data: a\ndata: b` -> `"a\nb"`).
  - Поля `event:`, `id:`, `retry:`.
  - Игнорирование комментариев (`: ping`).
  - Разделитель пустой строкой `""` (dispatch) и хвостовое событие без завершающей пустой строки.
  - LLM-маркер `data: [DONE]`.
  - Ленивость: парсинг строк по требованию downstream потребителя.
  - Жизненный цикл: закрытие `Stream<ServerSentEvent>` закрывает входной `Stream<String>`.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 282).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **282/282** (40 классов).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
