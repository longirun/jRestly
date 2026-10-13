# Handover Note: Сессия 37 (0.7.0-цикл — CHANGELOG.md создан, эпик 2/4; 256/256, кода нет, следующий шаг — bump 0.7.0 + gate + версия в commit message)

## Что сделано в этой сессии
- **Входящее:** mailbox тиш после #36 (~18:4x) — ответа Антона на #36 нет
  (тег-вопрос закрыт, контур зафиксирован в ADR-0012 — шаг брался по
  DRAFT: шаг 2 из 4).
- **`CHANGELOG.md` (корень) — шаг 2 эпика.** Первый user-facing changelog.
  Заголовок-файл: «User-facing changes per version. History prior to
  0.7.0 (cycles 0.5.0 → 0.6.1) lives in ROADMAP.md» (ADR-0012 п.3:
  бэкилла нет). Entry `0.7.0 — 2026-10-13 (unpublished)` + пометка «no
  git tag: версия в build.gradle и commit message; JitPack-координаты —
  v0.6.1 до тега». Контент — по шести COMPLETE-секциям ROADMAP,
  user-facing, без дизайн-рассуждений:
  - **Added:** (a) `CompletableFuture<T>`-возвраты (sendAsync, future
    completes как sync twin, исключения — как cause: join() →
    CompletionException); (b) `JacksonCodec.LocalDateTimeFormat` (SPACE —
    default, byte-identical; ISO_8601 — opt-in; общий fraction-хвост;
    lenient-парсер T+space в обеих модах); (c) Gson/Moshi-адаптеры
    (цепочка jackson→gson→moshi, explicit jsonCodec выше; API/POM
    неизменны — compileOnly; reality-ноуты: Gson 2.8.3+, с 2.14 —
    замороженный pre-2.14 java.time формат; Moshi 1.x без java.time-
    аддона → explicit Moshi, concrete-коллекции — JsonDataException);
    (d) Retry with backoff (off by default; транспорт +
    {408,429,500,502,503,504}; exponential (capped) + full jitter; два
    уровня: client `retry(...)`/`ModuleInfo.getRetryPolicy()` +
    self-contained `@Retry`, bare `@Retry` = off-switch; GET/PUT/DELETE vs
    POST/PATCH `retryNonIdempotent`; `@OnError`-терминальность +
    `retryOnErrorStatuses`; Retry-After — deterministic lower bound;
    InterruptedException fail fast; sync/async uniformity,
    delayedExecutor); (e) payload-decode retry (третий failure-kind,
    отдельный opt-in `retryDecodeFailures` default false, «enable retry»
    = zero change success-path, финал = codec-своё unchecked без нового
    класса, sync/async uniformity).
  - **Fixed:** ровно три user-visible изменения 0.x polish: PUT в INFO
    request-лог (GET/HEAD осознанно вне); `void` + непустое тело →
    Void-семантика (codec-exception под Gson/Moshi; lenient Jackson
    маскировал); HTTP-date Retry-After — все три формы RFC 9110
    (IMF-fixdate + RFC 850 + asctime(3)), past → floor 0.
  - Финальная строка: zero-dep сохранён (POM 0 `<dependencies>`, адаптеры
    compileOnly).
- **Факты перед записью сверены по коду** (не по памяти):
  `DEFAULT_RETRYABLE_STATUSES`, `LocalDateTimeFormat`/`defaults(format)`,
  `retryDecodeFailures` (RetryPolicy + @Retry), `times() default 1`,
  минверсия Gson 2.8.3 / пины — по Javadoc адаптеров, `retry(RetryPolicy)`
  на обоих builders, build.gradle 0.6.2 (не трогал — bump шаг 3).
- **DRAFT:** шаг 2 `[x]`.
- **Mailbox:** #37 (changelog готов — приглашение посмотреть перед
  bump'ом) + гигиена: удалён realized 22:17-обмен 10-12 (все пункты
  исполнены: ISO 'T' — #7, ADR-0006 не нужен — в DRAFT, чистка — #9,
  объём сессий — в DRAFT, «вариант А» — #7; «теги»/«версии» живы в
  правилах ниже). Остались: недопонимание-релизы (теги — живое),
  коммиты (живое правило), версионирование (живое до шага 3), #36, #37.
- **Гейты.** Кода нет — `./gradlew test --rerun` **256/256** (36 классов,
  0 failures/0 errors по XML, 19 s тёплый кэш); build.gradle 0.6.2,
  POM `pom-default.xml` — 0.6.2 (bump — шаг 3).

## Контекст для следующей сессии (#38)

### Эпик: 0.7.0-цикл (ADR-0012) — остался шаг 3 из 4 (потом шаг 4)
1. **Шаг 3 (следующий): bump + gate + версия в commit message.**
   `build.gradle` строка 7: `version = '0.6.2'` → `version = '0.7.0'`.
   Gate: (a) `./gradlew test --rerun` зелёный (ожидается 256/256);
   (b) POM `build/publications/mavenJava/pom-default.xml` — версия
   **0.7.0** и 0 `<dependency>`; если после test-прогона POM по-прежнему
   0.6.2 — перегенерировать (`./gradlew
   generatePomFileForMavenJavaPublication` или `build`) и проверить;
   (c) **версия в commit message** — правило Антона, впервые в деле
   (ADR-0012 п.2: «the version is named in that step's commit message»;
   кандидат-формулировка subject: `release 0.7.0: bump version in
   build.gradle + gate` — subject краткий, версия названа явно).
   После: DRAFT шаг 3 `[x]`, mailbox #38, память, коммит. «Кода» в шаге —
   ровно одна строка build.gradle.
2. **Шаг 4 (последний): дистилляция.** ROADMAP — новая секция «Cycle
   0.7.0 — COMPLETE ✅ (2026-10-13)» — упаковочная: scope (шесть наборов,
   ADR-0007…0011), версионирование (0.7.0, без тега, механика по правилу
   Антона), release notes (CHANGELOG.md) + линки на шесть COMPLETE-секций
   (не пересказ). Куда вставить: ПОСЛЕ «Payload-decode retry — COMPLETE
   ✅» (стр. 381) и ПЕРЕД «Post-0.5.0 shortlist» (стр. 444) —
   хронология цикла (прецедент в шестой раз). + зачистка DRAFT: эпик
   убрать (дистиллят в ROADMAP), «после цикла» поднять в активную зону
   (warnings-аудит — кандидат №1; ADR-0013 свободен — папка следующего
   цикла `docs/` при открытии).

### Грабли (актуальный набор)
- **Changelog — дериват, не зеркало** (#37): design-рассуждения не
  переносятся (они в ADR); reality-последствия — короткими строками
  «bring an explicit …».
- **Правило мейнтейнера = часть ADR** (#36): версионирование — цитата,
  не домысел (тега нет «как по OSS»).
- **Без тега координаты не существуют** (#36): README держит v0.6.1; в
  цикле README-правок НЕТ.
- **Цикл — упаковка, не фича** (#36): концепт ссылается, не пересказывает.
- **Коммиты — КРАТКИЙ subject, без дублирования ченджлога** (Антон,
  2026-10-13); на шаге 3 + версия в сообщении.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`); `grep -c "<dependency"` exit 1 при 0 — зелёный. После
  bump — версия 0.7.0 в том же файле (проверить по факту, не по памяти —
  файл может не регенерироваться от `test`).
- **Подсчёт тестов:** awk по XML-заголовкам (python3/bc не переживают
  ребут).
- **Порядок ROADMAP-секций:** новые COMPLETE — хронологически, перед
  Post-0.5.0 shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007…0012; **0013 — свободен**.
- **Сторонние API — по jar/javap/тегам, не из головы; wall-clock-пины —
  с запасом; NPE ≠ NFE.**

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый
   кэш пережил сессию #36).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 37 → после следующей 38).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`.

### Состояние git
- HEAD: коммит сессии #37 (CHANGELOG.md + память) поверх #36. Всё на
  `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном (по
  факту — больше не вешаем).
- Версия в build.gradle: **0.6.2** (bump до 0.7.0 — шаг 3 эпика).
- Тесты: **256/256** (36 классов).
