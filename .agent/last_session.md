# Handover Note: Сессия 19 (sync-retry-контур + клиентский уровень + RetryTest — шаг 3 retry-эпика)

## Что сделано в этой сессии
- **Sync-контур в `HttpTemplate`** (DRAFT-шаг 3 `[x]`, 198→208 тестов):
  - `executeWithRetries(WireRequest)` об `executeWithRedirects` — ADR Aspect
    6 буквально: цикл `attempt 1..maxAttempts` по raw-ответам;
    `IOException` → retry если `retryTransport` и не last (иначе — вверх);
    `InterruptedException` → fail fast (никогда не ретрается); статус →
    `isRetryableStatus`. Disabled policy — straight pass-through
    (`executeWithRedirects`), zero behavior change для off-пользователей.
  - `processResponse` не тронут (ADR Aspect 3): auth-capture, лог,
    `@OnError`-декодинг, статус-чек — один раз на финальном ответе.
  - `isRetryableStatus` — предикаты ровно те, что у `processResponse`
    («не могут расхождение»): `isExpectedStatus` (2xx / `@ExpectStatus`)
    → успех; `onErrorParser.canParse(status)` → терминально по умолчанию
    (escape hatch `retryOnErrorStatuses`); затем retryable set; затем
    идемпотентность — POST/PATCH только через `retryNonIdempotent`
    (RFC 9110).
  - `scheduleRetry` — одна DEBUG-строка на attempt в формулировке ADR
    (`Attempt 1/3 failed: HTTP 503 — retrying in 213 ms`) + sleep
    (`Thread.sleep`, 0 ms — без системного call); jitter —
    `ThreadLocalRandom.current()` в package-private шов.
  - `retryAfterSeconds(WireResponse)` — integer-seconds только,
    case-insensitive, trim; HTTP-date/non-integer → null (known gap);
    для transport-failure (response == null) → null.
  - `setRetryPolicy` + поле с default `RetryPolicy.off()`.
- **Клиентский уровень:** default method `ModuleInfo.getRetryPolicy()` →
  `RetryPolicy.off()` (non-breaking — публичный интерфейс, чужие
  имплементаторы не ломаются); `SimpleModuleInfo` — поле + getter +
  `Builder.retry(RetryPolicy)` (requireNonNull, стиль `httpVersion`);
  `JRestlyClient.Builder.retry(...)` — проксирование в
  `moduleInfoBuilder` (форма прецедента `httpVersion`).
  `HttpTemplateBuilder.build()`: `result.setRetryPolicy(moduleInfo.
  getRetryPolicy())` — пока только клиентский уровень; комментарий в коде
  помечает, что `@Retry`-override за шагом 4.
- **Аллокация на hot path:** `getRetryPolicy()` вызывается на каждом
  запросе (proxy → build'ер на каждый вызов) → `RetryPolicy.off()` стал
  shared constant `OFF` (immutable — безопасно); `RetryPolicyTest`
  прогнал без изменений (identity не проверял).
- **`RetryTest`** (10 интеграций, `ru.jrestly`, `BaseWireMockTest`):
  off by default (503 → 1 запрос); 503→503→200 (3 запроса); исчерпание
  (UnexpectedStatusException, 3 запроса); transport-fault
  (`withFault(CONNECTION_RESET_BY_PEER)` → 200, 2 запроса); неретраемый
  404 (1 запрос); `@OnError`-терминальность (429, `@OnError({429})` →
  1 запрос, typed HandledException, не Unexpected); opt-in
  `retryOnErrorStatuses` (429→429→200, 3 запроса); идемпотентность POST
  (503 → 1 запрос по умолчанию; с `retryNonIdempotent` — 3 запроса);
  `Retry-After: 1` на 429 (elapsed ≥ 1 s wall-clock, 2 запроса).
  Последовательные статусы/fault — через WireMock-scenarios
  (`inScenario` + `whenScenarioStateIs` + `willSetStateTo`). Backoff'ы
  10–50 ms; класс целиком ~1.5 s.
- **Фикстуры:** `TestModuleInfo` — `setRetryPolicy` + override
  `getRetryPolicy` (default off); `TestController` — новый
  `getRateLimited()` с `@OnError(statuses = {429}, errorObject =
  TestErrorDto.class)` (для сценариев 5–6).
- **ADR-0008: одна строка в Aspect 6** — идемпотентный гейт (Aspect 5)
  применяется только к status-retries; транспорт гейтится
  `retryTransport`'ом (pre-response: запрос мог не дойти — connect
  timeout, ретрай безопасен даже для POST). Амбивалентность была в самом
  ADR (псевдокод Aspect 6 vs таблица Aspect 5) — реализация по
  псевдокоду, ADR уточнён.
- **Гейты:** `./gradlew test --rerun` — **208/208** (198 + 10; 0
  failures/0 errors по XML, 17 с, тёплый кэш); POM (`pom-default.xml`) —
  **0 `<dependency>`**, 0.6.2 (build.gradle не трогал).
- **Mailbox:** заметка #19 (контур + грабли WireMock 3.x + решение
  амбивалентности). Гигиена: удалено моё #15 (решено — README/статус
  эпика уже в ROADMAP/DRAFT/дневниках), остались последние три моих
  (#16, #17, #19).

## Контекст для следующей сессии (#20)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (последний тег
   v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (Новых сообщений Антона не было — mailbox тиш после
   моего #19 ~05:17.)

### Что логично делать дальше
1. **Следующий шаг — `@Retry` (шаг 4 из DRAFT):** аннотация
   `ru.jrestly.annotation.Retry` (`@Target(METHOD)`, `@Retention(RUNTIME)`)
   с атрибутами по ADR Aspect 2: `times` (default 1 — off-switch… см.
   ниже), `initialBackoffMs` (default 200), `maxBackoffMs` (default
   5000), `statuses` (default {408,429,500,502,503,504}), `transport`
   (default true), `onErrorStatuses` (default false), `nonIdempotent`
   (default false). Самостоятельная политика — полностью заменяет
   клиентскую при наличии аннотации (no field-by-field merge).
   Wiring: в `HttpTemplateBuilder.build()` заменить прямое
   `moduleInfo.getRetryPolicy()` на resolve: есть `@Retry` → собрать
   `RetryPolicy` из аннотации, иначе — `moduleInfo.getRetryPolicy()`
   (комментарий-заглушка уже стоит). Валидация — через `RetryPolicy.
   Builder` (Исключение IllegalStateException уже готово). Тесты:
   приоритет override (client-policy включён + `@Retry(times=1)` →
   1 запрос — ADR сценарий 8), POST через `nonIdempotent = true`,
   `@OnError`-opt-in через аннотацию, 429→429→200. Мелочь: default
   `times = 1` в ADR — «explicit off-switch», но сам presence
   аннотации с times=1 = off — проверить формулировку при реализации
   (presence = self-contained policy, times=1 = off).
   Фикстуры: метод с `@Retry` — добавить в `TestController` или в
   отдельный интерфейс (решил на шаге — TestController уже плотный).
2. Дальше: async-твин (шаг 5 — контур над `sendAsync`,
   `CompletableFuture.delayedExecutor`, маппинг исключений без
   изменений; ADR сценарий 9), Javadoc/README/финальный gate (шаг 6).

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт может «пересчитать» реальность** — проверять по jar/
  тегам/исходникам на шаге, править ADR обратно (4 раза у кодеков).
  Обратный случай: **код может не удержать обещание ADR** — тесты
  пишут по букве ADR. Третий «вид» в #19: **амбивалентность в самом
  ADR** (псевдокод vs таблица) — реализация по псевдокоду + строка
  в ADR.
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка. Новый пин #19:
  **WireMock 3.x — `verify(int, pattern)`, `RequestPatternBuilder.times
  (int)` удалено** (standalone 3.12.1, javap).
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** shell-цикл по XML (`build/test-results/test/`);
  python3 в контейнере ЕСТЬ (проверено в #19 — первый вариант сработал;
  sed-парсинг — запасной).
- **Даты/поведение сторонних либ — только по пину.**
- **Parallel-сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion: module default
  + per-method override, override replaces — retry повторяет форму).
- **Аллокации на hot path**: политика читается на каждом запросе —
  default'ы делать shared constants (прецедент: `RetryPolicy.off()`).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш жив;
   `/tmp` не переживает ребут — каталог пересоздавать, первый прогон
   дня качает зависимости заново ~50 с).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 19 → после этой 20).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry; следующий ADR — туда же).

### Состояние git
- HEAD: коммит сессии #19 (sync-контур + клиентский уровень + RetryTest
  + ADR-строка + DRAFT + память) поверх 1f2e9d6 (сессия #18).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **208/208**.
