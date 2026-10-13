# Handover Note: Сессия 17 (концепт ADR-0008: Retry with backoff + раскладка шагов DRAFT)

## Что сделано в этой сессии
- **Концепт ADR-0008 (`docs/0.7.0/`) — Retry with backoff**, шаг 1 активного
  эпика. Ключевые решения:
  - **Какие сбои:** транспорт (`IOException` вкл. timeouts) — да;
    `InterruptedException` — нет; статусы — заданный набор (дефолт
    `{408, 429, 500, 502, 503, 504}`); прочие unexpected — нет;
    `@OnError.statuses` — нет по умолчанию.
  - **Порядок с `@OnError` (ответ на ADR-0003):** retry **до**
    классификации — цикл крутит raw `WireResponse`, `processResponse`
    один раз на финальном (декодинг error-body/лог/auth-capture не
    дублируются). Предикаты цикла = предикаты `processResponse`
    (`canParse`/`isExpectedStatus`). `@OnError.statuses` — терминальный
    контракт; escape hatch `retryOnErrorStatuses`; финал всегда типизирован.
  - **Поверхность:** оба уровня по прецеденту `httpVersion`: default
    method `ModuleInfo.getRetryPolicy()` (non-breaking) + `retry(...)` в
    builders + аннотация `@Retry` (самоценная замена, `times = 1` =
    off-switch). Дефолт — **off** (0.x без изменения поведения).
  - **Backoff:** exponential с cap + full jitter (AWS-рецепт); fixed —
    частный случай (равные границы), отдельного режима нет;
    `Retry-After` (integer seconds) — `max(delay, retryAfter)`; чистая
    математика с package-private швом (injectable `Random`).
  - **Идемпотентность:** по RFC 9110 GET/PUT/DELETE — да, POST/PATCH —
    `retryNonIdempotent`.
  - **Шов реализации:** `executeWithRetries` об `executeWithRedirects`;
    повторная отправка `WireRequest` безопасна (fresh `HttpRequest`,
    per-send publisher, boundary стабилен); async — chain +
    `CompletableFuture.delayedExecutor` (не блокирует вирт. потоки).
  - **Тесты:** юниты backoff без WireMock + 10 интеграционных сценариев
    (scenarios + `withFault(CONNECTION_RESET_BY_PEER)`), новых
    test-зависимостей нет.
- **DRAFT.md:** концепт `[x]`; добавлены 5 шагов реализации:
  1) `RetryPolicy` + `RetryPolicyTest`; 2) sync-контур + клиентский
  уровень + `RetryTest`; 3) `@Retry` + wiring + тесты; 4) async-твин +
  тесты; 5) Javadoc/README/финальный gate. Мелочь-гигиена: «Пометки из
  mailbox» — дата 2026-10-12 → 2026-10-12–13 (4-й пункт от 10-13).
- **Mailbox:** заметка #17 (04:15) с главным решением для Антона;
  гигиена: удалено своё #14 (инсайт 2.8.3 уже в ADR-0007/дневниках),
  осталось 3 моих (#15–#17).
- **Кода нет** (ADR/DRAFT/mailbox/память). `./gradlew test --rerun` на
  входе: **183/183** (0 failures/errors по XML, 14 с, тёплый кэш).

## Контекст для следующей сессии (#18)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (последний тег
   v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким (последние 2–3
   своих сообщения + живые нитки), расхождения — чинить сразу.
   (Новых сообщений Антона не было — mailbox тиш после 04:15.)

### Что логично делать дальше
1. **Следующий шаг — `RetryPolicy` + юниты backoff** (DRAFT, шаг 2).
   Изолированный кусок без WireMock: immutable value class + builder +
   валидация (`maxAttempts ≥ 1`, `initialBackoff ≤ maxBackoff`),
   `defaults()` (3 attempts, 200 ms → 5 s cap, дефолт-статусы) и `off()`
   (`maxAttempts = 1`), чистая backoff-функция (exponential с cap,
   full jitter, `Retry-After` max()) с package-private швом + injectable
   `Random` для детерминированного jitter. `RetryPolicyTest` — по пунктам
   ADR Aspect 4/7. Код в `ru.jrestly.http` (рядом с `HttpTemplate`).
2. Дальше по чекбоксам DRAFT: sync-контур (шаг 3), `@Retry` (шаг 4),
   async (шаг 5), Javadoc/README/gate (шаг 6).

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт может «пересчитать» реальность** — проверять по jar/тегам/
  исходникам на шаге, править ADR обратно (было 4 раза: Gson java.time,
  Gson 2.8.3, фантом moshi-adapters, Moshi-коллекции).
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** shell-цикл по XML (`build/test-results/test/`).
- **Даты/поведение сторонних либ — только по пину.**
- **Parallel-сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (новая, #17): спорные
  решения концепта сначала ищи в коде — форма `httpVersion`
  (module default + per-method override, override replaces) закрыла
  вопрос builder/аннотация для retry.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш жив;
   `/tmp` не переживает ребут — каталог пересоздавать, первый прогон
   дня качает зависимости заново ~50 с).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 17 → после этой 18).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry; следующий ADR — туда же).

### Состояние git
- HEAD: коммит сессии #17 (концепт ADR-0008 + DRAFT + память) поверх
  c887dda (сессия #16, gate эпика Gson/Moshi).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **183/183** (Java не тронута в #17).
