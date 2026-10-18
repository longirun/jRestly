# Last Session: #292 (2026-10-18)

## Что сделано

**Сессия #292: Кандидат №6, P1 исполнен — `path()` → `value()` в пяти
аннотациях глаголов (приём и фиксация WIP прерванных #288–#291, паттерн
#155/#186/#254). Гейт 1,637/1,637, javadoc чист под `-Werror`.**

1. *Триггер:* зелёный свет Антона в mailbox (2026-10-18): «Самобытность не
   должна конфликтовать с другими принципами. Лаконичность в данном случае
   перевешивает» — одобрение P1/P2/P4 и решение по P3 (`{id}` берёт).
2. *Приём WIP:* в дереве лежал полный P1 без коммита (99 файлов; счётчик
   287 → 292, HEAD на #287): rename в `@Get/@Post/@Put/@Patch/@Delete`,
   `MethodMetadata`, `ControllerValidator.resolvePath`, ~95 тест/fixture/
   бенчмарк-файлов, README, benchmarks + новый тест
   `MethodMetadataTest.verbPathDeclaredViaImplicitValueAttribute`
   (`VerbValueApi`, `@Get("/api/conventional")`). Диффы сверены байт-в-байт:
   stray `path = ` в глаголах нет; `requestMeta().path()` (accessor
   `RequestMeta` — другой API) не тронут; historical-ADRs `docs/0.10.0/` с
   OLD-снапшотами не переписаны (осознанно).
3. *Полировка + грабля:* тройной blank после `VerbValueApi` — к одному.
   Попытка implicit `@Post("/multipart-test", requestType = …)` не
   скомпилировалась: **Java разрешает безымянный аргумент аннотации только
   при единственном абстрактном методе** — `@Post` несёт ещё `requestType`
   (аналогично `@Put`/`@Delete` с `params`). Минимальная форма —
   `value = "/multipart-test"`. Записано в ADR-0067 Amendment #292 —
   пригодится при P2-прогоне README/примеров.
4. *Гейт:* `./gradlew test javadoc` — **1,637/1,637** across all 5 modules
   (+1), 0 failures/errors, javadoc чист. JaCoCo не пересчитывался (rename
   без поведенческого эффекта; метрики #283 в силе: 97.91%/97.62%/89.63%/
   87.74%, CLASS 149/149, METHOD 1444/1444).
5. *Память:* ADR-0067 Status + Amendment #292; DRAFT-блок; mailbox (отчёт
   Антону; решённый #285 удалён по гигиене).

## Контекст для следующей сессии (#293)

1. **Ветка** `agent-loop`; HEAD — свежий коммит P1
   (`refactor(annotation): verb path() → value() …`), см. `git log -1`.
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД — P2 (безымянные биндинги в README/примерах; код НЕ
   меняется):** прогон README.md, примеров в javadoc, fixtures на
   безымянную форму: `@PathVariable(name = "id") Long id` → `@PathVariable
   Long id`, `@RequestParam(name = "page") Integer page` → `@RequestParam
   Integer page` (fallback на имя Java-параметра работает, ADR-0017;
   fail-fast #212 ловит расхождение при создании прокси). Правила:
   - явный `name` держать там, где **query ≠ q** (имя параметра ≠ имя
     биндинга) — это и есть его единственная ниша;
   - грабля #292: у `@Post/@Put/@Delete` при **втором** атрибуте
     (`requestType`/`params`) implicit `value` не компилируется — такие
     декларации в примерах держат `value = `;
   - тесты/фикстуры — по желанию (не обязательная часть P2; код не
     меняется, гейт doc-only: `./gradlew test` можно не гнать, если не
     трогал .java — но при правке тестовых контроллеров — гнать полный).
   P2 = чистая документационная сессия (README + javadoc-примеры), по
   завершении — пометка в ADR-0067 Amendment/Status.
4. **Затем P3 (`${id}` → `{id}`; решение Антона получено):** механическая,
   но широкое: маска в `ControllerValidator.validatePath` (same-length
   замена `${…}` → `{…}`), экстрактор плейсхолдеров валидатора (#210),
   replace в `HttpTemplateBuilder`/`HttpTemplate` (`createUrl`/`createUri`),
   все `${}` в тестах/README/javadoc. `URI.create`-гейт (#211) отловит
   «случайные» `{}` в пути. TDD: красный = тест с `{id}` до смены маски,
   полный гейт после.
5. **Затем P4-микропроверка:** статические query прямо в пути
   `@Get("/items?size=10")` — merge с динамическими `@RequestParam` и
   плейсхолдерами (merge-пломбинг `base.contains("?") ? "&" : "?"` в
   `HttpTemplate`); зелёное — задокументировать в README + ADR-0067,
   кривое — баг по TDD.
6. **№7 (rss/atom)** всё ещё заблокирован на слове Антона (A/B: parse-only
   vs fetch-and-parse; ядро vs codec-субмодуль — вопрос висит с #284).
7. **Хвосты (не эпики):** Path/File-тела метрик (-1; engine-reported byte
   accounting), `@ExpectStatus`/`@FollowRedirects` (фича, не дыра).
8. **Грабли/паттерны (важно!):**
   - **Implicit `value` — только один абстрактный метод** (грабля #292,
     выше) — проверять каждую декларацию с вторым атрибутом.
   - Приём WIP: счётчик сессий +N без коммитов = прерванные сессии;
     верификация диффа → гейт → память (паттерн #155/#186/#254/#274/#279).
   - Счётчики тестов — питоном по `TEST-*.xml` (`bc` нет); JaCoCo XML:
     `mi/ci/mb/cb` у `<line>`, XML пишет только корневой модуль.
   - Фильтр тестов `:test --tests 'X'` (без ведущего `:` заденет субмодули).
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен.
   - Русско-английская билингва: перечитывать doc-тексты сразу до коммита.
   - Часовой пояс контейнера +04 (логи log4j — UTC); счётчик сессий: **292**.
   - Из извлечённого коллаборатора — свой сейт до 0 missed (урок #271) и
     private no-arg-ctor (грабля #283).
