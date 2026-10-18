# Last Session: #294 (2026-10-19)

## Что сделано

**Сессия #294: Кандидат №6, P3 исполнен — синтаксис path-плейсхолдеров
`${id}` → `{id}` (индустриальный стандарт; TDD red-first; гейт
1,638/1,638 = 1,637 + 1 новый тест, javadoc чист под `-Werror`).**

1. *TDD:* красный = новый тест `acceptsBraceStylePathPlaceholdersEagerly`
   в `ControllerValidationTest` + обновлённые message-ассерты
   (`'{id}'`, `'{}'`, `{...}`); 4 ожидаемых красных подтверждено фильтром
   `:test --tests 'ru.jrestly.ControllerValidationTest'`. Зелёный:
   `PATH_PLACEHOLDER` = `\{([^}]*)}` (было `\$\{([^}]*)}`), сообщения
   mismatch переписаны, `HttpTemplateBuilder.replace("{" + name + "}", …)`,
   javadoc 5 глаголов/`@PathVariable`/`RequestMeta`/валидатора.
2. *Масштаб:* 22 тестовых файла, shared fixtures
   (`TestController`/`PathVariableController`), README (8), benchmarks (2).
   `InvalidPathController.singleBrace()` → `unterminatedBrace()` —
   `{id` (незакрытая скобка) остаётся невалидным примером после смены
   синтаксиса (гейт `URI.create`, Amendment #211, его и ловит).
   НЕ тронуты: `Trace.java`/`TraceContext.java` (traceparent — W3C-доки,
   не path-шаблон), исторические ADR 0.9.0/0.10.0, `ROADMAP.md`
   (паттерн #292/#293).
3. *Грабля дня:* массовый sed с захват-группой, в которую входят
   символы-цели замены, дал `${id}` → `{{id}}` — первый полный гейт
   упал на 489 тестах. Поправлено обратным замещением + скан `{{`
   по дереву (литеральные `"{{{` в codec-тестах — JSON, не трогать).
   Урок: после такого sed — промежуточный скан ДО полного гейта.
4. *Гейт:* **1,638/1,638** green across all 5 modules (root 1,558 +
   sub 80), 0 failures/errors/skipped, `:javadoc` чист под `-Werror`.
   ADR-0067: Status «P3 реализован (#294)» + Amendment #294; DRAFT:
   кандидат №6 «осталось P4-микропроверка».

## Контекст для следующей сессии (#295)

1. **Ветка** `agent-loop`; HEAD — коммит P3 (см. `git log -1`).
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД — P4-микропроверка** (маленькая сессия): статические
   query прямо в пути `@Get("/items?size=10")` — merge с динамическими
   `@RequestParam` и плейсхолдерами: работает ли merge-пломбинг
   (`base.contains("?") ? "&" : "?"` в `HttpTemplate`) без искажений —
   зелёное: задокументировать в README + пометить в ADR-0067;
   кривое: баг по TDD (красный тест — путь со статическим query +
   `@RequestParam`).
4. **Затем — хвост Map-префикса** (находка прогона P2, #293): bare
   `@RequestParam Map filter` шлёт голые `status=…` (префикс строится из
   `rawRequestParamName()`, а не эффективного имени ADR-0017-fallback;
   `HttpTemplateBuilder`, 2 call-site, строки ~746/962). Либо довести
   fallback до Map-префикса (bare ≡ named) — TDD: красный тест
   «bare `@RequestParam Map` → ожидание префикса» — либо задокументировать
   различие явно. Маленькая TDD-сессия.
5. **№7 (rss/atom)** всё ещё заблокирован на слове Антона (A/B:
   parse-only vs fetch-and-parse; ядро vs codec-субмодуль — вопрос висит
   с #284).
6. **Хвосты (не эпики):** Path/File-тела метрик (-1; engine-reported byte
   accounting), `@ExpectStatus`/`@FollowRedirects` (фича, не дыра),
   fixtures-javadoc (15 типов).
7. **Грабли/паттерны (важно!):**
   - Плейсхолдеры теперь `{id}`: маска `\{([^}]*)}` в `ControllerValidator`;
     незакрытая скобка `{id` ловится гейтом `URI.create` (#211).
     Traceparent-`${…}` в `Trace.java`/`TraceContext.java` — НЕ трогать.
   - Map-префикс смотрит `rawRequestParamName()` (грабля #293) — любое
     расширение ADR-0017-fallback проверять на Map-раскрытии.
   - Implicit `value` — только один абстрактный метод (грабля #292);
     bare-`@Ann` (пустые скобки) — легален при `default ""`.
     `value = ` со вторым атрибутом держать (паттерн #292).
   - `@MultipartFormFile.partName()` — required (нет default'), грабля #209.
   - Исторические ADR со OLD-снапшотами не переписывать.
   - Счётчики тестов — питоном по `TEST-*.xml` всех модулей
     (`glob('*//build/test-results/*/TEST-*.xml')`; `bc` нет).
   - Фильтр тестов `:test --tests 'X'` (без ведущего `:` заденет субмодули).
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен.
   - Массовый sed с захват-группой в скобках — промежуточный скан ДО гейта
     (грабля #294, `{{id}}`).
   - XML-отчёты атрибутируют упавшие тесты на предшествующие self-closing
     testcase'ы — идентификация по ре-риду отчёта (наблюдение #294).
   - Русско-английская билингва: перечитывать doc-тексты сразу до коммита.
   - Часовой пояс контейнера +04 (логи log4j — UTC); счётчик сессий: **294**.
   - Из извлечённого коллаборатора — свой сейт до 0 missed (урок #271) и
     private no-arg-ctor (грабля #283).
