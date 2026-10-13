# Handover Note: Сессия 22 (финальный gate retry-эпика — закрыт; DRAFT → «0.x polish»)

## Что сделано в этой сессии
- **Эпик Retry with backoff ЗАКРЫТ** (DRAFT-шаг 6 `[x]`, последний).
- **Javadoc-контракт sync/async** (ADR-0008 Consequences «uniform across
  sync/async» доведено до поверхности API): `RetryPolicy` — новый абзац
  (одна политика — оба стиля вызовов; различие только в том, как тратится
  ожидание: sync `Thread.sleep` vs async `delayedExecutor` без parked
  virtual thread; решения/backoff/финальное исключение — общие);
  `@Retry` — абзац о тождественности sync/async; `ModuleInfo.
  getRetryPolicy()` — «applies to both synchronous and
  CompletableFuture-returning controller methods». `JRestlyClient.Builder`
  / `SimpleModuleInfo.Builder` не трогал (house style: proxy-builders без
  Javadoc, контракт на value-классе/аннотации/SPI).
- **README:** фича-буллет retry (после redirect-chasing) + секция
  «Retry with backoff» (между Quick start и Multipart): off by default,
  единый снippet обоих уровней (`retry(...)` client + `@Retry` per-method,
  self-contained + bare-off-switch), что ретраится (транспорт,
  default-семейство, `@OnError`-терминальность + escape hatch, POST/PATCH
  по RFC 9110), backoff + `Retry-After` + «classified exactly once» +
  sync/async uniformity.
- **ROADMAP:** новый раздел «Retry with backoff — COMPLETE ✅
  (2026-10-13, unreleased)» (стиль Gson/Moshi: source of truth ADR-0008,
  сессии #17–#22, без версии/тега) — ключевые решения + тестовый
  периметр (219/219). Post-0.5.0 shortlist: кандидат зачёркнут →
  **shortlist выработан целиком (все три пункта)**.
- **DRAFT зачищен** (прецедент #16): новый активный эпик **0.x polish —
  carry-over quirks and accepted known gaps**, шаг 1 = концепт ADR-0009
  (scope: что в пол, что остаётся gap'ом): (1) `logRequest` — PUT не
  логируется (подтверждено по коду, `HttpTemplate` line 536:
  `Arrays.asList(POST, PATCH, DELETE)`); (2) `processResponse` — только
  `Void.class.equals(returnType)` (line 165), маскируется empty-payload
  early return'ом (204); (3) `Retry-After` HTTP-date (Known gap ADR-0008);
  (4) payload-decode retry (Known gap ADR-0008). Правило ADR-0003:
  reopening known gap = новый ADR.
- **Гейты:** `./gradlew test --rerun` — **219/219** (0 failures/0 errors по
  XML, тёплый кэш); POM (`pom-default.xml`) — **0 `<dependency>`**, 0.6.2.
- **Mailbox:** заметка #22 (эпик закрыт, shortlist выработан, следующий
  эпик). Гигиена: удалены #17 и #19 (реализованные статусы, контент — в
  ROADMAP/ADR); осталось #20, #21, #22 + правила Антона.

## Контекст для следующей сессии (#23)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (Mailbox тиш после моего #22 ~06:25.)

### Что логично делать дальше
1. **ADR-0009 (шаг 1 нового эпика):** scope-решение по четырём
   кандидатам из DRAFT — что чиним, что остаётся known gap'ом. Моя
   предварительная интуиция (не решение!): (1) PUT-лог — чиним
   (однострочник, behavior-расхождение, «accepted quirk» давно висит);
   (2) `void.class` — чиним (одна строка `|| void.class.equals(...)` — но
   проверить, как `HttpTemplateBuilder` резолвит return type: если
   proxy-метод с `void` никогда не доходит до decode с телом, фикс =
   страховка, всё равно дешёвая); (3) HTTP-date `Retry-After` — чиним
   (формат RFC 9110 `HTTP-date = IMF-fixdate / rfc1123 / asctime` —
   парсить честно по `ZonedDateTime.parse` с перечнем форматов, known gap
   закрыт); (4) payload-decode retry — вероятно, ОСТАВЛЯЮ gap (decode
   сбой = кодек не понял финальный ответ; ретрай «на всякий» меняет
   контракт v1 — дорого для 0.x, пусть ADR так и запишет). Тестовая
   стратегия — по прецеденту (WireMock + юниты парсера HTTP-date).
2. Дальше — фиксы по шагам DRAFT.

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт может «пересчитать» реальность** — проверять по
  jar/тегам/исходникам на шаге, править ADR обратно (серия из 4+ раз);
  **код может не удержать обещание ADR** — тесты по букве ADR;
  **амбивалентность в самом ADR** — реализация по псевдокоду + строка в ADR.
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка. WireMock 3.x —
  `verify(int, pattern)`, `times(int)` удалено.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function.identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** python3 и bc НЕ пережили ребут — awk/grep-цикл по
  XML-заголовкам: `tests=`, `failures=`, `errors=` из первых строк
  каждого `build/test-results/test/*.xml`.
- **Даты/поведение сторонних либ — только по пину.**
- **Parallel-сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc — контракт на value-классе).
- **Аллокации на hot path**: default'ы — shared constants
  (`RetryPolicy.off()`).
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся
  (ребут), первый прогон дня качает зависимости заново (~50 с).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 22 → после этой 23).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry; ADR-0009 — будет в `0.7.0/`).

### Состояние git
- HEAD: коммит сессии #22 (Javadoc sync/async + README + ROADMAP-дистиллят
  + DRAFT «0.x polish» + память) поверх 1445e79 (сессия #21).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **219/219**.
