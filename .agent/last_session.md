# Handover Note: Сессия 26 (`0.x polish` — финальный gate, эпик закрыт; 239/239, DRAFT — пауза)

## Что сделано в этой сессии
- **Гейты (вход/выход):** `./gradlew test --rerun` — **239/239** (0
  failures/0 errors по XML, 19 с, тёплый кэш); POM `pom-default.xml` —
  **0 `<dependency>`**, 0.6.2.
- **Дистилляция в ROADMAP.md.** Новый раздел «0.x polish — carry-over quirks
  and accepted known gaps — COMPLETE ✅ (2026-10-13, unreleased)» в стиле
  Gson/Moshi- и Retry-разделов: source of truth — ADR-0009, сессии #23–#26;
  четыре scope-решения (PUT-лог → `static final Set` на hot path, GET/HEAD
  осознанно вне; void.class — единственное behavior-изменение эпика +
  кодек-зависимый пин: Jackson lenient, Gson/Moshi IAE; HTTP-date →
  `retryAfterDelayMillis` в мс + asctime-микро-gap + 8-й «ADR vs реальность»
  фикс — «null-valued → null» на деле был NPE; payload-decode — LEAVE с
  тремя аргументами); ровно три user-visible изменения; тестовый периметр
  (10 новых тест-классов/методов в двух новых классах + 6 в существующих).
  Carry-over-строка 0.5.0: оба квирка ~~зачёркнуты~~ + указатель на
  ADR-0009. Раздел вставлен хронологически (после Retry, перед
  Post-0.5.0 shortlist).
- **Зачистка DRAFT.md.** Эпик убран целиком (дистиллят — в ROADMAP). Новое
  состояние — **осознанная пауза «Known gaps triage»**: (1) payload-decode
  retry (reaffirmed, reopening = новый ADR по правилу ADR-0003; спорные
  места: failure-kind в retry-цикле vs «classified exactly once»,
  типизация финала); (2) asctime (микро-gap); (3) остаток Known gaps
  ADR-0008 (принятые non-blocking); (4) контур 0.7.0-цикла (пять
  unreleased-накоплений в 0.6.2 без тега: CompletableFuture, ISO option A,
  Gson/Moshi, Retry, 0.x polish). Эпик не открыт самовольно — направление
  (a) payload-decode / (b) 0.7.0-цикл / (c) третье — вопрос уровня
  мейнтейнера, в mailbox (#26).
- **Mailbox #26** (эпик закрыт + вопрос направления). Гигиена: удалён
  realized #24 (дистиллирован), остались правила Антона (22:17 + уточнение +
  «Гоу вариант А») + последние два моих (#25, #26).
- **Память.** Дневник #26 + «Итог дня» переписан по состоянию на #26 (день
  не закрыт — секция живая); DRAFT шаг 4 `[x]`-фактически (эпик зачистился).

## Контекст для следующей сессии (#27)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом.** (Mailbox тиш после моего #26 ~08:20 —
   вопрос направления ждёт ответа.)

### Что логично делать дальше
1. **Если Антон ответил в mailbox** — по ответу: (a) «payload-decode» →
   концепт ADR-0010 (`docs/0.7.0/`): scope-решение по ретраю сбоев декода
   финального ответа (failure-kind в retry-цикле vs «classified exactly once»
   ADR-0008, типизация финального исключения, транспорт-покрытие transient
   случаев, 0.x-дисциплина — три аргумента ADR-0009 за «оставить» надо
   переубедить или принять); DRAFT становится эпиком с чекбоксами. (b)
   «0.7.0-цикл» → релизный эпик: подготовка release notes, вопрос версии,
   теги — по его решению. (c) иное — по тексту.
2. **Если ответа нет** — не открывать payload-decode самовольно (flip-flop
   против свежего ADR-0009). Кандидаты на «маленький шаг без решения
   мейнтейнера»: (1) asctime-микро-gap — концепт в ADR-0010 или осознанное
   закрытие (RFC 9110: obsolete, без zone — фикс = кастомный formatter +
   zone-допущение; можно и зафиксировать «не чиним навсегда» одной строкой в
   Known gaps + Javadoc парсера); (2) гигиена-аудит ROADMAP (Post-0.5.0
   shortlist-секция теперь полностью зачёркнута — можно пометить «drained»);
   (3) рефакторинг/warnings. Любой такой шаг — один маленький, по норме.
3. **Постоянная нить DRAFT** (гигиена) — как и раньше: mailbox не растёт,
   «Пометки из mailbox» в DRAFT сверять с реальностью.

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт vs реальность** (серия из 8 раз): проверять по
  jar/тегам/исходникам/коду на шаге, править ADR обратно (#25 — восьмой:
  «null-valued → null (unchanged)» на деле был NPE).
- **NPE ≠ NFE:** в старом парсере `catch (NumberFormatException)` не ловил
  NPE с `value.trim()` — при переписывании смотреть набор catch'ей.
- **Wall-clock-пины — с запасом:** дата = now + 3 s, assert elapsed ≥ 2 s;
  один такой тест на эпик.
- **RFC_1123_DATE_TIME в тестах — дату строим в ZoneOffset.UTC** (контейнер
  +0400 → format даст «+0400»-текст; parse принимает оба, но «GMT» честнее).
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС**: capture-логгер —
  `implements` (абстрактные: `getName()`, `isLoggable`, оба `log(Level,
  ResourceBundle, ...)` перегрузки); parameterless вызов приходит с
  `params == null`. NPE-грабли: логгер в `HttpTemplate` null, пока не
  вызвали `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **Чужие API — только по jar/javap/исходникам; min-версии — по тегам.**
  Multi-release jar'ы: корневой класс — заглушка.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function .identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** python3/bc НЕ пережили ребут — grep-цикл по
  XML-заголовкам (`tests=`/`failures=`/`errors=` из строк `<testsuite` в
  `build/test-results/test/*.xml`).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc; dedicated-фикстура на новую аннотацию/
  поведение — RetryController/HttpVersionController; log-capture — public
  `setLogger`, не classloader/setErr-tricks; шов парсера — package-private
  метод, не WireMock — паттерн nextDelayMillis).
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся (ребут),
  первый прогон дня качает зависимости заново (~50 с).
- **DRAFT-пауза — легитимное состояние** (#26): эпик не изобретать, пока
  мейнтейнер не выбрал направление или триаж сам не покажет очевидного
  кандидата; в DRAFT это зафиксировано в правиле работы.
- **Порядок ROADMAP-секций** (#16, #22, #26 — прецедент трижды): новые
  «COMPLETE ✅ (unreleased)»-разделы — хронологически, перед Post-0.5.0
  shortlist.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 26 → после следующей 27).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry, ADR-0009 — 0.x polish;
   ADR-0010 — свободный номер для следующего концепта).

### Состояние git
- HEAD: коммит сессии #25 (HTTP-date-парсер) + коммит этой сессии #26
  (ROADMAP-дистилляция + зачистка DRAFT + память + mailbox).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **239/239**.
