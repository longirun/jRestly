# DRAFT: Retry with backoff (Post-0.5.0 shortlist, ADR-0003)

> Эпик открыт в сессии #16 (2026-10-13) после закрытия эпика
> Gson/Moshi-кодеков (дистиллят — раздел «Gson / Moshi codec adapters» в
> ROADMAP.md; концепт и все пины — ADR-0007 в `docs/0.7.0/`). Норма объёма:
> по одному шагу за сессию. Тегов нет, версия 0.6.2 (релизы на паузе).
> ADR-0003 называет retry единственной resilience-фичей в shortlist'е и
> подчёркивает, что она «интегрируется с `@OnError`» — с этой интеграции и
> начинается концепт.

## Шаги эпика

- [x] Концепт: ADR-0008 (`docs/0.7.0/`) — retry with backoff: какие сбои
      ретраятся (транспорт vs unexpected status vs ошибки, обработанные
      `@OnError`), поверхность конфигурации (client-level builder /
      per-method аннотация / оба), порядок с `@OnError` (retry до или после
      обработки?), backoff-кривая (fixed/exponential, cap), идемпотентность
      (ретраем ли PUT/PATCH/POST — только GET или всё?), тестовая стратегия.
      Ключевые решения (2026-10-13, сессия #17): retry **до** классификации —
      цикл работает над raw `WireResponse`, `processResponse` один раз на
      финальном ответе; `@OnError.statuses` — терминальный контракт по
      умолчанию (escape hatch: `retryOnErrorStatuses`); клиентский уровень —
      `ModuleInfo` default method (non-breaking) + `@Retry` — самоценная
      per-method замена; кривая — exponential с cap + full jitter, fixed —
      частный случай (равные границы); идемпотентность — по RFC 9110:
      GET/PUT/DELETE ретраятся, POST/PATCH — через `nonIdempotent`.
- [x] Код: `RetryPolicy` (immutable + builder + валидация; чистая backoff-
      математика: exponential с cap, full jitter с injectable `Random`,
      `Retry-After` max()-правило; `defaults()` / `off()`) +
      `RetryPolicyTest` (юнит, без WireMock). Сделано в сессии #18 (18→198
      тестов, 183→198, +15): шов `nextDelayMillis(attempt,
      retryAfterSeconds, random)` — package-private, Random приходит
      аргументом (policy без состояний); Retry-After — детерминированный
      `max(bound, retryAfter)` без jitter (ADR Aspect 4 уточнён);
      валидация: `maxAttempts ≥ 1`, backoff'ы non-negative и
      `initial ≤ max`, статусы в `[100, 599]`, коллекция копируется
      (LinkedHashSet → unmodifiable). `isEnabled()` — предикат для контура.
- [x] Код: sync-retry-контур в `HttpTemplate` (транспорт + статусы,
      `@OnError`-терминальность по умолчанию, `Retry-After`, DEBUG-лог) +
      клиентский уровень (default method в `ModuleInfo`, `retry(...)` в
      обоих builders) + `RetryTest` (WireMock: 503→503→200, исчерпание,
      transport-fault, неретраемый 404, `@OnError`-терминальность,
      идемпотентность, `Retry-After`). Сделано в сессии #19 (198→208):
      `executeWithRetries` об `executeWithRedirects` — цикл по raw-ответам
      (ADR Aspect 6 буквально: `IOException` → retryTransport и не last;
      `InterruptedException` → fail fast; статус → `isRetryableStatus`);
      `processResponse` не тронут — один раз на финале. `isRetryableStatus`
      — те же предикаты, что у `processResponse`: expected → `@OnError`-
      терминальность (если не `retryOnErrorStatuses`) → retryable set →
      идемпотентность POST/PATCH. `Retry-After` — только integer-seconds
      (HTTP-date — known gap); DEBUG-лог в формулировке ADR; sleep через
      `Thread.sleep`, jitter — `ThreadLocalRandom.current()` в шов.
      Клиентский уровень: default method `ModuleInfo.getRetryPolicy()` →
      `off()` (non-breaking), `retry(...)` в `SimpleModuleInfo.Builder` и
      `JRestlyClient.Builder` (проксирование по прецеденту `httpVersion`);
      `off()` — shared constant (политика читается на каждом запросе —
      без аллокаций на hot path). RetryTest: 10 интеграций (ADR Aspect 7,
      сценарии 1–7 и 10 + «off by default»), WireMock-scenarios для
      последовательных статусов и fault; backoff'ы 10–50 ms, Retry-After
      проверяется wall-clock'ом (elapsed ≥ 1 s).
- [x] Код: аннотация `@Retry` (per-method override, `nonIdempotent`,
      `times = 1` — явный off-switch) + wiring в `HttpTemplateBuilder` +
      тесты (приоритет override, POST через `nonIdempotent`,
      `@OnError`-opt-in, 429→429→200). Сделано в сессии #20 (208→214):
      `ru.jrestly.annotation.Retry` — self-contained policy по ADR Aspect 2
      (при presence заменяет клиентскую целиком, no merge; default
      `times = 1` = явный off-switch — интерпретация «presence =
      self-contained, times=1 = off» зафиксирована в Javadoc аннотации);
      wiring — `HttpTemplateBuilder.resolveRetryPolicy()` (есть `@Retry` →
      `RetryPolicy.Builder` из атрибутов, валидация `IllegalStateException`
      уже в builder'е; иначе — `moduleInfo.getRetryPolicy()`), аллокация
      политики на запрос — только для аннотированных методов (house style
      per-request build). Фикстура — отдельный `RetryController` (прецедент
      `HttpVersionController`); `RetryAnnotationTest`: 6 интеграций (bare
      `@Retry` перебивает enabled-клиент → 1 запрос; self-contained
      429→429→200 при off-клиенте → 3; POST через `nonIdempotent` → 3;
      `onErrorStatuses`-opt-in 429→429→200 → 3; exhaustion с declared 429 →
      типизированный `HandledException`, 3 запроса; `statuses={501}` —
      кастомный статус ретраится → 2). Javadoc `RetryPolicy` обновлён
      («later step» → реализовано).
- [ ] Код: async-твин (контур над `sendAsync`, неблокирующий backoff через
      `CompletableFuture.delayedExecutor`, маппинг исключений без изменений)
      + тесты retry на `CompletableFuture`-методах
- [ ] Javadoc-контракт + README («Retry with backoff») + финальный gate
      (тесты зелёные, POM без зависимостей, дистилляция в ROADMAP)

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
      лучше — в этом файле и вообще на проекте»). Практика: mailbox не
      растёт бесконечно (применять правило «решённый обмен удаляем» в каждой
      сессии, где он применим); устаревшие «Пометки из mailbox» в DRAFT
      сверять с реальностью; мелкие расхождения (версии, статусы) чинить
      сразу, не копить.

Пометки из mailbox (Антон, 2026-10-12–13, актуальные):
- ADR-0006 (java.time wire contract) **не требуется** — вопрос закрыт.
- **Версии — по нашему усмотрению** (сейчас 0.6.2), **теги не вешать**,
  чтобы либа не ушла в релизы и не спутали с главным релизом.
- **Объём одной сессии сократить в 2–3 раза** — брать меньше, делать чаще.
- **Чистку mailbox Антон подтвердил** (2026-10-13) — правило из «постоянной
  нити» выше в силе.

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место.
