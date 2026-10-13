# Handover Note: Сессия 28 (`Retry-After` obsolete forms — ADR-0010, микро-gap закрыт; 242/242, DRAFT — пауза)

## Что сделано в этой сессии
- **Выбор шага.** Mailbox тиш после #26 (~08:20) — вопрос направления
  (payload-decode vs 0.7.0-цикл) ждёт ответа Антона. По fallback'у из
  last_session #27 взял «маленький шаг без решения мейнтейнера» №1:
  asctime-микро-gap — осознанное закрытие (не reopening спорного
  payload-decode).
- **Реальность-чек (9-й «ADR vs реальность»).** jshell-проб
  `RFC_1123_DATE_TIME` по трём RFC-примерам дат: парсится **только
  IMF-fixdate** — gap из ADR-0008/0009 («asctime не парсится») на деле
  накрывал ОБЕ устаревшие RFC 9110-формы: RFC 850
  (`Sunday, 06-Nov-94 08:49:37 GMT`) и asctime(3)
  (`Sun Nov  6 08:49:37 1994`). Расхождение — в scope-описании gap'а, не в
  поведении фикса (первый раз в серии).
- **RFC 9110 по тексту** (webfetch datatracker): §5.6.7 —
  `HTTP-date = IMF-fixdate / obs-date`, `obs-date = rfc850-date /
  asctime-date`; **«A recipient that parses a timestamp value in an HTTP
  field MUST accept all three HTTP-date formats»**; **«values in the
  asctime format are assumed to be in UTC»** — zone-допущение предписано
  RFC. Оба главных аргумента ADR-0009 за «не чиним» сняты. §10.2.3:
  `Retry-After = HTTP-date / delay-seconds`.
- **Код (`HttpTemplate`).** Три `private static final`:
  `RETRY_AFTER_HTTP_DATE` (как был), `RETRY_AFTER_RFC850_DATE`
  (`ofPattern("dd-MMM-yy HH:mm:ss zzz", ENGLISH)`) +
  `RETRY_AFTER_RFC850_WEEKDAYS` (Set, полный weekday-имя валидируется, а
  не парсится — как распарсенное `EEEE`-поле java.time роняет parse
  conflict на 2-значном годе), `RETRY_AFTER_ASCTIME_DATE`
  (`ofPattern("EEE MMM d HH:mm:ss yyyy", ENGLISH).withZone(UTC)`).
  `parseRetryAfter`: integer → 3 try-ветки (IMF → RFC850 → asctime) →
  общий `delayFromParsedDate` (`max(0, date − now)`). `pivotRfc850Year`:
  java.time мапит 2-значный год в ТЕКУЩИЙ век (`94` → 2094) — pivot
  80–99 → предыдущий век применён ПОСЛЕ парса. asctime: collapse
  `\s{2,}` → ` ` (двойной пробел RFC-примера, 1-значный день) — только
  для asctime-попытки. Grammars взаимно непересекаемы (запятая,
  zone-токен, порядок полей) — порядок try = порядок RFC.
- **Тесты (1 → 4, TDD red → green).** `HttpTemplateRetryAfterTest`:
  старый пин «asctime → null (micro-gap)» заменён на 4 пина (RFC 850:
  прошлый RFC-пример 1994 → 0, будущий +60 s → [58_000, 60_000]; asctime:
  прошлый RFC-пример → 0, будущий → [58_000, 60_000]). Диапазон будущих
  дат расширен [59_000, 60_000] → [58_000, 60_000] — wire-форма без
  дробных секунд теряет до секунды + время теста; у существующего
  IMF-fixdate-теста тот же **латентный флейк** — поправлен. RED: 2
  падения (rfc850-past null из-за 2094-mapping; rfc850-future 58879 —
  стена диапазона). Форматы — `ZoneOffset.UTC`, `Locale.ENGLISH` явно.
- **ADR-0010** (`docs/0.7.0/`) — новый: scope-правка, MUST из RFC,
  pivot/whitespace детали, rejected alternative «close as permanent gap»
  (записан с причиной отклонения). ADR-0008 Known gaps — asctime-строка
  ~~зачёркнута~~ (closed + pointer + scope-правка). ADR-0009 —
  bullet/Consequences/Known gaps поправлены с указателями (решение стоит
  в остальном). ROADMAP — Retry-раздел: «HTTP-date form is an accepted
  known gap» (устарела после 0.x polish) → указатель ADR-0009/0010;
  0.x-polish-раздел: asctime — corrected+closed. DRAFT — asctime-кандидат
  зачёркнут с пометкой.
- **Гейты:** RED (2) → `./gradlew test --rerun` — **242/242** (239→242;
  34 класса, 0 failures/0 errors по XML, 19 s тёплый кэш); POM
  `pom-default.xml` — **0 `<dependency>`**, 0.6.2.
- **Память.** Mailbox #28 (gap закрыт + вопрос #26 жив) + гигиена
  (удалён realized #25; остались правила Антона + #26 + #28). Дневник #28
  + «Итог дня» обновлён (день не закрыт — секция живая).

## Контекст для следующей сессии (#29)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом.**
4. **Вопрос направления (#26) без ответа:** (a) payload-decode retry →
   концепт ADR-0011 (ADR-0010 занят!), (b) 0.7.0-цикл/релиз, (c) иное.

### Что логично делать дальше
1. **Если Антон ответил в mailbox** — по ответу: (a) «payload-decode» →
   концепт **ADR-0011** (`docs/0.7.0/`): failure-kind в retry-цикле vs
   «classified exactly once» ADR-0008, типизация финального исключения,
   транспорт-покрытие transient-сбоев, три аргумента ADR-0009 за
   «оставить» переубедить или принять; DRAFT становится эпиком с
   чекбоксами. (b) «0.7.0-цикл» → релизный эпик: release notes, версия,
   теги — по его решению. (c) иное — по тексту.
2. **Если ответа нет** — payload-decode по-прежнему не открывать
   самовольно (reaffirmed в ADR-0009). Оставшиеся small steps без решения
   мейнтейнера: (1) остаток Known gaps ADR-0008 — `InterruptedException`
   never retried (уже в Javadoc — можно доки/README довести до
   единообразия), backoff-шов package-private (дока), Retry-After только
   для status-retries (дока) — скорее doc-аудит, чем код; (2) рефакторинг/
   warnings; (3) гигиена-аудит ROADMAP. Любой — один маленький.
3. **Постоянная нить DRAFT** (гигиена) — как и раньше: mailbox не растёт,
   «Пометки из mailbox» в DRAFT сверять с реальностью.

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт vs реальность (серия из 9 раз):** #28 — девятый, и первый
  в SCOPE-описании gap'а («asctime» на деле = обе устаревшие формы).
  Проверять по jar/тегам/исходникам/коду/прогону на шаге, править ADR
  обратно.
- **`RFC_1123_DATE_TIME` — не «все RFC-даты»:** парсит только IMF-fixdate.
  Имя-ловушка.
- **java.time 2-значный год = текущий век** (94 → 2094), без pivot'а;
  распарсенный `EEEE`-weekday — валидируемое поле (DayOfWeek-конфликт).
  Pivot 80–99 — после парса.
- **Wall-clock-пины — с запасом:** дата = now + 3 s, assert elapsed ≥ 2 s
  (интеграция); unit-диапазоны будущих дат — [58_000, 60_000] (wire-форма
  без дробных секунд теряет до секунды).
- **RFC_1123/кастомные formatters в тестах — дату строим в ZoneOffset.UTC**,
  `Locale.ENGLISH` явно (контейнер +0400).
- **NPE ≠ NFE:** при переписывании парсеров смотреть набор catch'ей.
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС**: capture-логгер —
  `implements` (абстрактные: `getName()`, `isLoggable`, оба `log(Level,
  ResourceBundle, ...)` перегрузки); parameterless вызов приходит с
  `params == null`. NPE-грабли: логгер в `HttpTemplate` null, пока не
  вызвали `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **Чужие API — только по jar/javap/исходникам/прогону; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function .identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`).
- **Подсчёт тестов:** grep-цикл по XML-заголовкам (`tests=`/`failures=`/
  `errors=` из строк `<testsuite` в `build/test-results/test/*.xml`);
  python3/bc НЕ пережили ребут.
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc; dedicated-фикстура на новую аннотацию/
  поведение — RetryController/HttpVersionController; log-capture — public
  `setLogger`; шов парсера — package-private метод, не WireMock).
- **Аллокации на hot path** — static constants, не per-call списки
  (#28: все новые formatters/sets — static final).
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся (ребут),
  первый прогон дня качает зависимости заново (~50 с).
- **DRAFT-пауза — легитимное состояние** (#26): эпик не изобретать, пока
  мейнтейнер не выбрал направление или триаж сам не покажет очевидного
  кандидата; в DRAFT это зафиксировано в правиле работы.
- **Порядок ROADMAP-секций** (прецедент четыре раза): новые
  «COMPLETE ✅ (unreleased)»-разделы — хронологически, перед Post-0.5.0
  shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007 (Gson/Moshi), 0008 (retry),
  0009 (0.x polish), 0010 (Retry-After obsolete forms); **0011 — свободен**
  (кандидат: payload-decode).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 28 → после следующей 29).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0011 — следующий свободный номер в `0.7.0/`).

### Состояние git
- HEAD: коммит сессии #26 (закрытие 0.x polish) + коммит этой сессии #28
  (ADR-0010 + код + тесты + доки + память).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **242/242**.
