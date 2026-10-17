# Last Session: #195 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», срез ADR-0036 (метрики) × ADR-0047
(short-circuit): 14-й срез нити, 13-я «тихая» находка. Кандидат (c) из
#193 подтверждён и закрыт.**

1. *Находка:* ADR-0047 «Network Bypass» — «Number of network attempts is
   tracked as `0`». Во внутренних счётчиках так и есть
   (`attemptsHolder[0] = 0` в `exchange()`, `attemptsRef.set(0)` в
   `exchangeAsync()`), но слушатель видел `attempts() == 1`:
   `notifyCallComplete` клэмпил `Math.max(1, attempts)`, чтобы пережить
   компактный конструктор `SimpleCallMetrics` с валидацией
   `attempts < 1 → IAE`. SPI противоречил самому себе в рамках одного
   вызова: `attempts() == 1` при **нулевом** числе `onAttemptComplete`
   (передач на провод не было); `onCallStart` срабатывал, хотя запрос на
   провод не уходил (javadoc: «before the initial request is initiated»).
   Circuit breaker 0 уже обрабатывал корректно (`attempts > 0` sync,
   `shortCircuited == null` async) — расходились только метрики.
2. *Fix:* clamp убран — `attempts()` = 0 для short-circuit (равно числу
   `onAttemptComplete`), иначе ≥ 1. `SimpleCallMetrics`: валидация
   `attempts >= 0` (ослабление — обратно совместимо) + javadoc `@param`.
   Javadoc `CallMetrics.attempts()` (0 для short-circuit, ADR-0047) и
   `ClientMetricsListener.onCallStart` (fire once per logical call, в т.ч.
   при short-circuit; бьётся 1:1 с `onCallComplete`). ADR-0036 →
   Amendment #195.
3. *Тесты:* +1 `ShortCircuitExecutionTest.shortCircuitMetricsLifecycle`
   (sync + async: onCallStart×1, onAttemptComplete×0, onCallComplete×1,
   attempts()==0, isRetried()==false); 2 ассерта обновлены:
   `shortCircuitInvokesResponseInterceptorAndMetrics` (1→0, display-name
   «with attempts=0» наконец совпал с кодом) и
   `ClientMetricsValidationTest.testSimpleCallMetricsValidation` (attempts=0
   валидно, -1 — IAE).
4. *TDD:* red-first — 3 красных ровно по целевым ассертам (e2e ×2: expected
   0 but was 1; unit: IAE на attempts=0) → зелёный.
5. *Гейты:* `./gradlew test javadoc` — **1221/1221** (core 1141 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
   15), 0 failures, javadoc зелёный под `-Werror`, 0 warnings.

## Технические заметки

- Маркер чистоты среза: display-name старого теста
  `shortCircuitInvokesResponseInterceptorAndMetrics` уже гласил «with
  attempts=0» (сформулирован по ADR-0047), а ассерт держал 1 (под clamp) —
  «название против ассерта» — хороший детектор для будущих срезов.
- Не тронута ветка interceptor-threw (`notifyCallComplete(..., 1)` на
  строке ~197): там «вызов состоялся, interceptor упал до провода» — 1
  осознанно, не short-circuit.
- Пер-фазовая нумерация `onAttemptComplete`/`onRetry` в refresh-фазе
  (non-goal #194) не тронута — кандидат на отдельный срез.
- `isRetried()` при attempts==0 → false (`attempts() > 1`) — корректно.

## Контекст для следующей сессии (#196)

1. **Свободный ход.** Варианты: (a) «стыковой» аудит — ADR-0038
   (HTTP/2/трейлеры × ADR-0059: согласование, границы движков, trailers на
   HTTP/2) или ADR-0035 целиком (контракт BodyPublisher: retry-
   совместимость, progress × streaming, nullable publisher);
   (b) пер-фазовая нумерация `onAttemptComplete`/`onRetry` в refresh-фазе
   (non-goal #194);
   (c) кандидат №1, батч №2 test-javadoc (`cookie` 2, `codec` 4,
   `compression` 3, `tracing` 3, `interceptor` 4);
   (d) free-аудит (`ModuleInfo`/`SimpleModuleInfo` мутабельность,
   `ServerSentEventReconnector` жизненный цикл).
2. **Обновлено:** `HttpTemplate` (clamp убран), `SimpleCallMetrics`
   (валидация >= 0 + javadoc), `CallMetrics` (javadoc `attempts()`),
   `ClientMetricsListener` (javadoc `onCallStart`),
   `ShortCircuitExecutionTest` (+1 тест, 1 ассерт 1→0, импорты
   Duration/RequestContext), `ClientMetricsValidationTest` (ассерт
   0-валидно/-1-IAE), `docs/0.10.0/ADR-0036` (Amendment #195),
   `DRAFT.md` (запись #195), `.agent/days/2026-10-17.md` (сессия #195),
   `.agent/mailbox.md` (сообщение #195, гигиена: удалено #192),
   `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #194 (`30f6fbf`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 195. Часовой пояс контейнера +04 (может «откатываться»
   между запусками — писать реальный календарный день, 2026-10-17).
