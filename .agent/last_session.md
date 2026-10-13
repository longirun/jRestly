# Handover Note: Сессия 32 (payload-decode шаг 2: RetryPolicy.retryDecodeFailures + @Retry-атрибут + шов probePayloadDecode + юниты; 242→250/250, DRAFT — активный эпик, 2/5)

## Что сделано в этой сессии
- **Вводная (по факту).** Mailbox тиш после #31 (12:25) — входящего от
  Антона нет (его 11:20-ответ уже обработан в ADR-0011). HEAD = коммит #31
  (`ad4ab13`), дерево чистое (кроме session_counter). Перед правкой
  прочитал реальность по коду: `processResponse` (порядок decode-ветки:
  auth → log → `@OnError` → status-check → null-payload → String → Void/void
  → `deserialize`), `isExpectedStatus` (default 2xx, когда
  `expectStatuses == null`), `RetryPolicy` (builder-стиль), `@Retry`
  (self-contained), `HttpTemplateBuilder.resolveRetryPolicy()` (mapping
  атрибут→policy).
- **Код (трёхфайловая surface + шов).**
  - `RetryPolicy`: `retryDecodeFailures` (default `false`) — поле,
    конструктор, getter `isRetryDecodeFailures()`, builder-метод,
    `toString`; Javadoc-буллет в «what is retried»: отдельный opt-in,
    потому что probe ходит по КАЖДОМУ success-response (остальные оси — по
    failed) ⇒ «enable retry» = zero change на success-path (Aspect 4
    ADR-0011).
  - `@Retry`: атрибут `retryDecodeFailures` (default `false`) + нормативный
    Javadoc (success-path, double-decode-cost на финале, `@OnError` вне
    scope — его terminal-контракт ортогонален флагу, финальное исключение =
    codec-своё unchecked без нового класса) + class-level буллет.
  - `HttpTemplate.probePayloadDecode(WireResponse)` — package-private (шов
    класса `retryAfterDelayMillis`), после `isNonIdempotent`. Чистый
    предикат: `@OnError` → true; `!isExpectedStatus` → true; payload==null
    → true; String → true; Void/void → true; иначе
    `jsonCodec.deserialize(payload, returnType)` — исключение кодек-а
    проходит без обёртки. Те же предикаты/поля, что `processResponse`
    (`onErrorParser`, `isExpectedStatus`, `returnType`) — ADR-0008 Aspect 3:
    не могут разъехаться. Строка payload-конверсии продублирована
    дословно (empty-body-семантика — часть «тех же предикатов»).
- **Тесты (8 новых, зелёные с первого прогона).**
  - `HttpTemplateDecodeProbeTest` (7 юнитов, `ru.jrestly.http`, без
    WireMock, stub-кодек через public `setJsonCodec`): expected-decodable →
    true (кодек ровно 1 раз); unbindable → `assertSame` на codec-исключение
    (no wrapping, no new type — Aspect 3 на уровне юнита); `@OnError`/
    unexpected/пусто/String/Void/void → true и `deserializeCalls == 0`.
    Ключевая форма фикстуры: template намеренно без logger (null) и без
    auth-consumer — если probe когда-нибудь логнет/захватит auth, тесты
    NPE'ят: «side-effect-free» зафиксировано формой, не ассертом.
  - `RetryPolicyTest` +1: default off в off()/defaults()/builder + сеттер.
- **Гейты.** `./gradlew test --rerun` — **250/250** (242→250, +8; 0
  failures/0 errors по XML, 22 s); POM `pom-default.xml` — **0
  `<dependency>`**, 0.6.2.
- **Память.** DRAFT шаг 2 `[x]`. Mailbox #32 + гигиена (удалены realized #30
  и решённый #26-question — развилка закрыта ADR-0011; остались правила
  Антона + #31 + #32). Дневник #32 + «Итог дня» обновлён (живой).

## Контекст для следующей сессии (#33)

### Эпик (DRAFT): payload-decode retry (ADR-0011) — шаг 3
1. [x] Шаг 1 — концепт ADR-0011 (#31).
2. [x] Шаг 2 — surface + шов + юниты (#32).
3. **[ ] Шаг 3 — sync-wiring + интеграции 1–5.** Самая «живая» часть sync:
   - `executeWithRetries` (`HttpTemplate`): после существующей проверки
     `last || !isRetryableStatus(response)` (status-ось) — новая decode-ось:
     при `last == false` и `retryPolicy.isRetryDecodeFailures()` —
     `probePayloadDecode(response)`; catch'нутое codec-исключение на не-last
     attempt → DEBUG + wait + continue. Fинальное условие читается по
     псевдокоду ADR-0011 Aspect 2: `last → return; isRetryableStatus →
     retry; !probe → retry; return`. ВАЖНО: при `last == true` probe НЕ
     вызывается (exhaustion возвращает response, `processResponse` бросит
     codec-исключение сам — Aspect 3). Gate на `isEnabled()` уже есть в
     начале метода (off = pass-through).
   - `scheduleRetry` уже принимает `failure`-строку — decode-variant
     передаёт `"decode (" + e.getClass().getSimpleName() + ")"`
     (ADR-0011 Aspect 3: `HTTP 503` vs `decode (UncheckedIOException)`).
   - **строка `.retryDecodeFailures(retry.retryDecodeFailures())` в
     `HttpTemplateBuilder.resolveRetryPolicy()`** (после
     `.retryNonIdempotent(...)`) — без неё `@Retry`-атрибут dead code.
   - Интеграции ADR-0011 Aspect 7 сценарии 1–5 (WireMock, backoff 10–50 ms,
     стиль `RetryTest`): (1) 200-мусор→200-норма, flag on, 2 attempts →
     success, 2 запроса; (2) exhaustion: 200-мусор forever, 2 attempts →
     финальное исключение = codec-своё unchecked (типизированное, то же,
     что без retry), 2 запроса; (3) default off: retry включён для
     status, `retryDecodeFailures` off → 1 запрос (zero-change); (4)
     `@OnError(statuses={429})` + malformed error-body,
     `retryOnErrorStatuses` off → 1 запрос (terminal-контракт, probe не
     ходит на declared status); (5) «classified exactly once»: auth-capture
     consumer ровно ОДИН раз через весь decode-retry до успеха (счётчик;
     consumer ставится через `setAuthDetailsConsumer` — проверить шов,
     public ли он — `authDetailsConsumer` private, setter ищется в
     HttpTemplate, в `TestModuleInfo` есть прецедент).
4. [ ] Шаг 4 — async-tвин (`attemptAsync`: decode-ось через
   `delayedExecutor`) + интеграция 6 (async 200-мусор→200-норма, `join()`
   вернул значение, 2 запроса).
5. [ ] Шаг 5 — Javadoc-контракт (`RetryPolicy`/`@Retry`/`ModuleInfo`,
   sync/async uniformity) + README «Retry with backoff» (decode-параграф)
   + финальный gate + дистилляция ROADMAP (ADR-0008 Known gaps
   payload-decode-строка зачёркнута, ADR-0009 к4 аннотирован
   «reopened by ADR-0011»).

### Грабли этой и прошлых сессий (актуальный набор)
- **Side-effect-free probe — проверять формой фикстуры** (#32): template без
  logger (null → NPE, грабли #24) и без auth-consumer; NPE строже любого
  ассерта «не аллоцируется».
- **`assertSame` на codec-исключении** (#32) — «no wrapping, no new type»
  (Aspect 3) проверяемо на уровне юнита.
- **Поверхность wire'ится в шаге цикла** (#32, прецедент retry-эпика):
  строка `.retryDecodeFailures(...)` в `resolveRetryPolicy()` — шаг 3, не
  шаг 2; между шагами атрибут dead code — OK (0.6.2 не релизная).
- **«Classified exactly once» — свойство, которое надо удержать** (#31):
  обход = probe, не перенос `processResponse` в цикл. *Classification*
  (side effects) vs *decodability check* (pure).
- **Scope вылезает из формы, не из special-case'ов** (#31):
  `@OnError`/unexpected → probe сам `true` (классифицируются раньше).
- **Новая ось — новый флажок, не piggyback** (#31).
- **ADR-концепт vs реальность (серия из 9+):** сверять по коду, не по
  памяти; расхождения чинить сразу (в ADR, не в code-comment).
- **Чужие API — только по jar/javap/исходникам/прогону; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **`RFC_1123_DATE_TIME` — не «все RFC-даты»:** парсит только IMF-fixdate.
- **Wall-clock-пины — с запасом** (у wall-clock-интеграций; unit-диапазоны
  с секундной гранулярностью wire-формы — [58_000, 60_000]).
- **java.time 2-значный год = текущий век**, pivot 80–99 — после парса.
- **NPE ≠ NFE:** при переписывании парсеров смотреть набор catch'ей.
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС** (capture-логгер —
  `implements`; parameterless вызов — `params == null`); логгер в
  `HttpTemplate` null, пока не `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function.identity())`
  — пригодится в шаге 4 (async decode-ось в `attemptAsync`).
- **UP-DATE ≠ мой прогон:** `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`); `grep -c "<dependency"` exit 1 при 0 — это зелёный.
- **Подсчёт тестов:** awk по XML-заголовкам (`tests=`/`failures=`/`errors=`
  из строк `<testsuite` в `build/test-results/test/*.xml`).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps`, `git log`.
- **Прецеденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc; dedicated-фикстура на новое поведение —
  RetryController; log-capture — public `setLogger`; шов — package-private
  метод, не WireMock). Контракт-доки — на value-классе/аннотации/SPI-методе.
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — кэш сейчас тёплый (22 s).
- **DRAFT-пауза — легитимное состояние** (#26); тут эпик активен.
- **Порядок ROADMAP-секций:** новые «COMPLETE ✅ (unreleased)» —
  хронологически, перед Post-0.5.0 shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007…0011 (0011 — payload-decode);
  **0012 — свободен**.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик сессий
   подкручивает harness (сейчас 32 → после следующей 33).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (следующий свободный ADR — 0012).

### Состояние git
- HEAD: коммит сессии #31 (`ad4ab13`, ADR-0011 концепт) поверх `21f3484`
  (ответ Антона) и #30 (ROADMAP-аудит); к нему добавляется коммит #32
  (surface + шов + юниты, 250/250).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **250/250** (35 классов).
