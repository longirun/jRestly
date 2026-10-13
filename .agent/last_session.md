# Handover Note: Сессия 31 (концепт ADR-0011 — payload-decode retry; код не тронут, 242/242, DRAFT — активный эпик)

## Что сделано в этой сессии
- **Вводная (по факту).** Git-статус: HEAD = коммит #30 + чужой `21f3484`
  «ответы на вопросы» (коммит Антона 11:20 — ответ в mailbox). TODO.md нет
  (только ROADMAP.md — бэклог живёт там). Baseline `./gradlew test --rerun`
  **242/242** (34 класса, 0 failures/errors по XML; `bc` не пережил ребут —
  счёт по awk), POM `pom-default.xml` 0 `<dependency>`, 0.6.2.
- **Ответ Антона (11:20, `21f3484`).** По развилке #26 (payload-decode vs
  0.7.0-цикл): «предпочтения нет, исходить из стратегического планирования —
  что выгоднее для долгосрочного развития». Плюс уточнение про релизы:
  версионирование — по нашему усмотрению, **теги не вешать** (теги = релизные
  сборки; работаем в `agent-loop`, наружу не мержим).
- **Концепт ADR-0011 (`docs/0.7.0/`).** Стратегический разбор (в Context
  ADR): payload-decode = последняя содержательная фича known-gaps-бэклога,
  0.7.0-цикл = упаковка/версионирование, не фича ⇒ **фича первой**, 0.7.0
  упаковывает полный retry-контракт (transport + status + decode) без
  остаточных содержательных gap'ов. Reopen gap'а (supersedes «LEAVE» из
  ADR-0009 кандидат 4 — правило ADR-0003 «reopening = новый ADR»). Решения
  (Aspects 1–7):
  - **Третий failure-kind** (transport/status/**decode**). Decode-ретрай =
    last-resort: срабатывает только на response, который status-логика уже
    принял (именно случай gap'а: 200, тело не биндится).
  - **«Classified exactly once» ADR-0008 сохранён** — loop НЕ зовёт
    `processResponse` на каждый attempt; он зовёт **side-effect-free
    decode-probe** (`probePayloadDecode`, package-private шов): воспроизводит
    ТОЛЬКО decode-ветку `processResponse` (без auth/log/exception).
    `processResponse` (side-effectful) — ровно один раз на финале. Probe
    `true` на `@OnError`/unexpected/null/String/Void (они классифицируются ДО
    payload-decode) → scope (только success-path) вылезает из формы probe'а,
    без special-case. Стоимость: финальный payload декодируется дважды (probe +
    `processResponse`) — bounded, final-only, opt-in.
  - **Финальное исключение — без нового класса**: при исчерпании на decode-
    сбое `processResponse` на финале бросает codec-своё unchecked — то же,
    что и без retry (0.x-дисциплина ADR-0009-аргумент 3 удержан свойством
    дизайна). DEBUG-строка различает kind: `HTTP 503` vs `decode (<codec ex>)`.
  - **Отдельный opt-in `retryDecodeFailures`** (`RetryPolicy` builder +
    `@Retry`, default `false`): decode-probe ходит по EVERY success-response
    (в отличие от transport/status — по failed) ⇒ не подмешивать в общее
    enable; «enable retry» остаётся zero change на success-path.
  - **ADR-0009-аргумент 1 сужен**: transport = «байты не пришли» (incomplete
    → IOException, уже ретраится); decode = «байты пришли, но не биндятся»
    (complete body, schema-drift/одноразовый malformed). Complementary.
  - Aspect 6 (seam: sync `executeWithRetries` + async `attemptAsync`
    `delayedExecutor`), Aspect 7 (тест-стратегия: probe-юниты stub-кодеком + 6
    интеграций).
- **DRAFT.** Осознанная пауза → **активный эпик «payload-decode retry
  (ADR-0011)»** с 5 чекбоксами (концепт `[x]`). «Пометки из mailbox»
  обновлены (ответ Антона, направление решено).
- **Память.** Mailbox #31 + гигиена (удалён realized #29; остались правила
  Антона + 11:20-ответ + #31 + #30 + #26-question-thread). Дневник #31 +
  «Итог дня» обновлён (день не закрыт — секция живая). last_session
  переписан.
- **Кода нет** — только ADR-0011 + DRAFT + память.

## Контекст для следующей сессии (#32)

### Эпик (DRAFT): payload-decode retry (ADR-0011) — шаг 2
1. [x] Шаг 1 — концепт ADR-0011 (сессия #31).
2. **[ ] Шаг 2 — `RetryPolicy.retryDecodeFailures` + `@Retry`-атрибут + шов
   `probePayloadDecode` (package-private) + юниты.** Самая изолированная
   часть: как backoff-шов в #18 (чистая функция + юниты без WireMock).
   - `RetryPolicy`: поле `retryDecodeFailures` (default `false`), getter,
     builder (валидация не нужна — boolean), copy/unmodifiable-семантика
     (boolean — тривиально). `defaults()` = false, `off()` = false.
   - `@Retry`: атрибут `retryDecodeFailures` (default `false`), Javadoc-
     абзац (success-path, double-decode cost, out-of-scope `@OnError`).
   - `probePayloadDecode(WireResponse)` — package-private метод на
     `HttpTemplate` (шов, как `retryAfterDelayMillis`). Логика (Aspect 2
     ADR-0011): `onErrorParser.canParse(status)` → true; `!isExpectedStatus`
     → true; `payload==null` → true; `String`/`Void`/`void` → true; иначе
     `jsonCodec.deserialize(payload, returnType)` (бросает codec-exception).
     ВАЖНО: ре-использовать те же предикаты/поля, что `processResponse`
     (`isExpectedStatus`, `onErrorParser`, `returnType`) — чтобы не разъехалось
     (принцип ADR-0008 Aspect 3).
   - Юниты (`HttpTemplateDecodeProbeTest`, пакет `ru.jrestly.http`): stub-
     кодек (через шов), expected-decodable → true; expected-unbindable →
     codec-exception; `@OnError` → true; unexpected → true; null/String/Void/
     void → true.
3. [ ] Шаг 3 — sync-wiring (`executeWithRetries`: decode-ось в final-условии +
   decode-DEBUG) + интеграции ADR-0011 Aspect 7 сценарии 1–5.
4. [ ] Шаг 4 — async-tвин (`attemptAsync`) + интеграция 6.
5. [ ] Шаг 5 — Javadoc-контракт + README + финальный gate + дистилляция
   ROADMAP (ADR-0008 Known gaps payload-decode-строка зачёркнута, ADR-0009
   к4 аннотирован «reopened by ADR-0011»).

### Грабли прошлых сессий (актуальный набор)
- **«Classified exactly once» — свойство, которое надо удержать, а не
  сломать** (#31): обход = probe, не перенос `processResponse` в цикл.
  Разделение: *classification* (side effects) vs *decodability check* (pure).
- **Scope вылезает из формы, а не из special-case'ов** (#31): `@OnError`/
  unexpected не декодируются на success-path (классифицируются раньше) → probe
  возвращает `true` для них сам. Special-case «если status in @OnError — не
  пробоуем» = дублирование логики `processResponse` (риск разъезда).
- **Новая ось — новый флажок, не piggyback** (#31): piggyback на `isEnabled()`
  дешевле в конфигурации, но ломает predictability (общий enable меняет
  success-path).
- **Стратегический аргумент — в ADR, не в chat** (#31): Антон «стратегическое
  планирование» → разбор (фича vs упаковка, 0.7.0 = feature-complete retry)
  живёт в Context ADR-0011.
- **Чужие API — только по jar/javap/исходникам/прогону; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **ADR-концепт vs реальность (серия из 9+):** #31 — концепт сверен по коду
  (executeWithRetries/processResponse/JsonCodec), не по памяти.
- **`RFC_1123_DATE_TIME` — не «все RFC-даты»:** парсит только IMF-fixdate.
- **Wall-clock-пины — с запасом:** дата = now + 3 s, assert elapsed ≥ 2 s.
- **java.time 2-значный год = текущий век** (94 → 2094), без pivot'а; pivot
  80–99 — после парса.
- **NPE ≠ NFE:** при переписывании парсеров смотреть набор catch'ей.
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС**: capture-логгер —
  `implements` (абстрактные: `getName()`, `isLoggable`, оба `log(Level,
  ResourceBundle, ...)`); parameterless вызов — `params == null`. НPE-грабли:
  логгер в `HttpTemplate` null, пока не вызвали `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function.identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`); `grep -c "<dependency"` exit 1 при 0 — это зелёный.
- **Подсчёт тестов:** `bc`/python3 не пережили ребут — awk по XML-заголовкам
  (`tests=`/`failures=`/`errors=` из строк `<testsuite` в
  `build/test-results/test/*.xml`).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime, `ps`,
  `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry; proxy-
  builders без Javadoc; dedicated-фикстура на новую аннотацию/поведение —
  RetryController; log-capture — public `setLogger`; шов парсера — package-
  private метод, не WireMock). Контракт-доки — на value-классе/аннотации/
  SPI-методе, не на proxy-builders.
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся (ребут),
  первый прогон дня качает зависимости заново (~50 с).
- **DRAFT-пауза — легитимное состояние** (#26); тут эпик открыт (направление
  выбрано).
- **Порядок ROADMAP-секций** (прецедент 4 раза): новые «COMPLETE ✅
  (unreleased)»-разделы — хронологически, перед Post-0.5.0 shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007 (Gson/Moshi), 0008 (retry), 0009
  (0.x polish), 0010 (Retry-After obsolete forms), **0011 (payload-decode —
  эта сессия)**; **0012 — свободен**.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик сессий
   подкручивает harness (сейчас 31 → после следующей 32).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0011 — в `0.7.0/`; следующий свободный — 0012).

### Состояние git
- HEAD: коммит сессии #31 (ADR-0011 концепт + DRAFT эпик + память) поверх
  `21f3484` (ответ Антона) и #30 (ROADMAP-аудит).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **242/242** (34 класса).
