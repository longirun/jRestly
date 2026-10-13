# Handover Note: Сессия 21 (async-твин retry + RetryAsyncTest — шаг 5 retry-эпика)

## Что сделано в этой сессии
- **Async-твин retry в `HttpTemplate`** (DRAFT-шаг 5 `[x]`, 214→219):
  `executeAsyncWithRetries` — async-зеркало `executeWithRetries`
  (ADR-0008 Aspect 6): disabled policy → pass-through в
  `executeAsyncWithRedirects`; `attemptAsync` — тот же цикл в форме
  handle-рекурсии, решения побазу идентичны sync: ретраится только
  `IOException` с `retryTransport` и не last; `InterruptedException`
  (fail fast) и любой прочий failure-тип — pass-through в маппинг
  `exchangeAsync`. `Retry-After`/jitter — тот же шов `nextDelayMillis` +
  `ThreadLocalRandom.current()`; DEBUG-строка в формате sync-твина.
- **Ожидание — `CompletableFuture.delayedExecutor`** (ADR буквально):
  `CompletableFuture.runAsync(noop, delayedExecutor(ms))` →
  `thenCompose(следующий attemptAsync)` — без parked virtual thread;
  нулевой jitter-выигрыш — без scheduler-вызова (зеркало sync
  `if (delay > 0)` против `Thread.sleep(0)`).
- **`exchangeAsync` не тронут** — только источник сменился на
  `executeAsyncWithRetries(request)`; `processResponse` и
  `exceptionally`-маппинг (HandledException as-is / IE re-interrupt /
  прочее — `RuntimeException(cause)`) — как есть (ADR: «final exception
  mapping is untouched»).
- **Фикстура:** `RetryController.getWithBareRetryAsync()` — bare `@Retry`
  на async-методе (off-switch проверяется и на async-контуре).
- **`RetryAsyncTest` (5 интеграций, зелёные со второго прогона — первый
  упал на типизации handle, см. грабли):** ADR сценарий 9 — 503→200,
  `join()` вернул значение, 2 запроса (maxAttempts=2); transport-fault→200
  — 2 запроса; exhaustion 503×3 — join-причина остаётся типизированным
  `UnexpectedStatusException` (3 запроса); 404 — 1 запрос; bare `@Retry`
  перебивает enabled-клиента и на async-контуре (1 запрос).
- **Гейты:** `./gradlew test --rerun` — **219/219** (214 + 5; 0
  failures/0 errors по XML, 16 с, тёплый кэш); POM
  (`pom-default.xml`) — **0 `<dependency>`**, 0.6.2.
- **Mailbox:** заметка #21 (async-твин готов, до закрытия эпика один шаг).

## Контекст для следующей сессии (#22)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (Mailbox тиш после моего #21 ~06:00.)

### Что логично делать дальше
1. **Финальный шаг эпика (шаг 6 из DRAFT):** Javadoc-контракт
   (async-упоминание в `RetryPolicy`/`@Retry` — сейчас Javadoc не
   различает sync/async, а ADR Consequences обещает «uniform across
   sync/async» + README «Retry with backoff»: feature bullet + короткая
   usage-секция) + финальный gate (тесты зелёные, POM, дистилляция эпика
   в ROADMAP.md + зачёркивание кандидата в Post-0.5.0 shortlist —
   прецедент кодеков, зачистка DRAFT).
2. После эпика — выбор следующего по ROADMAP (Post-0.5.0 shortlist).

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт может «пересчитать» реальность** — проверять по jar/
  тегам/исходникам на шаге, править ADR обратно (4 раза у кодеков);
  **код может не удержать обещание ADR** — тесты по букве ADR;
  **амбивалентность в самом ADR** — реализация по псевдокоду + строка
  в ADR (#19).
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка. WireMock 3.x —
  `verify(int, pattern)`, `times(int)` удалено.
- **CompletableFuture handle-рекурсия (#21):** lambda `handle`,
  возвращающая следующую стейдж-цепочку, не проходит целевую типизацию —
  явный witness `.<CompletableFuture<T>>handle(...)` +
  `.thenCompose(Function.identity())`; на рантайме handle сам
  разворачивает возвращённый stage (identity лишь чинит тип, исключение
  проходит с original-cause).
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** python3 и bc НЕ пережили ребут — awk/grep-цикл по
  XML-заголовкам: `tests=`, `failures=`, `errors=` из первых строк
  каждого `build/test-results/test/*.xml`.
- **Даты/поведение сторонних либ — только по пину.**
- **Parallel-сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion: module default
  + per-method override, override replaces — retry повторил форму
  ровно).
- **Аллокации на hot path**: default'ы — shared constants
  (`RetryPolicy.off()`); per-request build — только для аннотированных
  методов.
- **Единые предикаты — страховка от разъезда синхронного и асинхронного
  контуров**: `isRetryableStatus`/`retryAfterSeconds`/`nextDelayMillis`
  общие для sync/async (ADR Aspect 3).
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся
  (ребут), первый прогон дня качает зависимости заново (~50 с).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 21 → после этой 22).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry).

### Состояние git
- HEAD: коммит сессии #21 (async-твин + RetryAsyncTest + DRAFT + память)
  поверх 1d37cc4 (сессия #20).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **219/219**.
