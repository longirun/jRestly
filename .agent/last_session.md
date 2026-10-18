# Last Session: #259 (2026-10-18)

## Что сделано

**Сессия #259: free-аудит `HttpTemplate` (хвост `exchange()`/`exchangeAsync()`,
pre-wire rejection) — 37-я «тихая» находка: на rejection rate-limiter (timeout)
и open circuit breaker до провода `attempts()` сообщал `1` при нулевых
`onAttemptComplete`-колбэках (нарушен инвариант #195/#258 «attempts() ==
количество onAttemptComplete»); red-first (4 красных, 2 rejection × sync/async)
и минимальный фикс в `HttpTemplate` (sync — `attemptsHolder[0] = 0` в
rejection-catch, async — `0` вместо литерала `1` в `notifyCallComplete`);
ADR-0036 дополнен `## Amendment #259`; гейт 1454/1454, BRANCH 83.48%,
INSTRUCTION 95.95%, LINE 95.74% — новые исторические рекорды.**

1. *Цель:* продолжение free-аудита `HttpTemplate` (класс №1 по missed
   branches, 195 на входе, ветви 79.12%); сужение на pre-wire rejection-путь
   `exchange()`/`exchangeAsync()` — `rateLimiter.acquire()` (timeout) и
   `circuitBreaker.acquirePermission()` (open), оба бросают **до** первой
   передачи на провод.
2. *Находка:* Amendment #195/#258 починили short-circuit- и
   interceptor-failure-пути, но sync-путь падал в общий `catch` с
   `attemptsHolder`, инициализированным `1` (строка 255) и перезаписываемым
   только внутри `executeWithRetries` (до rejection-исключения не доходит) —
   `notifyCallComplete` получал `1`; async-ветки rejection передавали литерал
   `1`. Итог: `attempts() == 1` при `onAttemptComplete` × 0.
3. *TDD red-first:* 4 красных — `RequestPipelineTest.rateLimiterRejection
   PairedMetricsLifecycle`, `circuitBreakerOpenRejectionPairedMetricsLifecycle`
   и async-близнецы: фейки `RejectionRateLimiter` / `OpenCircuitBreaker`
   (синглтон-классы) + `RecordingMetrics` с `onAttemptComplete`. До фикса:
   `expected: <[0]> but was: <[1]>`.
4. *Fix:* sync — acquire в try/catch, `attemptsHolder[0] = 0` перед
   перебросом; async — обе rejection-ветки `notifyCallComplete(..., 0)`. CB
   recording не тронут. Javadoc `CallMetrics.attempts()` переформулирован
   (всегда равен числу `onAttemptComplete`; `0` — short-circuit / упавший
   интерцептор / pre-wire rejection). ADR-0036 дополнен `## Amendment #259`.
5. *Верификация:* `./gradlew test javadoc jacocoTestReport` — **1454/1454**
   (core 1374 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
   benchmarks 15), 0 failures/errors, javadoc чист под `-Werror`.

## Контекст для следующей сессии (#260)

1. **Ветка** `agent-loop`; HEAD — коммит этой сессии (#259).
2. **Highest ADR:** **0065**, next free **0066** (эта сессия добавила поправку
   в ADR-0036, не ADR-0066).
3. **Состояние проекта:** абсолютные рекорды качества: **100.00% Class
   (143/143)**, **100.00% Method (1418/1418)**, **95.95% Instruction**,
   **95.74% Line**, **83.48% Branch**, **80.91% Complexity**. 1454/1454 теста
   зелёные across all 5 modules. Javadoc чист под `-Werror`.
4. **Варианты для следующего шага:**
   - `ControllerValidator#validateMethod` — 25 missed branches (следующий
     по рангу отдельный класс).
   - Остаток `HttpTemplate`: `processResponse` 23, `probePayloadDecode` 20,
     `exchangeAsync` ~14, `exchange` ~13 (193 missed всего, ветви 79.38%).
   - Дальше по рангу: `HttpTemplateBuilder` (164 missed), `MethodMetadata#
     parseMetricsListeners` (13) / класс (69).
   - Формирование цикла 0.11.0: концепт `ADR-0066` (например, декларативная
     эргономика SSE/NDJSON streaming API, LLM stream consumption).
5. **Грабли/паттерны этой сессии (Java/метрики):**
   - `Enum.name()` final — фейки-одиночки в тестах делать **классами**, не
     enum (первая ошибка компиляции сессии);
   - реальная сигнатура `ClientMetricsListener.onAttemptComplete` — 6
     аргументов `(RequestContext, WireRequest, int, Optional<WireResponse>,
     Optional<Throwable>, Duration)`;
   - JaCoCo XML пишется только корнем (submodule'ы — нет); root == aggregate,
     headline-числа брать из `build/reports/jacoco/test/jacocoTestReport.xml`
     (top-level `<counter>`), а не суммировать `<class>`;
   - `:test --tests 'X'` (bare `--tests` падает на `:benchmarks:test`, паттерн
     #188);
   - в контейнере нет `bc` — считать через `awk`/`python3`;
   - префикс номера строки в выводе `read` (`<line>: `) визуально смещает
     отступ — реальный отступ продолжения списка = 2 пробела.
6. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   Счётчик сессий: 259. Часовой пояс контейнера +04.
