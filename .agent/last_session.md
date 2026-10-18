# Last Session: #296 (2026-10-19)

## Что сделано

**Сессия #296: хвост Map-prefix fallback (находка #293) решён — «задокументировано
by design», wire-формат не менялся (гейт 1,644/1,644 в силе, код — только
комментарии).**

1. *Исследование:* Map-префикс в `HttpTemplateBuilder` (query `createRequestParams`
   ~L757 + multipart `createMultipartParts` ~L973) строится из
   `rawRequestParamName()`, тогда как скалярный путь использует эффективное имя
   с fallback ADR-0017 на имя Java-параметра. Поведение записано explicitly в
   ADR-0017 Aspect 2, в Javadoc `@RequestParam.name()` («uses map keys directly»)
   и в README (префикс — «if name() is specified»). Оба режима e2e-зафиксированы:
   `GetMethodTest` (плоское `q=java&page=2` / `filter[status]`),
   `RequestParameterFlexibilityTest` (named query Map, bare query Map, bare form
   Map), `MultipartTest` (flat parts / `meta[category]`), `HttpTemplateBuilderTest`
   (unit, оба режима).
2. *Решение:* вариант **(б) — задокументировать**, а не доводить fallback. Для Map
   `name()` — не имя параметра, а **опциональный namespace-префикс**: пустой =
   «без префикса», ключи как есть (индустриальный формат `@QueryMap` — Retrofit/
   Feign/Spring шлют bare Map плоским); указанный = `name[key]`. Вариант (а)
   (bare ≡ named, префикс по имени Java-параметра) делал бы плоский режим
   **недостижимым** — любой Map получал бы префикс — тихий wire-брейк против
   «strict backward compatibility» самого ADR-0017. Согласовано с Amendment #212
   (валидатор осознанно исключает Map из `-parameters`-проверки: имена берутся из
   записей, а не из параметра).
3. *Изменения:* README — буллет «Dynamic `Map<?, ?>` parameters» теперь явно
   описывает дефолт (ключи как есть) и контраст со скалярами; ADR-0017 —
   `## Amendment #296` (решение, обоснование, список закреплённых тестов);
   `HttpTemplateBuilder` — комментарии на обоих Map-call-site (код не изменился).
4. *Гейт:* **1,644/1,644** green across all 5 modules, javadoc чист под `-Werror`.
   Новых публичных API нет.

## Контекст для следующей сессии (#297)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #296 (см. `git log -1`).
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД (автономный, бэклог):** хвосты — fixtures-javadoc (15 типов)
   или Path/File-тела метрик (-1; engine-reported byte accounting). Кандидат
   №7 (rss/atom) всё ещё заблокирован на A/B-слове Антона (parse-only vs
   fetch-and-parse; ядро vs codec-субмодуль — вопрос висит с #284).
4. **Хвосты (не эпики):** `@ExpectStatus`/`@FollowRedirects` (фича, не дыра).
5. **Грабли/паттерны (важно!):**
   - Map-префикс читает `rawRequestParamName()` — теперь **осознанно** (ADR-0017
     Amendment #296): bare Map шлёт плоские ключи. Не «чинить» в эффективное имя
     без явного решения о wire-изменении (плоский режим станет недостижимым).
   - Плейсхолдеры `{id}`: маска `\{([^}]*)}` в `ControllerValidator`; незакрытая
     `{id` ловится гейтом `URI.create` (#211). Traceparent-`${…}` в
     `Trace.java`/`TraceContext.java` — НЕ трогать.
   - Статическая query в пути: merge-порядок `путь ?…` → `&` → query target → `&`
     → динамика (фикс #295, `createUrl`). Двойной `?` `URI.create` не ловит —
     проверять только e2e (WireMock `urlEqualTo`).
   - Implicit `value` — только один абстрактный метод (грабля #292); `value = `
     со вторым атрибутом держать (паттерн #292).
   - `@MultipartFormFile.partName()` — required (нет default'), грабля #209.
   - Исторические ADR со OLD-снапшотами не переписывать (добавлять Amendment —
     ок, паттерн #295/#296).
   - Счётчики тестов — питоном по `TEST-*.xml` всех модулей (`bc` нет). Фильтр
     тестов `:test --tests 'X'` (ведущий `:` обязателен).
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен.
   - XML-отчёты атрибутируют упавшие тесты на предшествующие self-closing
     testcase'ы (наблюдение #294).
   - Русско-английская билингва: перечитывать doc-тексты сразу до коммита.
   - Часовой пояс контейнера +04 (логи log4j — UTC); счётчик сессий: **296**.
   - Из извлечённого коллаборатора — свой сейт до 0 missed (урок #271) и
     private no-arg-ctor (грабля #283).
