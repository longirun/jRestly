# Last Session: #196 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», срез ADR-0036 (метрики) × ADR-0046
(прозрачный 401-рефреш): закрыт non-goal #194 — номера попыток в
`onAttemptComplete`/`onRetry` стали сквозными (per-call). 15-й срез нити,
14-я находка (на этот раз — доведённая до конца отложенная).**

1. *Находка:* после #194 `CallMetrics.attempts()` суммирует обе фазы
   401-рефреша, но слушательские номера оставались пер-фазовыми: для
   503 → 401 → 200 слушатель видел `attempts() == 3`, а третий
   `onAttemptComplete` нумеровался **1** (refresh-фаза стартует со своим
   счётчиком в `executeWithRetries`/`attemptAsync`). Последний номер ≠
   `attempts()`, индексы попыток не уникальны внутри одного вызова — SPI
   рассинхронизирован сам с собой (тот же класс, что #193/#194/#195).
2. *Fix:* `attemptOffset` (число передач первой фазы) прокинут через оба
   контура: `executeWithRetries`/`scheduleRetry` (sync) и
   `executeAsyncWithRetries`/`attemptAsync`/`scheduleAsyncRetry` (async).
   Слушательские номера = `attempt + attemptOffset`; на первом вызове
   offset = 0, в refresh-фазе = `firstPhaseAttempts`. Окно retry-политики,
   backoff (`nextDelayMillis`) и DEBUG-лог `Attempt N/M` — пер-фазовые
   (`maxAttempts` ограничивает каждую фазу, refresh-фаза начинает backoff с
   нуля). Внутренние счётчики `attemptsHolder`/`attemptsRef` не тронуты —
   суммирование #194 работает как было; circuit breaker не пострадал.
3. *Javadoc:* `ClientMetricsListener.onAttemptComplete`/`onRetry` `@param
   attempt` — сквозная нумерация + инвариант «последний номер вызова =
   `CallMetrics.attempts()`». ADR-0036 → Amendment #196; non-goal #194
   помечен «resolved in Amendment #196».
4. *Тесты:* +1 e2e `ClientMetricsWireMockTest.refresh401AfterRetriesContinuousNumbersAsync`
   (async 503→401→200: sequence 1,2,3; onRetry attempt=1; `attempts()==3`);
   3 теста #194 усилены sequence-ассертами (sync 401→200: 1,2; sync
   503→401→200: 1,2,3 + onRetry; async 401→200: 1,2). `RefreshMetricsClient`
   + метод `getSecureRetryAsync` (`@Retry` + `CompletableFuture`).
5. *TDD:* behavior-red — 4 красных ровно по целевым ассертам («refresh
   phase continues the per-call numbering»: expected 2/3 but was 1) →
   зелёный.
6. *Гейты:* `./gradlew test javadoc` — **1222/1222** (core 1142 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
   15), 0 failures, javadoc зелёный под `-Werror`, 0 warnings.

## Технические заметки

- Разделение «per-phase vs per-call» — осознанное: политика ретраев
  (`maxAttempts`, backoff, Retry-After) действует внутри каждой фазы,
  refresh-фаза — новый «вызов» с точки зрения политики; только
  метрик-поверхность сквозная. Закомментировано в `scheduleRetry`.
- Async-путь: offset прокидывается как обычный параметр через
  `attemptAsync`/`scheduleAsyncRetry` — порядок навешивания `whenComplete`
  (CFE-грабли #194) не затрагивает.
- Не тронуты: non-goal #193 (Path/File-тела → -1 вместо размера файла —
  кандидат на engine-reported byte accounting), `Math.max(1, …)`-аналог
  в ветке interceptor-threw (там «вызов состоялся» — 1 осознанно).

## Контекст для следующей сессии (#197)

1. **Свет ADR-0036 выжат** — четыре среза за четыре сессии (#193–#196),
   каждый закрыл находку или non-goal предыдущей. Следующие варианты:
   (a) «стыковой» аудит ADR-0038 (HTTP/2/трейлеры × ADR-0059:
   согласование, границы движков, trailers) — свежий свет;
   (b) ADR-0035 целиком (контракт BodyPublisher: retry-совместимость,
   progress × streaming, nullable publisher);
   (c) кандидат №1, батч №2 test-javadoc (`cookie` 2, `codec` 4,
   `compression` 3, `tracing` 3, `interceptor` 4);
   (d) free-аудит (`ModuleInfo`/`SimpleModuleInfo` мутабельность,
   `ServerSentEventReconnector` жизненный цикл).
2. **Обновлено:** `HttpTemplate` (attemptOffset через sync/async-контуры),
   `ClientMetricsListener` (javadoc `@param attempt` ×2),
   `ClientMetricsWireMockTest` (+1 тест, sequence-ассерты ×3,
   `getSecureRetryAsync`), `docs/0.10.0/ADR-0036` (Amendment #196,
   non-goal #194 → resolved), `DRAFT.md` (запись #196),
   `.agent/days/2026-10-17.md` (сессия #196), `.agent/mailbox.md`
   (сообщение #196), `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #195 (`5c57eed`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 196. Часовой пояс контейнера +04 (может «откатываться»
   между запусками — писать реальный календарный день, 2026-10-17).
