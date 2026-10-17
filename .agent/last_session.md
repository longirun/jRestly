# Last Session: #194 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», срез ADR-0036 (метрики) × ADR-0046 (прозрачный
401-рефреш): 13-й срез нити, 12-я «тихая» находка.**

1. *Находка:* ADR-0036 §2 — `attempts()` «total attempts executed». 401-
   рефреш (ADR-0046) — две фазы передачи: первый
   `executeWithRetries`/`executeAsyncWithRetries` и второй после
   `tokenManager.invalidate()`. Фазы делили **один** счётчик
   (`int[] attemptsHolder` в `exchange()`, `AtomicInteger attemptsRef` в
   `exchangeAsync()`) — вторая фаза **затирает** первую. Слушатель видел
   `attempts() == 1` и `isRetried() == false` для вызова, физически ушедшего
   на провод дважды; с `@Retry`: 503 → 401 → 200 отчитывался как 1 вместо 3.
   Кандидат (a) из заметок #193 подтверждён на обоих контурах.
2. *Fix:* фазы суммируются. Sync: `firstPhaseAttempts` сохраняется перед
   вторым `executeWithRetries`, после — `attemptsHolder[0] = first + holder[0]`.
   Async: значение берётся в 401-ветке, и `secondPhase.whenComplete(...)`
   прибавляет его через `accumulateAndGet` при завершении второй фазы (успех
   **или** сбой) — навешивается раньше продолжения `thenCompose`, поэтому
   сумма готова до `notifyCallComplete` (порядок зависимостей CFE).
   `executeWithRetries`/`executeAsyncWithRetries` не тронуты (контракт
   «записать своё число попыток» сохранён). Javadoc `CallMetrics.attempts()`
   уточнён (refresh-фаза входит в сумму; «at least 1» как было).
   ADR-0036 → Amendment #194.
3. *Тесты:* +3 в `ClientMetricsWireMockTest` (новый публичный-в-тесте
   интерфейс `RefreshMetricsClient`): `refresh401SumsAttemptsAcrossPhases`
   (sync 401→200, ожидаемо 2), `refresh401AfterRetriesSumsBothPhases` (sync
   503→401→200 с `@Retry(times=3)`, ожидаемо 3; один `onAttemptComplete` на
   передачу — 3 записи, один `onRetry`), `refresh401SumsAttemptsAcrossPhasesAsync`
   (async, ожидаемо 2).
4. *TDD:* behavior-red на старом коде — ровно по целевым ассертам
   (expected 2/3 but was 1; `isRetried()` false) → зелёный после фикса.
5. *Гейты:* `./gradlew test javadoc` — **1220/1220** (core 1140 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
   15), 0 failures, javadoc зелёный под `-Werror`, 0 warnings.

## Технические заметки

- Non-goal Amendment #194: номера `attempt` в `onAttemptComplete`/`onRetry`
  стартуют с 1 в refresh-фазе (per-phase, не per-call) — кандидат на
  отдельный срез (семантика нумерации против сквозного счётчика).
- Circuit breaker не пострадал: использует `attempts` только как `> 0`
  (булево), сумма ничего не меняет.
- Async-фикс не требует синхронизации: `accumulateAndGet` атомарен, а
  `whenComplete` на `secondPhase` гарантированно отрабатывает до
  терминальных стадий (порядок attach).
- Кандидат (b) из #193 не тронут: `onCallStart` срабатывает и при
  short-circuit (запрос на провод не уходил) — против javadoc «before the
  initial request is initiated». Кандидат (c) — `SimpleCallMetrics` строится
  вне try — всё ещё неактуален (clamp держит ≥ 1).

## Контекст для следующей сессии (#195)

1. **Свободный ход.** Варианты: (a) «стыковой» аудит — ADR-0038
   (HTTP/2/трейлеры × ADR-0059: согласование, границы движков, trailers на
   HTTP/2) или ADR-0035 целиком (контракт BodyPublisher: retry-
   совместимость, progress × streaming, nullable publisher);
   (b) нумерация `onAttemptComplete`/`onRetry` в refresh-фазе (non-goal
   #194); (c) `onCallStart` при short-circuit (кандидат #193 b);
   (d) кандидат №1, батч №2 test-javadoc (`cookie` 2, `codec` 4,
   `compression` 3, `tracing` 3, `interceptor` 4);
   (e) free-аудит (`ModuleInfo`/`SimpleModuleInfo` мутабельность,
   `ServerSentEventReconnector` жизненный цикл).
2. **Обновлено:** `HttpTemplate` (sync + async сумма фаз), `CallMetrics`
   (javadoc `attempts()`), `ClientMetricsWireMockTest` (+3 теста,
   `RefreshMetricsClient`), `docs/0.10.0/ADR-0036` (Amendment #194),
   `DRAFT.md` (запись #194), `.agent/days/2026-10-17.md` (сессия #194),
   `.agent/mailbox.md` (сообщение #194, гигиена: удалено #191),
   `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #193 (`de65708`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 194. Часовой пояс контейнера +04 (может «откатываться»
   между запусками — писать реальный календарный день, 2026-10-17).
