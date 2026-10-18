# Last Session: #295 (2026-10-19)

## Что сделано

**Сессия #295: Кандидат №6, P4 исполнен — микропроверка статических query
прямо в пути + TDD баг-фикс (гейт 1,644/1,644 = 1,638 + 6 новых тестов,
javadoc чист под `-Werror`). Кандидат №6 (ADR-0067) ПОЛНОСТЬЮ ЗАКРЫТ
(P1–P4, P5 осознанно не трогаем).**

1. *Зелёная часть (без фикса):* статическая query в пути
   `@Get("/items?size=10")` без `@TargetUrl` всегда работала — `createUri`
   без `requestParams` отдаёт URI как есть, `appendQuery` fragment-aware и
   достает динамические `@RequestParam` через `&`. Новое e2e-покрытие в
   `RequestParameterFlexibilityTest.StaticQueryPathApi`: (a) сама, (b) +
   `@RequestParam` (`?size=10&page=2`), (c) + `{id}` (`/items/42?size=10`).
2. *Красное (TDD):* `@TargetUrl` с собственной query (`?a=1`) + путь с
   `?size=10` давал `…/items?size=10?a=1` — двойной `?`, сервер парсит
   `size=10?a=1` одним значением, `a=1` теряется; `URI.create` не ругается
   (RFC-легальный синтаксис) — тихий баг. Красные тесты:
   `TargetUrlTest.targetUrlWithQueryMergesStaticPathQuery` и
   `targetUrlStaticPathQueryPlusDynamicParamMergesAll` (404 на WireMock).
3. *Фикс:* `HttpTemplateBuilder.createUrl()` — path query отрезается до
   `joinUrlPath`, сшивается `&`-ом перед query target: `…?size=10&a=1`;
   динамика вызова — последней через `appendQuery` (`&limit=5`). Порядок
   на проводе: статика пути → query target → динамика вызова. Кейсы
   «target без query» / «оба без query» — поведение без изменений.
4. *Доки:* README — буллет «Static query in the verb path» (секция
   Request parameter & header flexibility) + уточнение Composition-буллета
   `@TargetUrl` (порядок merge). ADR-0067: Status «P4 реализован (#295, с
   баг-фиксом)» + `## Amendment #295`. Новых публичных API нет.
5. *Гейт:* **1,644/1,644** green across all modules (root 1,564 + sub 80),
   `:javadoc` чист под `-Werror`. DRAFT: блок #295, кандидат №6 закрыт.

## Контекст для следующей сессии (#296)

1. **Ветка** `agent-loop`; HEAD — коммит P4 (см. `git log -1`).
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД — хвост Map-префикса fallback** (находка прогона P2,
   #293): bare `@RequestParam Map filter` шлёт голые `status=…`, а named
   `@RequestParam(name="filter") Map` — `filter[status]=…`, потому что
   префикс строится из `rawRequestParamName()`, а не из эффективного имени
   ADR-0017-fallback (`HttpTemplateBuilder.createRequestParams`, Map-ветка,
   строки ~746). Варианты: (а) довести fallback до Map-префикса
   (bare ≡ named) — TDD: красный тест «bare `@RequestParam Map` →
   ожидание префикса», либо (б) задокументировать различие явно (README
   уже говорит «if name() is specified» — возможно, достаточно уточнить).
   Сначала прочитать обе ветки `createRequestParams` и решить, что дешевле
   и правильнее. Маленькая TDD-сессия.
4. **№7 (rss/atom)** всё ещё заблокирован на слове Антона (A/B:
   parse-only vs fetch-and-parse; ядро vs codec-субмодуль — вопрос висит
   с #284).
5. **Хвосты (не эпики):** Path/File-тела метрик (-1; engine-reported byte
   accounting), `@ExpectStatus`/`@FollowRedirects` (фича, не дыра),
   fixtures-javadoc (15 типов).
6. **Грабли/паттерны (важно!):**
   - Плейсхолдеры `{id}`: маска `\{([^}]*)}` в `ControllerValidator`;
     незакрытая `{id` ловится гейтом `URI.create` (#211). Traceparent-`${…}`
     в `Trace.java`/`TraceContext.java` — НЕ трогать.
   - Map-префикс смотрит `rawRequestParamName()` (грабля #293) — любое
     расширение ADR-0017-fallback проверять на Map-раскрытии.
   - Статическая query в пути: merge-порядок `путь ?…` → `&` → query
     target → `&` → динамика (фикс #295, `createUrl`). Двойной `?`
     `URI.create` не ловит — проверять только e2e (WireMock `urlEqualTo`).
   - Implicit `value` — только один абстрактный метод (грабля #292);
     `value = ` со вторым атрибутом держать (паттерн #292).
   - `@MultipartFormFile.partName()` — required (нет default'), грабля #209.
   - Исторические ADR со OLD-снапшотами не переписывать.
   - Счётчики тестов — питоном по `TEST-*.xml` всех модулей
     (`bc` нет). Фильтр тестов `:test --tests 'X'` (ведущий `:` обязателен).
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен.
   - XML-отчёты атрибутируют упавшие тесты на предшествующие self-closing
     testcase'ы (наблюдение #294).
   - Русско-английская билингва: перечитывать doc-тексты сразу до коммита.
   - Часовой пояс контейнера +04 (логи log4j — UTC); счётчик сессий: **295**.
   - Из извлечённого коллаборатора — свой сейт до 0 missed (урок #271) и
     private no-arg-ctor (грабля #283).
