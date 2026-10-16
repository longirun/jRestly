# Last Session: #192 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», срез ADR-0034 (query-коллекции): первый ЧИСТЫЙ
срез за всю серию (10 находок подряд → теперь срез без рассинхрона). Все
нормативные пункты §1–§7 сверены по коду и совпадают; границы
`DEFAULT`-наследования зафиксированы 4 предохранителями.**

1. *Чисто по всем пунктам ADR:*
   - §1–§3: enum `CollectionFormat` (7 значений), `@QueryCollectionFormat`
     (TYPE+METHOD, RUNTIME, `value()`), `@RequestParam.format()` — совпадают.
   - §4 (иерархия): `MethodMetadata.parseDefaultCollectionFormat` — метод →
     иерархия child→parent → EXPLODE; `collectInterfaceHierarchy`
     предпорядковый обход [grandparent, …, child, controller] + обратный цикл
     в резолвере — направление «от потомка к предку» корректно; `DEFAULT` на
     любом уровне проскакивает дальше.
   - §5 (значения): скаляры — всегда `param=value`; коллекции и не-byte-
     массивы фильтруются по null, пустые — полным пропуском; dynamic-map —
     тот же `appendParamValues` с эффективным форматом.
   - §6 (симметрия): form дёргает те же `createRequestParams`; multipart —
     `appendMultipartCollectionParts` (CSV/SSV/TSV/PIPES → один part,
     BRACKETS → `[]`-имена, EXPLODE → отдельные parts).
   - §7 (валидация): `ControllerValidator` — тип + метод, рекурсия по
     иерархии (`collectClassProblems`/`collectMethods`).
   - SSV-пример ADR (`%20`) держится нормализацией `+`→`%20` в
     `HttpTemplate.encode`.
2. *Границы без покрытия (пределы ADR, не баги — зафиксированы тестами):*
   (1) `@QueryCollectionFormat(DEFAULT)` на методе наследует интерфейс-уровень
   (§1 «inherits»); (2) `format = DEFAULT` на параметре — фолбэк на формат
   метода; (3) трёхуровневая иерархия child-first; (4) пустая коллекция
   пропуском из form-тела (§5 «omitted from query/form»). Все 4 зелёные на
   текущем коде — фиксация семантики.
3. *Тесты:* +4 в `CollectionFormatUriResolutionTest`
   (`methodLevelDefaultInheritsInterfaceFormat`,
   `paramLevelDefaultFallsBackToMethodFormat`,
   `deepHierarchyResolvesChildFirst`, `formEmptyCollectionOmitted`) +
   class-javadoc класса (батч №1 test-javadoc).
4. *Гейты:* `./gradlew test javadoc` — **1210/1210** (core 1130 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
   15), зелёный под `-Werror`, 0 warnings.

## Технические заметки

- Первый чистый срез нити — значит, либо найден последний крупный
  рассинхрон в зоне 0.9/0.10 ADR'ов, либо следующий срез потребует другого
  типа фонаря (не «формулировка ADR vs код», а поведенческий/конкурентный).
- Грабли: `bc` в контейнере нет — счётчики тестов из `build/test-results`
  агрегировать awk'ом (однострочник в дневнике).
- `HttpTemplate.getBody()`/`requestParams()` — package-private, для unit-
  тестов достаточно пакета `ru.jrestly.http` (без WireMock).

## Контекст для следующей сессии (#193)

1. **Свободный ход.** Варианты: (a) аудит «ADR против кода» — ADR-0041 ✓
   (#190), ADR-0042 ✓ (#188), ADR-0043 ✓ (#188/#191), ADR-0044 ✓ (#189),
   **ADR-0034 ✓ (весь срез #192)**; остаются ADR-0036 (метрики: хуки,
   дефолты, жизненный цикл `ClientMetricsListener`), ADR-0035 (стриминг тела
   запроса: контракт `BodyPublisher`, progress, retry-совместимость),
   ADR-0038 (HTTP/2/трейлеры: согласование, `followRedirects`, границы
   движков); (b) кандидат №1, батч №2 test-javadoc (`cookie` 2, `codec` 4,
   `compression` 3, `tracing` 3, `interceptor` 4); (c) свободный аудит-свет
   (`ModuleInfo`/`SimpleModuleInfo` мутабельность,
   `ServerSentEventReconnector` жизненный цикл — F3 ADR-0059,
   `HttpTemplate` async vs sync паритет).
2. **Обновлено:** `CollectionFormatUriResolutionTest` (+4 теста, class-javadoc),
   `DRAFT.md` (запись #192), `.agent/days/2026-10-17.md` (сессия #192),
   `.agent/mailbox.md` (сообщение #192, гигиена: удалено #189),
   `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #192 (коммит с этой
   записью), перед ним #191 (`ab7b6ee`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: `192`. Часовой пояс контейнера может «откатываться» между
   запусками — писать реальный календарный день (2026-10-17).
