# Handover Note: Сессия 20 (@Retry аннотация + wiring + тесты — шаг 4 retry-эпика)

## Что сделано в этой сессии
- **`ru.jrestly.annotation.Retry`** (новый публичный API, DRAFT-шаг 4 `[x]`,
  208→214 тестов): `@Target(METHOD)`, `@Retention(RUNTIME)`; атрибуты по ADR
  Aspect 2 — `times` (default **1**), `initialBackoffMs` (200),
  `maxBackoffMs` (5_000), `statuses` ({408,429,500,502,503,504}),
  `transport` (true), `onErrorStatuses` (false), `nonIdempotent` (false).
  Нормативный Javadoc: self-contained policy (presence заменяет клиентскую
  целиком, no merge), off-switch, что ретрается, backoff-кривая, Retry-After.
- **Интерпретация флажка из #19 зафиксирована:** presence = self-contained
  policy, default `times = 1` = off; любое настоящее retry-объявление пишет
  `times >= 2` явно. Альтернатива (default 3, bare = retry с дефолтами)
  отвергнута — bare-аннотация, меняющая поведение, против 0.x «retry is a
  choice». В ADR строки про default не было → решение записано в Javadoc
  аннотации (source of truth для API), в ADR не лез.
- **Wiring** — `HttpTemplateBuilder.resolveRetryPolicy()`: presence `@Retry`
  → `RetryPolicy.Builder` из атрибутов (валидация — готовая
  `IllegalStateException` из builder'а; нового кода валидации нет), иначе —
  `moduleInfo.getRetryPolicy()`. Коммент-заглушка из #19 заменена.
  Аллокация: политика строится на запрос только для аннотированных методов
  (house style per-request build, как `createOnErrorParser`); остальные
  методы — shared `off()` без аллокаций.
- **Фикстура** — `RetryController` (отдельный интерфейс, прецедент
  `HttpVersionController`; TestController уже ~60 методов): 5 методов по
  ролям — bare `@Retry` (off-switch), self-contained GET `/api/rate-limited`,
  POST `nonIdempotent`, `@OnError({429})` + `onErrorStatuses=true`,
  `statuses={501}` (custom set). Backoff'ы 10–50 ms.
- **`RetryAnnotationTest`** (6 интеграций, зелёные с первого прогона):
  bare `@Retry` перебивает enabled-клиент (503 → 1 запрос; ADR сценарий 8 +
  закреплён default times=1); self-contained 429→429→200 при off-клиенте
  (3 запроса — «presence = self-contained»); POST через `nonIdempotent`
  (503→503→200, 3 запроса — вторая половина ADR-сценария 7);
  `onErrorStatuses`-opt-in (429→429→200, 3 запроса); exhaustion с declared
  429 → типизированный `HandledException` (не UnexpectedStatusException,
  3 запроса — вторая половина ADR-сценария 6); `statuses={501}` — 501
  ретраится (2 запроса; замена дефолтного семейства, не merge).
- **Javadoc `RetryPolicy`:** «(implementation: a later step of the same
  ADR)» → «the annotation is a self-contained policy … times = 1 default is
  the explicit off-switch» (заглушка устарела — шаг реализован).
- **Гейты:** `./gradlew test --rerun` — **214/214** (208 + 6; 0
  failures/0 errors по XML); POM (`pom-default.xml`) — **0 `<dependency>`**,
  0.6.2.
- **Mailbox:** заметка #20 (поверхность закрыта с обеих сторон + осознанный
  выбор default times=1). Гигиена: удалено моё #16 (решено — эпик закрыт,
  инсайты в ROADMAP/DRAFT/дневниках), остались #17 (живая нитка — ответ на
  ADR-0003 «интегрируется с @OnError»), #19 и новое #20.

## Контекст для следующей сессии (#21)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (последний тег
   v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (Mailbox тиш после моего #20 ~05:45.)

### Что логично делать дальше
1. **Следующий шаг — async-твин (шаг 5 из DRAFT):** контур над
   `sendAsync` (ADR Aspect 6: «the same loop chained over
   `sendAsync`; the backoff wait runs on
   `CompletableFuture.delayedExecutor` (non-blocking — no parked virtual
   thread per retry), and exchangeAsync's final exception mapping is
   untouched»). Скорее всего: `exchangeAsync`/async-контур
   `HttpTemplate` — найти, где сейчас async идёт (поиск:
   `sendAsync`, `isAsync()` в `HttpTemplate`/`AbstractHttpClient`),
   дублировать sync-цикл `executeWithRetries` в async-форме (chain:
   attempt → catch/thenCompose → delayedExecutor(nextDelay) → retry;
   `Retry-After` — тот же `max()`-правило; `InterruptedException` —
   маппинг без изменений, как в sync). Тесты — ADR сценарий 9:
   `CompletableFuture`-метод, 503 → 200 → `join()` возвращает значение,
   2 запроса (в `RetryAnnotationTest`/`RetryTest` рядом с sync-тестами —
   решение на шаге; fixture-методы с `CompletableFuture`-возвратом уже
   есть у `TestController`: `getServerErrorAsync` и т.п.).
2. Потом финальный: Javadoc-контракт + README («Retry with backoff» —
   ADR Consequences: feature bullet + короткая usage-секция) + финальный
   gate (тесты, POM, дистилляция эпика в ROADMAP, зачистка DRAFT).

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт может «пересчитать» реальность** — проверять по jar/
  тегам/исходникам на шаге, править ADR обратно (4 раза у кодеков);
  **код может не удержать обещание ADR** — тесты по букве ADR;
  **амбивалентность в самом ADR** — реализация по псевдокоду + строка
  в ADR (#19).
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка. WireMock 3.x —
  `verify(int, pattern)`, `times(int)` удалено.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** python3 НЕ пережил ребут (пропал, как и
  GRADLE_USER_HOME) — grep-цикл по XML-заголовкам:
  `tests=`, `failures=`, `errors=` из первых строк каждого
  `build/test-results/test/*.xml`.
- **Даты/поведение сторонних либ — только по пину.**
- **Parallel-сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion: module default
  + per-method override, override replaces — retry повторил форму
  ровно).
- **Аллокации на hot path**: default'ы — shared constants
  (`RetryPolicy.off()`); per-request build — только для аннотированных
  методов.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся
  (ребут), первый прогон дня качает зависимости заново (~50 с).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 20 → после этой 21).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry).

### Состояние git
- HEAD: коммит сессии #20 (@Retry + wiring + RetryAnnotationTest +
  RetryPolicy Javadoc + DRAFT + память) поверх 0e3ee7e (сессия #19).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **214/214**.
