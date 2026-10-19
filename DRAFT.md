# DRAFT: активная зона после закрытия Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055)

> **Сессия #303 (свободный аудит ResponseHeaders & CircuitBreakers: устранены dead checks в ResponseHeaders и CircuitBreakers, покрыт CircuitBreakerOpenException(null); 100% METHOD/CLASS coverage — 1449/1449, 149/149; BRANCH 89.98%, COMPLEXITY 88.10%; гейт 1,661/1,661):**
> 1. *ResponseHeaders dead-checks:* (a) `version()` — `this.version` гарантированно non-null (инициализирован и нормализуется в `doPopulate`), тернарный оператор заменен на прямой возврат; (b) `firstTrailer` и `firstValue` — списки в `trailerMap` и `headerMap` не бывают пустыми, убраны избыточные `values.isEmpty()` проверки; (c) `declaredTrailers` — убран избыточный `val != null`; (d) `parseCookies` — убраны избыточные `header != null`; (e) `cookieMap` — убраны мертвые `c.getName() != null` и `c.getValue() != null ? ... : ""` (парсер `HttpCookie` гарантирует не-null). `ResponseHeaders` достиг 100% инструкций (1222/1222) и строк (281/281), missed branches упали с 15 до 7.
> 2. *CircuitBreakers:* в `evaluateHalfOpenTransition()` устранена недостижимая ветка `else -> tripToOpen()`: неудачи в `HALF_OPEN` мгновенно триггерят `tripToOpen()` в `recordFailure`, поэтому в `evaluateHalfOpenTransition()` приходят только успешные вызовы (схема возвращается в `CLOSED` при достижении порога пробных вызовов).
> 3. *CircuitBreakerOpenException:* покрыт случай `remainingWaitDuration == null` (дефолт `Duration.ZERO`). `CircuitBreakerOpenException` достиг 100% покрытия инструкций и ветвей.
> 4. *Гейты:* **1,661/1,661** green во всех 5 модулях (+1 новый тест, 0 failures/errors), javadoc чист под `-Werror`.
>    JaCoCo: CLASS **149/149 (100%)**, METHOD **1449/1449 (100%)**, INSTRUCTION **98.09%**,
>    BRANCH 3985/4429 (**89.98%**, было 89.80%), LINE **97.75%**, COMPLEXITY **88.10%** (было 87.86%) — новый исторический рекорд.
> 5. *Дальше:* хвост `byte[]`-скаляра в headers/params (решить wire-формат) или аудит engine/tracing/codec. Кандидат №7 (rss/atom) ждёт A/B-ответа Антона. Highest ADR **0067**, next free **0068**.

> **Сессия #302 (хвосты HttpTemplateBuilder-аудита: удалены dead default-ветки
> switch CollectionFormat; покрыто объединение заголовка Cookie с @Cookie;
> проверен Path knownBodySize; 100% METHOD/CLASS coverage — 1449/1449,
> INSTRUCTION 98.09%, BRANCH 89.80%; гейт 1,660/1,660):**
> 1. *Удаление dead default-веток:* в `appendFormattedItems` и
>    `appendMultipartCollectionParts` нормализация `DEFAULT`→`EXPLODE`
>    оставляла ветку `default ->` мёртвым кодом (дублировала `EXPLODE`).
>    Заменено на исчерпывающий switch `case EXPLODE, DEFAULT ->`, покрывающий
>    все 7 констант `CollectionFormat` без `default`-ветки (-4 пропущенных ветви в JaCoCo).
> 2. *Cookie merging:* покрыта ветка `createHeaders`: объединение существующего
>    заголовка `Cookie` (статического или переданного через `@RequestHeader(name = "Cookie")`)
>    с вычисленными куками из `@Cookie` аннотаций (`h.value() + "; " + cookieHeader`).
>    *Грабля отлова:* `@RequestHeader("Cookie")` по ошибке биндит в `value` (игнорируемый на параметрах),
>    а имя бралось из имени Java-параметра — закреплена форма `name = "Cookie"`.
> 3. *METHOD coverage 100%:* добавлен тест чтения `HttpTemplate.getKnownBodySize()`
>    для Path тела, подтверждающий соответствие размеру файла на диске (ADR-0036 Amendment #298).
> 4. *Гейты:* **1,660/1,660** green across all 5 modules (+1 новый тест), javadoc чист под `-Werror`.
>    JaCoCo: CLASS **149/149 (100%)**, METHOD **1449/1449 (100%)**, INSTRUCTION **98.09%**,
>    BRANCH **89.80%**, LINE **97.75%**, COMPLEXITY **87.86%** — все показатели на абсолютном рекорде.
> 5. *Дальше:* хвост `byte[]`-скаляра в headers/params (решить wire-формат) или концепт/аудит
>    resilience/codec/engine. Кандидат №7 (rss/atom) ждёт A/B-ответа Антона. Highest ADR **0067**, next free **0068**.

> **Сессия #301 (свободный аудит HttpTemplateBuilder-кластера — найден и
> закрыт двойной расчёт URL на ambient-пути TracePropagator;
> гейт 1,659/1,659):**
> 1. *Аудит (файл прочитан целиком, 1464 строки):* (a) **подтверждён
>    найденный в эстафете #300 подозреваемый** — `createUrl()` вызывался
>    ДВАЖДЫ за один `build()`: в `build()` (L239, `result.setUrl`) и в
>    `applyTracePropagator` (L649) на ambient-пути §6.4 ADR-0042 (нет
>    активной `@Trace` + настроенный клиентский `TracePropagator`) — каждый
>    такой запрос прогонял подстановку path-переменных и query-join'ы в
>    двойном экземпляре; входные поля между вызовами не мутируются → второй
>    результат value-идентичен первому, чистое дублирование вычислений и
>    аллокаций на горячем пути; (b) `byte[]`-скаляр в
>    `appendHeaderValues`/`appendParamValues` падает в `value.toString()`
>    (`[B@…`) — реальный край, но `byte[]` не задокументирован типом
>    аргумента `@RequestHeader`/`@RequestParam` (в multipart-ветках — raw
>    bytes по ADR-0031/0033) — **оставлен в беклог** (решение о wire-формате
>    — отдельный шаг); (c) dead `default`-ветки в `appendFormattedItems`/
>    `appendMultipartCollectionParts` (дублируют `EXPLODE` после
>    `DEFAULT`→`EXPLODE`-нормализации) — косметика, в беклог; (d)
>    defensive-null-проверки `methodMetadata` в `resolve*` — by design
>    (nullable по контракту публичного javadoc); (e) Map-префикс
>    `rawRequestParamName()` — осознанно (Amendment #296), не тронут.
> 2. *TDD red-first:* `HttpTemplateBuilderTest.
>    testTracePropagatorReceivesBuiltUrlComputedOnce` — сейт
>    `CountingCharSequence` (`@TargetUrl` принимает `CharSequence` по
>    `ControllerValidator`, `toString()` со счётчиком) упал ровно на счёт:
>    expected 1, was 2; плюс инвариант «пропигатор наблюдает URL,
>    идентичный `template.getUrl()`» (ADR-0042 §6.4).
> 3. *Фикс:* URL вычисляется ровно один раз в `build()` и пробрасывается
>    `createHeaders(url)` → `applyTracingHeaders(result, url)` →
>    `applyTracePropagator(result, url)`; `TraceRequestContext` получает тот
>    же `String`, что и `HttpTemplate`. Поведение и публичный API не тронуты,
>    только `//`-комментарий в main (javadoc-гейт не нужен).
> 4. *Документация:* ADR-0042 — `## Amendment #301`.
> 5. *Гейт:* **1,659/1,659** green across all 5 modules (+1, 0
>    failures/errors).
> 6. *Дальше:* хвосты `HttpTemplateBuilder` (byte[]-скаляр в
>    headers/params — решить wire-формат; dead `default`-ветки) или валидация
>    `@ExpectStatus`/`@FollowRedirects` (#207, при тихом исходе) или новый
>    свободный аудит. Кандидат №7 (rss/atom) всё ещё ждёт A/B-ответа Антона.
>    Highest ADR **0067**, next free **0068**.

> **Сессия #300 (свободный аудит HttpTemplate/Redirects — найден и закрыт
> тихий баг: sync 401-refresh failure leg терял суммарный attempts();
> гейт 1,658/1,658):**
> 1. *Аудит (кандидаты выверены по коду + ADR, три закрыты как by design):*
>    (a) `isExpectedStatus` при `expectStatuses != null` игнорирует
>    `conditional`/`optional`-allowances — это «strict precedence»
>    `@ExpectStatus` по ADR-0023 Decision 1 (пустой список отрезан eager-
>    валидацией, ADR-0004 Amendment #235); (b) sync `acquire()`/
>    `acquirePermission()` vs async `tryAcquire`/`tryAcquirePermission` —
>    стилистическая асимметрия, дефолты resilience-интерфейсов реализованы
>    через те же try-варианты, семантика идентична; (c) CFE-порядок
>    `attemptsRef`-аккумуляции async 401-refresh верен — `whenComplete`
>    регистрируется раньше downstream-стадий, CFE гонит dependents по
>    порядку регистрации.
> 2. *Найденный баг:* sync 401-refresh — аккумуляция счёта первой фазы
>    стояла ПОСЛЕ `executeWithRetries` (только success-нога). Refresh-фаза,
>    умершая на проводе (IOException после retry-исчерпания /
>    InterruptedException), пропускала аккумуляцию: вызов 401 → refresh →
>    connection reset репортил `attempts() == 1`, `isRetried() == false` и
>    ломал инвариант Amendment #196 (последнее число `onAttemptComplete` =
>    `attempts()`). Async twin уже суммировал (whenComplete на failure тоже
>    срабатывает).
> 3. *TDD + фикс:* red-first `ClientMetricsWireMockTest.
>    refresh401WireFailureStillSumsBothPhases` (sync: expected 2, was 1) +
>    twin-parity `…Async` (зелёный, зафиксирован). Фикс — `try/finally`
>    вокруг второй `executeWithRetries`: аккумуляция на завершении (success
>    OR failure), точные `whenComplete`-семантики async-двойника;
>    success-нога value-идентична старой.
> 4. *Документация:* ADR-0036 — `## Amendment #300`.
> 5. *Гейт:* **1,658/1,658** green across all 5 modules (+2, 0
>    failures/errors). Javadoc в main не тронут.
> 6. *Дальше:* кандидат №7 (rss/atom) ждёт A/B-ответа Антона (с #284);
>    оставшийся хвост — валидация `@ExpectStatus`/`@FollowRedirects`
>    (#207, трогать лишь при тихом исходе) или свободный аудит
>    `HttpTemplateBuilder`-кластера (в #300 не читался). Highest ADR
>    **0067**, next free **0068**.

> **Сессия #299 (приём и верификация WIP прерванной #298 — engine-reported byte
> accounting для Path/File-тел и полностью известных multipart-тел: хвост
> non-goal ADR-0036 Amendment #193 закрыт; гейт 1,656/1,656):**
> 1. *Приём WIP (паттерн #155/#186/#254):* счётчик сессий был уже докручен до 299,
>    HEAD стоял на #297 — в дереве лежал полный комплект (9 файлов: код + тесты)
>    по хвосту Path/File-тел метрик. Диффы прочитаны байт-в-байт и сверены с
>    семантикой: (a) `WireRequest` получил 9-й nullable record-компонент
>    `knownBodySize` (Long) с сохранившимся 8-аргументным
>    backward-compatible-конструктором; (b) `HttpTemplateBuilder.fileSizeBytes(Path)`
>    (через `Files.size`, исчезнувший файл → null) заполняет его для
>    Path/File-аргументов `@RequestBody`; (c) порядок резолва
>    `MetricsNotifier.requestBytes`: буферный `byte[]` → `knownBodySize` →
>    rendered `contentLength()` у multipart (сбой → -1) → -1; supplier-rendered
>    тела остаются -1 (контракт Amendment #193 не сломан).
> 2. *Wither-семантика (проверена payload-aware):* transport-витеры сохраняют
>    `knownBodySize`; `withBody`/`withBodyPublisher`/`withBodyPublisherSupplier`
>    очищают его; `withParts` — только когда выставлены непустые parts;
>    `Redirects.nextHop` сохраняет на 307/308 и сбрасывает при GET-downgrade.
>    Единственная точка `new WireRequest(...)` — `HttpTemplate.exchange()`; async
>    переиспользует тот же запрос (второй конструктор не упущен).
> 3. *Тесты:* +12 — юнит-матрица `MetricsNotifierBytesTest` (+5: knownBodySize
>    приоритет, multipart contentLength, fallback -1), `WireRequestTest` (+4:
>    wither/builder-матрица), e2e `RequestBodyStreamingWireMockTest` (+3: новые
>    эндпоинты `/upload/path-metrics` и `/upload/file-metrics`, метрики против
>    реального размера файла).
> 4. *Документация:* ADR-0036 — `## Amendment #298` (non-goal «Path/File-тела —
>    -1» закрыт: движок репортит то, что сам измерил; supplier-контракт и
>    -1 для неизвестных размеров не тронуты).
> 5. *Гейт:* **1,656/1,656** green across all 5 modules (+12, 0 failures/errors),
>    javadoc чист под `-Werror`.
> 6. *Дальше:* оставшийся хвост — валидация `@ExpectStatus`/`@FollowRedirects`
>    (#207 «фича, не дыра», трогать лишь при тихом исходе) или свободный аудит;
>    кандидат №7 (rss/atom) всё ещё ждёт A/B-ответа Антона (с #284). Highest ADR
>    **0067**, next free **0068**.

> **Сессия #297 (хвост fixtures-javadoc — закрыт: class-level javadoc у всех
> 17 типов `ru.jrestly.fixtures`; гейт 1,644/1,644 в силе, код — только
> doc-комментарии):** последний незакрытый хвост кандидата №1 (test-javadoc):
> 1. *Состав:* лаконичный class-level javadoc (3–5 строк, стиль
>    `RetryController`/`TestFutures`: «что это + какие сьюты/ADR держит») у
>    **15** типов без doc: контроллеры (`TestController` — общий декларативный
>    фикстур core-сьют, `PathVariableController`, `NullableFieldController`,
>    `BrokenController` — невалидные декларации для `ControllerValidator`,
>    `HttpVersionController`, `DateTimeController`,
>    `RedirectMethodController`), DTO-записи (`TestDto`, `TestErrorDto`,
>    `EventDto`, `NullableFieldDto`) и инфраструктура
>    (`TestModuleInfo`/`TestApiClient`/`TestEnvironment`/`TestAuthProvider`).
>    У `RetryController` и `TestFutures` doc уже был — не тронуты.
> 2. *Факты, проверенные перед записью:* ADR-номера для ссылок —
>    `@OnError` = ADR-0028, `@HttpVersion` = ADR-0038, name-fallback =
>    ADR-0017 Amendment #212, auth-override на кросс-оригинном редиректе =
>    ADR-0002 Amendment #253, capture-count `TestAuthProvider` = ADR-0011
>    Aspect 7 (комментарий уже был в коде — перенесён в doc).
> 3. *Гейт:* **1,644/1,644** green across all 5 modules (логика не менялась),
>    javadoc чист под `-Werror`. Новых публичных API нет.
> 4. *Итог нити:* кандидат №1 (test-javadoc) + хвост fixtures **закрыты
>    полностью** — во всём дереве классы без class-level javadoc отсутствуют.
>    Highest ADR **0067**, next free **0068**. Дальше: Path/File-тела метрик
>    (-1; engine-reported byte accounting) или новый свободный аудит.

> **Сессия #296 (хвост Map-prefix fallback — решён: «задокументировано by
> design», wire-формат не менялся; гейт 1,644/1,644 в силе):** хвост находки
> #293: bare `@RequestParam Map filter` шлёт плоские `status=…`, а named
> `@RequestParam(name="filter") Map` — `filter[status]=…`, т.к. Map-префикс
> строится из `rawRequestParamName()`, а не из эффективного имени ADR-0017.
> 1. *Исследование:* обе ветки (query `createRequestParams` ~L757 и multipart
>    `createMultipartParts` ~L973) читают сырое имя аннотации, скалярный путь
>    — эффективное. Поведение записано explicitly в ADR-0017 Aspect 2 («name()
>    пусто → ключ карты становится именем параметра»), в Javadoc
>    `@RequestParam.name()` («uses map keys directly») и README (префикс — «if
>    name() is specified»). Оба режима e2e-зафиксированы: `GetMethodTest`
>    (плоское `q=…` / `filter[status]`), `RequestParameterFlexibilityTest`
>    (named query + bare query + bare form), `MultipartTest` (flat parts /
>    `meta[category]`), `HttpTemplateBuilderTest` (unit, оба режима).
> 2. *Решение — вариант (б), задокументировать:* для Map `name()` — не имя
>    параметра, а **опциональный namespace-префикс**; пустой = «без префикса»
>    (ключи как есть) — индустриальный формат `@QueryMap` (Retrofit/Feign/
>    Spring шлют bare Map плоским). Вариант (а) (bare ≡ named) делал бы плоский
>    режим недостижимым (любой Map получал бы префикс — явный или по имени
>    Java-параметра) — тихий wire-брейк против «strict backward compatibility»
>    самого ADR-0017. Согласовано с #212 (валидатор осознанно исключает Map из
>    `-parameters`-проверки — имена у Map берутся из записей).
> 3. *Изменения:* README — буллет «Dynamic `Map<?, ?>` parameters» теперь
>    явно описывает дефолт (ключи как есть) и контраст со скалярами; ADR-0017
>    — `## Amendment #296` (решение, обоснование, список закреплённых тестов);
>    `HttpTemplateBuilder` — комментарии на обоих call-site (код не изменился,
>    интент сделан самодокументируемым).
> 4. *Гейт:* **1,644/1,644** green across all 5 modules (код — только
>    комментарии), javadoc чист под `-Werror`. Новых публичных API нет.
> 5. *Дальше:* кандидат №7 (rss/atom) всё ещё ждёт A/B-слова Антона
>    (parse-only vs fetch-and-parse; ядро vs codec-субмодуль — с #284);
>    хвосты: fixtures-javadoc (15 типов), Path/File-тела метрик (-1;
>    engine-reported byte accounting). Highest ADR **0067**, next free **0068**.

> **Сессия #295 (Кандидат №6, P4 — исполнен: микропроверка статических query
> прямо в пути + TDD баг-фикс; гейт 1,644/1,644):**
> 1. *Микропроверка:* статическая query в пути (`@Get("/items?size=10")`) без
>    `@TargetUrl` всегда работала (`createUri` отдаёт URL как есть;
>    `appendQuery` fragment-aware и достает динамические `@RequestParam`
>    через `&`). Новое e2e-покрытие `RequestParameterFlexibilityTest`
>    (a) сама, (b) с `@RequestParam`, (c) с `{id}` — зелёное без фикса.
> 2. *Баг:* `@TargetUrl` с собственной query (`?a=1`) + путь со статической
>    query давал `…/items?size=10?a=1` — двойной `?`, `a=1` растворяется в
>    значении `size`, `URI.create` не ругается (тихий баг). Красные тесты:
>    `TargetUrlTest.targetUrlWithQueryMergesStaticPathQuery` и
>    `…PlusDynamicParamMergesAll` (404 на WireMock).
> 3. *Фикс:* `HttpTemplateBuilder.createUrl()` — статическая query пути
>    сшивается `&`-ом перед query самого target: `…?size=10&a=1`; динамика
>    вызова — последней (`&limit=5`). Порядок на проводе: статика пути →
>    query target → динамика вызова. Кейсы «target без query» и «оба без
>    query» — без изменений.
> 4. *Документация:* README — буллет «Static query in the verb path» +
>    уточнение Composition-буллета `@TargetUrl`. ADR-0067: Status +
>    `## Amendment #295`. Новых публичных API нет.
> 5. *Гейт:* **1,644/1,644** green across all modules (+6 новых тестов),
>    javadoc чист под `-Werror`.
> 6. **Кандидат №6 (ADR-0067) полностью закрыт:** P1–P4 реализованы, P5
>    осознанно не трогаем.
> 7. *Дальше:* **хвост Map-prefix fallback** (находка #293:
>    `createRequestParams` строит Map-префикс из `rawRequestParamName()`, а не
>    из эффективного имени ADR-0017 — довести fallback до Map-префикса
>    (bare ≡ named) или задокументировать различие явно). Highest ADR
>    **0067**, next free **0068**.

> **Сессия #293 (Кандидат №6, P2 — исполнен: безымянные биндинги в
> README/примерах/фикстурах; гейт 1,637/1,637):** документационный прогон
> без изменения поведения (fallback на имя Java-параметра — ADR-0017 +
> fail-fast #212). 32 файла, 93 строки.
> 1. *Правило (применено программно, дифф прочитан строка-в-строку):*
>    `@Ann(name = "X") T x` → `@Ann T x` тогда и только тогда, когда `X == x`
>    и в скобках нет второго атрибута. Явный `name = ` — ровно в своей нише:
>    дискриминаторы (`query ≠ q`, `tag ≠ tags`, `Accept ≠ acceptOverride`),
>    второй атрибут (`encoded`/`format`/`contentType`), статические
>    декларации (`@RequestHeader(name = …, value = …)`,
>    `@RequestDefaultParam`, `partName = ` — у `MultipartFormFile.partName()`
>    нет default'а, так что она держится всегда).
> 2. *Состав:* README (8 мест), javadoc `DownloadProgressListener`, shared
>    fixtures `TestController`/`PathVariableController`, benchmarks (2) и
>    поведенческие тесты (WireMock/integration, ~25 файлов). Тесты
>    декларативной семантики (`ControllerValidationTest`,
>    `MethodMetadataTest`, `*ValidationTest`, `HttpTemplateBuilderTest`)
>    осознанно не тронуты — там `name` есть объект наблюдения.
> 3. *Факты кода, проверенные для прогона:* bare-форма компилируется у
>    `@PathVariable/@RequestParam/@RequestHeader/@Cookie` (`name()` имеет
>    `default ""`; грабля #292 бьёт по `@Ann("…")`, а не по пустым скобкам);
>    `-parameters` стоит в `compileJava` И `compileTestJava` всех модулей;
>    `SpringRestClientBenchmarkClient` не тронут (Spring-аннотация).
> 4. *Находка прогона — fallback неполон для Map-префикса:* первый гейт упал
>    на двух тестах `GetMethodTest` (prefixing `name[key]`) —
>    `HttpTemplateBuilder` строит префикс из `rawRequestParamName()`, а не из
>    эффективного имени (ADR-0017-fallback): bare `@RequestParam Map filter`
>    → голые `status=…`, named → `filter[status]=…`. Это **задокументированное**
>    поведение (README: префикс «if name() is specified»), поэтому в рамках
>    «кода не меняется» декларация `searchWithPrefixedMap` вернулась к явной
>    форме (атрибут — объект наблюдения, как в `*ValidationTest`). **Хвост в
>    беклог:** довести fallback до Map-префикса (bare ≡ named) или
>    задокументировать различие явно — отдельная сессия.
> 5. *Гейт:* **1,637 / 1,637** green across all 5 modules (число тестов не
>    изменилось), javadoc чист под `-Werror`. ADR-0067: Status «P2
>    реализован (#293)», `## Amendment #293`.
> 6. *Дальше:* **P3** (`${id}` → `{id}`: маска `validatePath`, экстрактор
>    плейсхолдеров #210, replace в `createUrl`/`createUri`, все `${}` в
>    тестах/README/javadoc; TDD: красный тест с `{id}` до смены маски) →
>    P4-микропроверка (статические query прямо в пути) → новый хвост
>    (Map-префикс fallback). Highest ADR **0067**, next free **0068**.

> **Сессия #292 (Кандидат №6, P1 — исполнен: приём и фиксация WIP прерванной
> сессии `path()` → `value()` в пяти аннотациях глаголов; гейт 1,637/1,637):**
> Антон ответил в mailbox (2026-10-18): «Даю зеленый свет на твои идеи.
> Самобытность не должна конфликтовать с другими принципами. Лаконичность в
> данном случае перевешивает» — зелёный свет P1/P2/P4 + решение по P3
> (`{id}` берёт: самобытность `${}` уступает паритету с отраслью).
> 1. *Приём WIP (паттерн #155/#186/#254):* в дереве лежал полный комплект P1
>    без коммита (счётчик сессий докручен до 292, HEAD стоял на #287):
>    `path()` → `value()` в `@Get/@Post/@Put/@Patch/@Delete` + все call-site'ы
>    (`MethodMetadata`, `ControllerValidator.resolvePath`, ~95 тест/fixture/
>    бенчмарк-файлов, README, benchmarks) + новый тест
>    `MethodMetadataTest.verbPathDeclaredViaImplicitValueAttribute`. Диффы
>    прочитаны байт-в-байт: stray `path = ` в глаголах по дереву нет,
>    `requestMeta().path()` (accessor `RequestMeta` — другой API) не тронут,
>    historical-ADRs (0.10.0) со старыми снапшотами не переписаны.
> 2. *Полировка:* тройной blank после `VerbValueApi` — к одному; «косметический»
>    перевод `@Post(value = …, requestType = …)` на implicit
>    `@Post("/multipart-test", requestType = …)` не скомпилировался — implicit
>    `value` в Java легален только у аннотации с одним абстрактным методом,
>    а `@Post` несёт ещё `requestType` — возвращено к минимальной форме
>    `value = "/multipart-test"` (грабля записана в ADR-0067 Amendment).
> 3. *Гейт:* **1,637 / 1,637** green across all 5 modules (+1), javadoc чист
>    под `-Werror`. ADR-0067: Status → «P1 реализован (#292)»,
>    `## Amendment #292`.
> 4. *Дальше:* P2 (безымянные биндинги в README/примерах — код не меняется,
>    дискриминирующие `query ≠ q` держат явный name), P3 (`${id}` → `{id}`:
>    маска `validatePath`, экстрактор #210, `createUrl`/`createUri`, README,
>    тесты), P4-микропроверка (статические query прямо в пути). Highest ADR
>    **0067**, next free **0068**.

> **Сессия #287 (Кандидат №8 — ревизия старого синтетического бенчмарка; код НЕ
> менялся, гейт 1,636/1,636 от #283 в силе):** автономный ход из беклога Антона
> («не убежали ли мы от самого главного?» — после Zero-Garbage ADR-0055 и
> декомпозиции ADR-0066). Антон молчит по P1–P4 ADR-0067 — №8 исполняется без
> его слова (он автономен, стенд и JMH-матрица в дереве).
> 1. *Прогон стенда той же командой, что публикация (BENCHMARKS.md, 1 fork /
>    2×1s warmup / 5×1s):* (a) in-memory dispatch — `jrestly_inMemoryMockDispatch`
>    **6.63 µs/op (±4.26)** и `jrestly_inMemoryMockPing` **2.58 µs/op (±0.14)**
>    при baseline raw-Jackson 1.24 µs/op — декларативный слой остался на единицах
>    микросекунд, внутри исторического коридора ~5–9 µs/op;
>    (b) аллокации сетевого пути — `jrestly_getUser` **29,136 B/op (±1,347)** и
>    `jrestly_ping_zeroDepRaw` **26,609 B/op (±1,008)** против рекордов финала
>    ADR-0055 (#135) 49,378 / 47,392 B/op: **ниже** базы (GC-norm метрики жёсткие,
>    ±5% — не шум абсолютных µs-колонок) — интерпретация «аллокационная
>    экономика пережила декомпозицию», а не переклейка рекорда (абсолютные
>    байты JVM/classpath-чувствительны, см. Honest limitations);
>    (c) сетевая матрица getUser/ping × 10 клиентов — групповая структура жива:
>    быстрая группа (OkHttp/HC5 raw + Retrofit + jRestly на внешних движках)
>    ~0.22–0.43 ms/op, JDK-транспортная (raw 1024 / jRestly 1314 µs getUser)
>    ~0.9–1.3 ms/op, Feign 1820 / Spring 2177 µs по-прежнему позади; error-бары
>    прогона широкие (шумная машина) — инверсий групп нет (#147/#158
>    воспроизводятся по группам).
> 2. *Вывод №8: типовой метод не «убежал»* — dispatch-оверхед и аллокации
>    на уровне или ниже до-рефакторного рекорда, рейтинг клиентов в тех же
>    группах. BENCHMARKS.md дополнен секцией «Revision check (run #287)».
> 3. *Ждёт Антона:* слово по P1–P4 ADR-0067 (P1/P2 готовы к немедленному
>    исполнению) и по №7 (вариант A/B rss/atom — вопрос висит с #284/#285).
> Гейт не запускался (код не менялся): 1,636/1,636 в силе. Next free ADR **0068**.


> **Сессия #285 (Кандидат №6 — исследование лаконичности публичного API; ADR-0067
> оформлен, Status: Proposed; код НЕ менялся):**
> исследование без кода (первый пункт беклога Антона; последний гейт 1,636/1,636
> от #283 в силе).
> 1. *Сверка (по коду jRestly + известным декларациям Retrofit 2.11 / OpenFeign
>    13.5 / Spring 6 HTTP Interfaces):* три реальные точки многословности, все —
>    в декларациях контроллеров: (V1) `path = ` в глаголах (`@Get(path = "/users/${id}")`
>    при неявном `value` у всех конкурентов); (V2) `@PathVariable(name = "id")` в
>    README/фикстурах при уже работающем fallback на имя Java-параметра
>    (ADR-0017, fail-fast #212) — пробел не в коде, а в примерах; (V3) `${id}`
>    против отраслевого стандарта `{id}` (Retrofit/Feign/Spring/RFC 6570).
>    Периферия: длинная `requestType = RequestType.APPLICATION_FORM_URLENCODED`,
>    вложенный `@RequestDefaultParam` для статических query-дефолтов.
> 2. *ADR-0067* (`docs/0.11.0/`): P1 — `value()` как атрибут глаголов
>    (рекомендация: да, механический rename); P2 — безымянные биндинги в
>    примерах/фикстурах (рекомендация: да, бесплатно, код не трогается);
>    P3 — `{id}` вместо `${id}` (решение Антона: стандарт vs самобытность);
>    P4 — проверить/задокументировать статические query прямо в пути
>    (`@Get("/items?size=10")` vs `@RequestDefaultParam`; микропроверка); P5 —
>    form/multipart оставить как есть (рекомендация: не трогать — анти-комбайн,
>    один механизм). Non-goals: `@RequestLine`-стиль, Response-оболочки, билдер.
> 3. *Ожидание:* слово Антона по P1–P4 (P1/P2 могли бы пойти сразу в следующей
>    сессии); №7 (rss/atom) всё ещё заблокирован на его ответ о варианте A/B.
> 4. *Гейт не запускался* (doc-only): 1,636/1,636 в силе. Next free ADR **0068**.

> **Сессия #284 (гигиена: дистилляция закрытого эпика ADR-0066 в `ROADMAP.md` —
> паттерн #206; посессионный лог «### Cycle 0.11.0» заменён полноценной секцией
> «Internal Code Structure: HttpTemplate Decomposition (ADR-0066) — COMPLETE ✅»;
> Current status обновлён; Status ADR-0066 закрыт окончательно; вопрос Антону
> по объёму кандидата №7 отправлен):**
> рабочий квант без кода (последний гейт 1,636/1,636 от #283 в силе, код не менялся).
> 1. *ROADMAP:* секция по формату ADR-0058/0059/0060 — problem (аудит #270 +
>    god-класс 2,825 строк / 132 missed), solution (package-info ×15 + шесть
>    шагов/коллабораторов с находками: `responseCodecOrNull` #274, TDD №38
>    `findFirst`-NPE #277, ленивый кодек 5c #281, mutable-setter-урок #281),
>    patterns (урок #271 ×6; JaCoCo-грабля private-ctor; static vs value-object
>    по mutate-профилю полей), gates (1,636/1,636; CLASS 149/149, METHOD
>    1444/1444, INSTRUCTION 97.91%, LINE 97.62%, BRANCH 89.63%, COMPLEXITY
>    87.74%). `HttpTemplate` 132 → 82 missed. Current status: гейт и метрики
>    обновлены с устаревших 1,598 / 97.69% / 89.11%.
> 2. *DRAFT:* блок сессии; активная зона — дистилляция отмечена выполненной,
>    следующий ход — Кандидат №6 (исследование лаконичности API, без кода).
> 3. *Mailbox:* Антону — итог дистилляции + конкретный вопрос по №7 (объём
>    «движка rss/atom»: parse-only vs fetch-and-parse; в ядре vs codec-
>    субмодуль) — ответ разблокирует №7; сообщение #281 удалено (гигиена).
> 4. Гейт не запускался (док-изменения только).

> **Сессия #283 (ADR-0066, шаг №6 — последний: уведомления метрик вынесены из
> `HttpTemplate` в package-private `MetricsNotifier`; шов ADR-0036 #193
> переехал в свой сейт; **план ADR-0066 закрыт полностью**; гейт 1,636/1,636,
> CLASS 149/149 и METHOD 1444/1444 — 100%, INSTRUCTION 97.91%, LINE 97.62%,
> BRANCH 89.63%, COMPLEXITY 87.74% — рекорды; `HttpTemplate` 95 → **82**
> missed):**
> 1. *Извлечено (байт-в-байт):* четыре точки диспетчеризации
>    (`notifyCallStart`/`notifyAttemptComplete`/`notifyRetry`/
>    `notifyCallComplete` — 17 call-site'ов sync/async контуров) →
>    `MetricsNotifier.callStart`/`attemptComplete`/`retry`/`callComplete`
>    + байт-учётный шов ADR-0036 Amendment #193 (`computeRequestBytes` →
>    `requestBytes`, `computeResponseBytes` → `responseBytes`).
> 2. *Коллаборатор — чистый `static`-хелпер* (паттерн CacheDispatch/ResponseBody):
>    `(metricsListener, logger)` передаются аргументами на каждом call-site —
>    mutable-сеттеры `setMetricsListener`/`setLogger` не создают «устаревшего
>    производного кэша» (урок #281 в минус — вариант с ленивым value-объектом
>    отсечён сознательно).
> 3. *Грабля JaCoCo:* встроенный фильтр режет только **private** пустые
>    no-arg-конструкторы; пакетно-приватный `<init>` живёт как missed метод —
>    у прецедентов (`CacheDispatch`/`ResponseBody`/`RetryAfter`/
>    `ProblemDetailResolver`) `private` → METHOD 1444/1444 (100%) сохранены.
> 4. *TDD:* compile-red (2 ошибки компиляции) → зелёный; сьют
>    `MetricsNotifierTest` (22 безсетевых теста) — матрица ветвей всех четырёх
>    точек (no-listener / null-context / pass-through / listener-сбой ± логгер /
>    SimpleCallMetrics-контракт). Шов байт-учёта переименован:
>    `HttpTemplateMetricsBytesTest` → `MetricsNotifierBytesTest` (6 → 8:
>    +не-CL заголовок = false-сторона `equalsIgnoreCase`, +отрицательный
>    `Content-Length` = false-сторона `val >= 0`).
> 5. *Остаток:* 1 missed ветвь `responseBytes` (`headers() == null`) —
>    structurally недостижимая defensive (record нормализует headers);
>    guard оставлен осознанно (паттерн #268 InMemoryHttpCache).
> 6. *Гейты:* **1,636 / 1,636** green across all 5 modules (+24), javadoc чист
>    под `-Werror`. JaCoCo: CLASS **149/149**, METHOD **1444/1444 (100%)**,
>    INSTRUCTION 30,799/31,455 (**97.91%** — рекорд), LINE 6,696/6,856
>    (**97.62%** — рекорд), BRANCH 3,977/4,437 (**89.63%** — рекорд),
>    COMPLEXITY 3,242/3,695 (**87.74%** — рекорд). `HttpTemplate` 95 → 82.
> 7. *Дальше:* дистилляция закрытого эпика ADR-0066 в `ROADMAP.md`; затем
>    беклог Антона №6–№8 (оба условия «после завершения» выполнены; по №7
>    уточнить объём бенчмарка до старта).

> **Сессия #281 (ADR-0066, под-шаг 5c: буферное декодирование вынесено из
> `processResponse` в `ResponseBody` (`bufferedOptional`/`bufferedRaw` — две
> зеркальные ноги; кодек передаётся лениво через `Supplier<PayloadCodec>`;
> `contentTypeOf` переехал из `HttpTemplate`; шёлк переехал целиком; 13 новых
> тестов в `ResponseBodyTest`; гейт 1,612/1,612, CLASS 148/148 и METHOD
> 1444/1444 — 100%, INSTRUCTION 97.89%, LINE 97.57%, BRANCH 89.41%,
> COMPLEXITY 87.50% — рекорды; `ResponseBody` 118/118 ветвей — 0 missed,
> `HttpTemplate` 108 → 95 missed):**
> шаг №5 плана ADR-0066 закрыт целиком (5a примитивы + 5b стриминг + 5c
> буферное декодирование).
> 1. *Извлечено (байт-в-байт):* две ноги буферного декодирования — optional
>    (ADR-0023: missing-resource/пустое тело → `Optional.empty()`;
>    byte[]/String/Void — без кодека; DTO → `Optional.ofNullable(decode)`) и
>    raw (пустое тело → `null`; те же raw-биндинги; DTO → голый possibly-null
>    возврат) → `ResponseBody.bufferedOptional`/`bufferedRaw`. Логи ног
>    сохранены дословно («No response or empty/status for Optional return.» vs
>    «No response.», String-нога с размером+пейлоадом), prettyPrint-превью —
>    как и раньше UTF-8, а не payloadCharset.
> 2. *Критический шов — ленивый кодек:* шаблон передаёт
>    `() -> responseCodec(responseHeaders)` — на raw-ногах кодек по-прежнему
>    **не резолвится вовсе** (bare-template выживает, урок #274/#277), а на
>    DTO-ноге резолвится дважды по числу использований (превью в логе +
>    deserialize), ровно как раньше. Error-пути (`handleOnError`/`isExpectedStatus`
>    /`createUnexpectedStatusException`) осторожно оставили на шаблоне
>    (прецедент #274 «dispatch, не резолвинг»); `probePayloadDecode` не тронут
>    (свой сьют зелёный).
> 3. *Швы переехали:* `contentTypeOf` удалён из `HttpTemplate`, все 5 осиротевших
>    call-site'ов (`responseCodec`, probe ×2, `handleOnError` ×2) перенаправлены
>    на `ResponseBody.contentTypeOf`. Первая же честная матрица уточнила
>    контракт: `contentTypeOf` возвращает значение **первого** по имени
>    `Content-Type` — даже `null`-значного (байт-в-байт поведение, задокументировано
>    тестом).
> 4. *TDD-гейт по уроку #271:* юнит-матрица `ResponseBodyTest` 20 → **33** тестов —
>    обе ноги × {byte[], String, Void/void, DTO, empty/null, missing-resource}
>    + лог-матрицы (verbose/quiet) + guard «кодек не резолвится на raw-биндингах»
>    (throwing-supplier) + guard «prettyPrint получает payloadCharset, превью —
>    UTF-8» (строки `"Ã©"`/`"é"` как дискриминатор). Первый прогон зафиксировал
>    2 missed ветви нового `contentTypeOf` — добраны до **118/118, 0 missed**
>    (урок #271 четвёртый раз подряд).
> 5. *Гейты:* **1,612 / 1,612** green across all 5 modules (+14), javadoc чист
>    под `-Werror`. JaCoCo: **CLASS 148/148** и **METHOD 1444/1444 (100%)**,
>    **INSTRUCTION 30,759/31,423 (97.89%)** — рекорд, **LINE 6,692/6,859
>    (97.57%)** — рекорд, **BRANCH 3,967/4,437 (89.41%)** и **COMPLEXITY
>    3,233/3,695 (87.50%)** — рекорды. `HttpTemplate`: 108 → **95** missed.
>    Публичный API не изменён.
> 6. *Дальше* — шаг №6 (уведомления метрик `notify*` — последний пункт плана
>    ADR-0066) или хвосты покрытия.

> **Сессия #279 (приём и фиксация WIP #277 — шаг №5b закоммичен; полный гейт
> перепроверен по XML-отчётам: 1,598/1,598, BRANCH 89.11%, COMPLEXITY 87.16%,
> `ResponseBody` 68/68 ветвей, `HttpTemplate` 108 missed):**
> #277 обрывалась до коммита (паттерн #155/#186/#254 — четвёртый раз за эпик);
> в дереве лежал полный комплект шага №5b (код + 12 тестов + память). Принят:
> диффы прочитаны байт-в-байт, осиротевших ссылок нет (grep), гейт зелёный,
> метрики совпали с заявленными один-в-один. Единственное поведенческое
> отличие при переносе — `closeQuietly` принимает `logger` параметром вместо
> чтения поля (null-защита коллаборатора). Дальше — под-шаг 5c или №6.

> **Сессия #277 (ADR-0066, шаг №5b: стриминговые артефакты line/SSE вынесены из
> `processResponse` в `ResponseBody` (`lineStream`/`eventStream`/`wrapForTrailers`/
> `isMissingResource`/`streamingPayloadLine`/`closeQuietly`); новый сьют
> `ResponseBodyTest` вырос 8 → 20 тестов, у коллаборатора **68/68 ветвей — 0
> missed**; TDD-находка №38 — NPE в лог-строке при `Content-Length` без значения;
> гейт 1,598/1,598, CLASS 148/148, METHOD 1440/1440 — 100%, BRANCH 89.11% —
> новый рекорд):**
> продолжение плана ADR-0066 — под-шаг 5b (стриминговые артефакты +
> `optional`-обёртки; ветка буферного декодирования остаётся на 5c).
> 1. *Извлечено (байт-в-байт):* дублирующиеся конструкции line-stream
>    (`BufferedReader(...).lines().onClose(...)` × 2 — line и SSE-ветки)
>    → `ResponseBody.lineStream(stream, charset, logger)`; SSE-илифовка →
>    `ResponseBody.eventStream(...)` (parse поверх lineStream, close
>    проксируется до провода); трейлер-хук ADR-0059 → `wrapForTrailers`,
>    матрица из 4 комбинаций поставщика держателей; правило missing-resource
>    (404/304/204) названо `ResponseBody.isMissingResource` и применяется в
>    трёх стриминговых ветках, в буферной `optional`-ветке и в
>    `probePayloadDecode` (единый источник правды). `closeQuietly` и
>    `streamingPayloadLine` переехали целиком (публичный API не изменён).
> 2. *TDD-находка №38:* юнит-матрица `streamingPayloadLine` поймала
>    `Stream.findFirst()` → `Optional.of(null)` → **NPE** на ответе с
>    `Content-Length`-заголовком без значения: заголовок проходил фильтр
>    по имени, нулевое значение попадало в стрим map'а, и
>    `findFirst()` (в отличие от `Optional.map`) не проглатывает null.
>    Крэш добирался до caller'а — `processResponse` падал NPE при включённом
>    INFO-логе вместо лога «(length unknown)». Фикс: третье условие
>    `&& h.value() != null` в фильтре; `findFirst`/`orElse` остались.
> 3. *Швы переехали, а не продублированы:* новый сьют на 12 новых кейсов
>    (матрица `lineStream` — charset-декод + close провода + close-failure
>    логируется, матрица `eventStream` — parse + проксируемый close +
>    charset, `wrapForTrailers` — pass-through ×3, attach ×3, заполнение
>    двух держателей на EOF, null-сиблинг, `closeQuietly` null/throw,
>    `streamingPayloadLine` ×5). `HttpTemplate` 122...115 → **108** missed.
> 4. *Гейты:* **1,598 / 1,598** green across all 5 modules (+12), javadoc чист
>    под `-Werror`. JaCoCo: **CLASS 148/148** и **METHOD 1440/1440 (100%)**,
>    **INSTRUCTION 30,693/31,420 (97.69%)** — рекорд, **LINE 6,678/6,856
>    (97.40%)** — рекорд, **BRANCH 3,954/4,437 (89.11%)** и **COMPLEXITY
>    3,217/3,691 (87.16%)** — новые рекорды. `ResponseBody`: 68/68 ветвей
>    (0 missed) — урок #271 подтверждён третьим коллаборатором подряд.
> 5. *Дальше* — под-шаг 5c (ветка буферного декодирования: DTO/byte[]/String/
>    Void-legs + кодек-селекция) или шаг №6 (уведомления метрик `notify*`).

> **Сессия #276 (ADR-0066, шаг №5a: примитивы декодирования тела ответа вынесены
> из `HttpTemplate` в package-private `ResponseBody`; швы переехали в свой
> безсетевой сьют (`ResponseBodyTest`, 8 тестов); гейт 1,586/1,586, CLASS 148/148
> и METHOD 1437/1437 — 100%, BRANCH 88.98% и COMPLEXITY 86.98% — новые рекорды):**
> начало шага №5 плана ADR-0066 (декодирование тела) — выбран **безопасный
> под-шаг 5a** (чистые `static`-примитивы), т.к. весь `processResponse` — самый
> связанный с состоянием участок (читает `onErrorParser`, `isExpectedStatus`,
> `handleOnError`, `returnType`, `optional`, `logger`, `responseCodec`).
> 1. *Извлечено (байт-в-байт):* `resolveCharset` → `ResponseBody.resolveCharset`
>    (+ private `parseCharsetFromContentType`), `isStreamType` →
>    `ResponseBody.isStreamType`, `consumePayload` → `ResponseBody.consumePayload`,
>    `extractResponseBodyString` → `ResponseBody.extractText`.
> 2. *Коллаборатор — чистый `static`-хелпер* (как `RetryAfter`/`ProblemDetailResolver`/
>    `CacheDispatch`): поля шаблона не читает — все входы аргументы. Вызов
>    `isStreamType` из `MethodMetadata` перенаправлен на `ResponseBody`.
> 3. *Швы переехали, а не продублированы:* charset-матрица ушла из
>    `HttpTemplateLineAndEventStreamTest` в `ResponseBodyTest`; добавлены прямые
>    матрицы `isStreamType` (не-`ParameterizedType`, чужой raw-тип, неверная
>    арность), `consumePayload` (декодирование + закрытие стрима + пустой → `null`),
>    `extractText` (`null`-ответ/тело → `""`, honor charset) и тест искажённых
>    charset-параметров (оборванная/одиночная кавычка, параметр без `=`).
>    `ResponseBody` сразу даёт **0 missed ветвей** (40/40).
> 4. *Гейты:* **1,586 / 1,586** green across all 5 modules, javadoc чист под
>    `-Werror`. JaCoCo: **CLASS 148/148** и **METHOD 1437/1437 (100%)**,
>    **INSTRUCTION 30,703/31,436 (97.67%)**, **LINE 6,677/6,857 (97.37%)**,
>    **BRANCH 3,957/4,447 (88.98%)** и **COMPLEXITY 3,212/3,693 (86.98%)** — новые
>    рекорды. `HttpTemplate`: 122 → **115** missed. Публичный API не изменён.
> 5. *Дальше* — под-шаг 5b (стриминговые артефакты line/SSE + `optional`-обёртки
>    в `processResponse`) или хвосты покрытия.

> **Сессия #275 (ADR-0066, шаг №4: dispatch кэша вынесен из `HttpTemplate` в
> package-private `CacheDispatch`; швы переехали в свой безсетевой сьют
> (`HttpTemplateCacheDispatchTest` → `CacheDispatchTest`, 12 тестов); гейт
> 1,579/1,579, CLASS 147/147 и METHOD 1437/1437 — 100%, BRANCH 88.82% и
> COMPLEXITY 86.79% — новые рекорды):**
> продолжение плана ADR-0066 (шаг №4 — dispatch кэша).
> 1. *Извлечено:* `handleCacheResponse` → `CacheDispatch.handle(req, resp,
>    cache, streamBody)`, `toStreamWireResponse` →
>    `CacheDispatch.toStreamWireResponse`; логика байт-в-байт, оба контура
>    (sync-ветка `exchange` + async-ветка `thenApply` над `authGuardedFuture`)
>    поведение не изменили.
> 2. *Коллаборатор — чистый `static`-хелпер* (как `RetryAfter`/
>    `ProblemDetailResolver`): состояние шаблона не читает — store
>    (`HttpCache`), `streamBody`-флаг, запрос и ответ передаются аргументами.
>    Заодно снимает вопрос мутабельного поля `httpCache` (`setHttpCache`):
>    коллаборатор читает переданный store, а не поле.
> 3. *Швы переехали, а не продублированы:* тест переименован в
>    `CacheDispatchTest` (12 тестов, включая новый прямой тест
>    `toStreamWireResponse`); тонких делегатов на `HttpTemplate` не осталось.
>    Урок #271 подтверждён: извлечённому коллаборатору нужен свой сьют —
>    `CacheDispatch` сразу даёт 0 missed ветвей.
> 4. *Гейты:* **1,579 / 1,579** green across all 5 modules (+1), javadoc чист
>    под `-Werror`. JaCoCo: **METHOD 1437/1437** и **CLASS 147/147 (100%)**,
>    **INSTRUCTION 30,703/31,436 (97.67%)**, **LINE 6,677/6,857 (97.37%)**,
>    **BRANCH 3,950/4,447 (88.82%)**, **COMPLEXITY 3,205/3,693 (86.79%)**.
>    Публичный API не изменён.
> 5. *Дальше* — шаг №5 ADR-0066 (декодирование тела: charset/streaming/
>    `consumePayload`) или хвосты покрытия.

> **Сессия #274 (ADR-0066, шаг №3: resolver Problem Details вынесен из
> `HttpTemplate` в package-private `ProblemDetailResolver`; завершение WIP
> прерванной #272–#273 + находка «вынос из `try` меняет контракт»; гейт 1,578/1,578,
> METHOD 1437/1437 и CLASS 146/146 — 100%, INSTRUCTION 97.67%, LINE 97.38%,
> BRANCH 88.78%, COMPLEXITY 86.73%):**
> продолжение плана ADR-0066 (шаг №3 — resolver Problem Details).
> 1. *Вход:* WIP прерванных сессий #272–#273 (паттерн #155/#186/#254/#261): в дереве
>    уже лежал новый `ProblemDetailResolver.java` + правки `HttpTemplate`/теста,
>    но коммита не было. Принят, верифицирован, завершён.
> 2. *Извлечено:* `resolveProblemDetail` → `ProblemDetailResolver.resolve`,
>    плюс `isProblemJsonContentType`, `isLikelyProblemDetail`,
>    `instantiateThrowable` (`private static` → package-private `static`),
>    байт-в-байт.
> 3. *Коллаборатор — чистый `static`-хелпер* (в отличие от `Redirects`-value-
>    объекта #271): не завязан на поля шаблона — выбранный кодек и его media-тип
>    передаются аргументами, конструктор не нужен. `handleOnError` остаётся на
>    `HttpTemplate` (dispatch, не резолвинг).
> 4. *Находка (завершение WIP):* перенос вытащил `responseCodec(responseHeaders)`
>    **из охранного `try`** наружу → на bare-template (без
>    `codecRegistry`/`jsonCodec`) `createUnexpectedStatusException` стал падать
>    голым `NullPointerException: jsonCodec` вместо тихого пролёта к встроенному
>    парсеру. Поймали e2e-гварды `HttpTemplateOptionalStreamTest` /
>    `HttpTemplateStreamWireTest` (4 падения). Фикс — private
>    `responseCodecOrNull` (best-effort lookup, `catch (Throwable) → null`),
>    поведение восстановлено точно.
> 5. *TDD:* `HttpTemplateProblemDetailResolutionTest` (30 тестов) — предикаты и
>    резолвер напрямую на `ProblemDetailResolver`, `handleOnError` по-прежнему
>    через `HttpTemplate`; новый тест «null codec → built-in parser» закрывает
>    обе стороны `if (responseCodec != null)`. Оставшиеся 5 missed ветвей
>    резолвера — известные недостижимые defensive (`instantiateThrowable`).
> 6. *Гейты:* **1,578 / 1,578** green across all 5 modules (+1), javadoc чист под
>    `-Werror`. JaCoCo: **METHOD 1437/1437** и **CLASS 146/146 (100%)**,
>    **INSTRUCTION 30,702/31,435 (97.67%)**, **LINE 6,677/6,857 (97.38%)**,
>    **BRANCH 3,948/4,447 (88.78%)**, **COMPLEXITY 3,203/3,693 (86.73%)**.
>    `HttpTemplate` 128 → **122** missed. Публичный API не изменён.
> 7. *Дальше* — шаг №4 ADR-0066 (dispatch кэша) или хвосты покрытия.

> **Сессия #271 (ADR-0066, шаг №2: политика редиректов вынесена из
> `HttpTemplate` в package-private коллаборатор `Redirects`; новый безсетевой
> `RedirectsTest` (8 тестов) даёт коллаборатору 100% ветвей; гейт 1,577/1,577,
> BRANCH 88.80%, INSTRUCTION 97.67%, COMPLEXITY 86.76% — новые рекорды):**
> продолжение плана ADR-0066 (шаг №2 — политика редиректов).
> 1. *Извлечено:* `nextHop` / `dropAuthHeaders` / `logExhaustedRedirects` /
>    `redirectLocation` (→ `Redirects.location`) / `isCrossOrigin` /
>    `effectivePort` — логика байт-в-байт, оба контура (sync
>    `executeWithRedirects`, async `executeAsyncWithRedirects`) не изменили
>    поведение.
> 2. *Коллаборатор — value-объект конфигурации,* а не статическая утилита:
>    три неизменяемых настройки (`followRedirectsNumber`, `authHeaderNames`,
>    `logger`) в конструкторе; `HttpTemplate` строит его **лениво** и только
>    при заданном `@FollowRedirects` — zero-redirect hot-path не аллоцирует
>    (ADR-0055). Async-рекурсия вынесена в `executeAsyncHop`, чтобы
>    `null`-бюджетный fast-path не смешивался с хоп-логикой.
> 3. *Швы переехали, а не продублированы:* `isCrossOrigin`/`effectivePort`
>    теперь на `Redirects`; тест `HttpTemplateCrossOriginTest` →
>    `RedirectsCrossOriginTest` (ссылки обновлены); dead-code делегатов нет.
> 4. *TDD:* новый `RedirectsTest` (8 тестов, без сети) — stop-paths (нет
>    бюджета, не-редирект, нет `Location`), GET-downgrade vs. preserve 307/308,
>    cross-origin drop ambient-auth + pass-through при `null`-именах,
>    `Location`-lookup с `null`-именованным заголовком, все комбинации
>    `logExhausted`.
> 5. *Гейты:* **1,577 / 1,577** green across all 5 modules (+8, 0 failures/errors),
>    javadoc чист под `-Werror`. JaCoCo: **BRANCH 3,949/4,447 (88.80%)**,
>    **INSTRUCTION 30,696/31,429 (97.67%)**, **LINE 6,673/6,853 (97.37%)**,
>    **COMPLEXITY 3,203/3,692 (86.76%)**, METHOD 1436/1436 и CLASS 145/145 (100%).
>    `HttpTemplate` 129 → **128** missed; `Redirects` — 0 missed по ветвям.
> 6. *Дальше* — шаг №3 ADR-0066 (resolver Problem Details) или хвосты покрытия.

> **Сессия #270 (структурный аудит по вопросу Антона + ADR-0066 + первый шаг
> декомпозиции `HttpTemplate`: `RetryAfter` вынесен в package-private
> коллаборатор; `package-info.java` для всех 15 пакетов; гейт 1,569/1,569,
> публичный API не изменён, `HttpTemplate` 132 → 129 missed):**
> прямой заказ Антона об организации внутренней структуры кода (mailbox,
> 2026-10-18). Аудит: таксономия пакетов осмысленна, SPI-границы честные,
> направление зависимостей предсказуемо; долги — god-класс `HttpTemplate`
> (2825 строк / 48 полей / 63 метода), осознанное кольцо `ru.jrestly` ⇄
> `ru.jrestly.http`, отсутствие пакетной документации.
> 1. *ADR-0066* (`docs/0.11.0/ADR-0066-internal-code-structure-and-pipeline-decomposition.md`):
>    план инкрементальной декомпозиции `HttpTemplate` по одной когезивной
>    ответственности на сессию (RetryAfter → политика редиректов → Problem
>    Details resolver → dispatch кэша → декодирование тела → метрики),
>    package-private коллабораторы, публичный API и швы не трогаются.
> 2. *Шаг №1 — `RetryAfter`:* из `HttpTemplate` перенесены три формата HTTP-date
>    RFC 9110 + набор дней недели и 4 private-static метода в новый
>    package-private `ru.jrestly.http.RetryAfter` (`delayMillis(WireResponse)`);
>    логика байт-в-байт, 4 осиротевших импорта удалены, тест переименован
>    (`HttpTemplateRetryAfterTest` → `RetryAfterTest`, 11 тестов).
> 3. *Пакетная документация:* `package-info.java` во все 15 пакетов
>    `ru.jrestly.*` — ответственность, ключевые типы, ссылки на ADR; javadoc-гейт
>    (`-Xdoclint:all -Werror`) зелёный.
> 4. *Гейты:* **1,569 / 1,569** green across all 5 modules, 0 failures/errors,
>    javadoc чист под `-Werror`. JaCoCo: BRANCH 3,944/4,443 (88.77%),
>    INSTRUCTION 30,648/31,381 (97.66%), LINE 6,659/6,839 (97.37%),
>    COMPLEXITY 3,197/3,687 (86.71%), METHOD 1433/1433 и CLASS 144/144 (100%).
> 5. *Дальше* — шаг №2 ADR-0066 (политика редиректов) или хвосты покрытия.

> **Сессия #269 (free-аудит кластера Problem Details ADR-0064 в `HttpTemplate` —
> `handleOnError` / `resolveProblemDetail` / `isProblemJsonContentType` /
> `isLikelyProblemDetail` / `instantiateThrowable`: полный срез достижимых ветвей
> через четыре package-private seam'а; закрыто 30 ветвей — `HttpTemplate`
> 162 → 132 missed (82.69% → 85.90%); 29 безсетевых тестов; гейт 1,569/1,569,
> INSTRUCTION 97.66%, LINE 97.37%, BRANCH 88.77%, COMPLEXITY 86.71% — четыре
> новых исторических рекорда):**
> свободный ход по хвосту `HttpTemplate` (162 missed branches — класс №1 по
> непокрытым ветвям, при этом именно здесь прошлые сессии находили «тихие» баги
> #256–#259); сужение на кластер RFC 9457 Problem Details (ADR-0064) — тот же
> участок, где #257 нашёл BOM-баг. Кластер приватный, поэтому четыре метода
> получили осознанные package-private швы (паттерн #255/#266/#267).
> 1. *Аудит (точная карта по XML-отчёту JaCoCo):*
>    - `resolveProblemDetail`: `rawBody` null/blank, shape-гейт `{…}` (обе стороны),
>      путь кодека (accept по `isProblemType`/эвристике, заполнение status),
>      fallback на встроенный `fromJson` (при сбое кодека и при его `null`);
>    - `isProblemJsonContentType`: null/пустой список, отсутствующий/null-значный
>      `Content-Type`, `problem+json`/`problem+xml` (регистронезависимо), непроблемный тип;
>    - `isLikelyProblemDetail`: null, дефолтный документ (`about:blank`/0/null),
>      status>0, кастомный type, не-blank title/detail, instance;
>    - `handleOnError`: `null`/пустое/blank тело, `Void`-цель, decoded
>      RuntimeException/Error/checked-Throwable/ProblemDetail, бестелесная
>      инстанциация `(Object,int,List)`/`(String,int)`/`(String)`/no-arg,
>      ProblemDetailException-обёртка (decoded и `forStatus`), fallback
>      ProblemDetail + заполнение/сохранение status.
> 2. *TDD и гейт:* новый безсетевой сьют `HttpTemplateProblemDetailResolutionTest`
>    (29 тестов: 16 — три предиката/резолвер, 13 — `handleOnError`, включая
>    `TestError`/`TestCheckedException` под пробу конструкторов). Быстрые
>    unit-проверки, без сети.
>    - Всего тестов: **1,569 / 1,569** green across all 5 modules (+29, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,944 / 4,443 (88.77%)** — новый исторический рекорд (+31 за счёт среза; одна ветвь — джиттер-зависимая в `RetryPolicy`, вне среза)!
>      * **INSTRUCTION:** **30,647 / 31,380 (97.66%)** — новый исторический рекорд!
>      * **LINE:** **6,659 / 6,839 (97.37%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **3,196 / 3,686 (86.71%)** — новый исторический рекорд!
>    - `HttpTemplate`: 162 → **132** missed (85.90%). Оставшиеся ветви кластера —
>      defensive (`instanceof Error`/checked в `instantiateThrowable`,
>      `ProblemDetailException.isAssignableFrom` — недостижимы, т.к. L2689 всегда
>      заворачивает `isProblemException`), плюс `createUnexpectedStatusException`
>      non-4xx/5xx и хвост `handleOnError`.
>    - ADR не тронут — чистый coverage-срез по контракту (паттерн #192/#197/#198/#261/#268).

> **Сессия #268 (free-аудит кэша RFC 9111 — `InMemoryHttpCache` / `SimpleCachedResponse` / `CacheControl`: полный срез достижимых ветвей кластера кэша; закрыто 18 ветвей — `InMemoryHttpCache` 17 → 4, `SimpleCachedResponse` 3 → 0, `CacheControl` 2 → 0; оставшиеся 4 — структурно недостижимые defensive-проверки `WireResponse`; гейт 1,540/1,540, INSTRUCTION 97.49%, LINE 97.19%, BRANCH 88.07%, COMPLEXITY 85.95% — новые рекорды):**
> свободный ход по классам ядра с наибольшим хвостом непокрытых ветвей; сужение на
> кластер прозрачного HTTP-кэша ADR-0063 (`InMemoryHttpCache` 17 missed, `SimpleCachedResponse`
> 3, `CacheControl` 2 — 22 ветви на входе).
> 1. *Аудит (точная карта по XML-отчёту JaCoCo):*
>    - `InMemoryHttpCache.put`: обе стороны статус-гейта `!= 200/301/404` (301 и 404 не
>      кэшировались ни одним тестом), `date == null` при расчёте `Expires`-freshness,
>      ветвь «только `Last-Modified`» (все четыре селектора `maxAge/expires/etag/lastModified`),
>      обе стороны `resCc.noCache() || reqCc.noCache()`, `null`-response и request с `null`-URI;
>    - `InMemoryHttpCache.update`: ветвь «304 без max-age и без Expires» (`expires == null`
>      после `maxAge == null`) — сохраняет прежний freshness; `Expires`-без-`Date`;
>      `null`-response304 и request с `null`-URI;
>    - `parseHttpDate`: blank-значение заголовка (`"   "` → `null`);
>    - `SimpleCachedResponse`: `isFresh` на `noCache`-записи (никогда не fresh) и
>      `mustRevalidate() || noCache` (обе ноги);
>    - `CacheControl.stripQuotes`: непарная `"`, одиночная `"`, пустая `""`.
> 2. *TDD и гейт:*
>    - 12 тестов: `InMemoryHttpCacheTest` (+11 — статусы 301/404, `Expires`-без-`Date`,
>      lone `Last-Modified`, response/request `no-cache`, `null`-response/`null`-URI для
>      put и update, update без freshness, update `Expires`-без-`Date`, blank http-date,
>      флаги `noCache`/`mustRevalidate`) и `CacheControlTest` (+1 — непарные/одиночные/пустые
>      кавычки). Быстрые unit-тесты, без сети.
>    - Всего тестов: **1,540 / 1,540** green across all 5 modules (+12, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,913 / 4,443 (88.07%)** — новый исторический рекорд (+17 за счёт среза; одна ветвь — джиттер-зависимая в `RetryPolicy`, вне среза)!
>      * **INSTRUCTION:** **30,593 / 31,380 (97.49%)** — новый исторический рекорд!
>      * **LINE:** **6,647 / 6,839 (97.19%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **3,168 / 3,686 (85.95%)** — новый исторический рекорд!
>    - Кластер кэша: `InMemoryHttpCache` 17 → **4** missed (оставшиеся — `response304.version() != null`,
>      `findHeader(headers == null)`, `mergeHeaders(newHeaders != null)` и `mergeHeaders(oldHeaders != null)`:
>      все четыре недостижимы через публичный API, ибо record `WireResponse` нормализует
>      headers/version к non-null; defensive-guard'ы оставлены осознанно); `SimpleCachedResponse`
>      3 → **0**; `CacheControl` 2 → **0**.
>    - ADR не тронут — чистый coverage-срез по контракту (паттерн #192/#197/#198/#261).

> **Сессия #267 (free-аудит `HttpTemplate` — завершение WIP прерванной #266: package-private seams `hasHeader`/`isCrossOrigin`/`effectivePort` обрели unit-матрицу; закрыто 14 ветвей в трёх методах — missed branches `hasHeader` 5 → 0, `isCrossOrigin` 6 → 0, `effectivePort` 3 → 0; `HttpTemplate` 176 → 162 missed; гейт 1,528/1,528, INSTRUCTION 97.46%, LINE 97.18%, BRANCH 87.69%, COMPLEXITY 85.49%):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (176 missed по данным #265);
> #266 оборвалась до коммита (паттерн #155/#186/#254): в дереве — только package-private
> seam'ы `hasHeader`, `isCrossOrigin`, `effectivePort` (+ комментарии о намеренности),
> но без тестов. Приняты, верифицированы по коду, покрыты.
> 1. *Аудит (матрица ветвей по HTML-отчёту JaCoCo):*
>    - `hasHeader(req, name)` (L569/L573; 5 missed из 12): `req == null`,
>      `req.headers() == null`, элемент `null` в списке заголовков и `Header` с
>      `null`-именем — все defensive-ветви не вызывались (в проде `effectiveRequest`
>      всегда валиден, а `List.of` запрещает null-элементы → e2e до них не доходит);
>      плюс ветвь «не найдено» (`equalsIgnoreCase == false`);
>    - `isCrossOrigin(from, to)` (L1755/L1756/L1759/L1760; 6 missed из 14):
>      `from.getScheme() == null` / `to.getScheme() == null` (относительный URI),
>      `from.getHost() == null` / `to.getHost() == null` (opaque URI вида `foo:/opaque`),
>      сравнение схем и хостов без учёта регистра (обе стороны `equalsIgnoreCase`);
>    - `effectivePort(uri)` (L1767/L1770; 3 missed из 4): явный порт, дефолт `https`
>      → 443 (в т.ч. схема `HTTPS` — регистронезависимо), дефолт прочих схем → 80.
> 2. *TDD и гейт:*
>    - Два новых безсетевых сьюта: `HttpTemplateCrossOriginTest` (10 тестов —
>      матрица `isCrossOrigin` + `effectivePort`) и `HttpTemplateConditionalHeaderTest`
>      (7 тестов — матрица `hasHeader`, включая null-элемент и `Header(null, v)` —
>      `List.copyOf`/`List.of` такие не принимают, поэтому список собирается `ArrayList`).
>      До seam-семейства #266 тесты не компилировались бы (методы были `private`),
>      на seam'ах — зелёные с первого прогона.
>    - Всего тестов: **1,528 / 1,528** green across all 5 modules (+17 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - `hasHeader`: missed 5 → **0**; `isCrossOrigin`: 6 → **0**; `effectivePort`: 3 → **0**;
>      `HttpTemplate`: 176 → **162** missed (82.69%). Суммарный BRANCH-прирост +15 (14 — этот
>      срез, одна ветвь — джиттер-зависимая в `RetryPolicy`, вне среза).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,896 / 4,443 (87.69%)** — новый исторический рекорд!
>      * **INSTRUCTION:** **30,582 / 31,380 (97.46%)** — новый исторический рекорд!
>      * **LINE:** **6,646 / 6,839 (97.18%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **3,151 / 3,686 (85.49%)** — новый исторический рекорд!
>    - ADR не тронут — чистый coverage-срез по контракту.

> **Сессия #265 (free-аудит `ResponseHeaders` и `ProblemDetail$ZeroDepJsonParser`: масштабный coverage-срез заголовков ответа и встроенного zero-dep JSON парсера; закрыто 19 ветвей `ResponseHeaders` — missed branches 34 → 15, ветви 81.3% → 91.8%; закрыто 5 ветвей `ZeroDepJsonParser` — missed branches 29 → 24, ветви 79.0% → 82.6%; впервые в истории библиотеки преодолён рубеж 87% Branch Coverage — 87.35%; впервые пробит рубеж 85% Complexity — 85.11%; гейт 1,511/1,511 тестов, INSTRUCTION 97.40%, LINE 97.09%):**
> свободный ход по классам ядра с наибольшим хвостом непокрытых ветвей;
> сужение на `ResponseHeaders` (34 missed branches на входе, 81.3% coverage) и `ProblemDetail$ZeroDepJsonParser` (29 missed branches, 79.0% coverage):
> 1. *Аудит (кластеры `ResponseHeaders`):*
>    - Конструкторы и `doPopulate`: null-параметры `duration`, `headers`, `version`, `trailers` безопасно замещаются дефолтами (`Duration.ZERO`, `List.of()`, `HttpClient.Version.HTTP_1_1`);
>    - Очистка заголовков и трейлеров: дедупликация и фильтрация элементов с `null`-именами и замена `null`-значений на пустую строку `""` (`h.value() != null ? h.value() : ""`);
>    - `populateTrailers`: безопасная обработка `null` и пустых списков (no-op guard), обработка трейлеров с `null`-именами и `null`-значениями;
>    - `declaredTrailers` и `firstTrailer`: заголовок `Trailer` с `null`-значением и пустыми запятыми (`" , X-Hash , , "`), безопасная обработка `null` имени в `firstTrailer` и `trailerValues`;
>    - `parseCookies`: поддержка ответов только с `Set-Cookie2` (без `Set-Cookie`), обработка `null`/blank значений заголовков, перехват `IllegalArgumentException` при невалидных cookie (`"=malformed"`), fail-fast валидация `Objects.requireNonNull(name)` в `cookie(name)` и `cookieValue(name)`;
>    - `cookieMap`: куки с пустыми значениями (`"empty_val="` -> `""`);
>    - `capture(Supplier)`: вложенный вызов с корректным восстановлением предыдущего `ThreadLocal` captor;
>    - RFC 8288 / 5988 парсер `Link`: угловые скобки `<`/`>` внутри кавычек, двойные кавычки `"` внутри URI, замыкающая запятая в заголовке, непарные кавычки (`rel="unclosed`, `rel=unopened"`, `rel="`), пустые/blank кавычки `rel=""` и `rel="   "`;
>    - Защита `firstValue`, `allValues`, `firstLongValue`: fail-fast `NullPointerException` при `name == null`.
> 2. *Аудит (кластеры `ProblemDetail$ZeroDepJsonParser`):*
>    - Пустые структуры: объект `{}` и `{   }`, массив `[]`;
>    - Полная матрица escape-последовательностей: `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\u0041` (unicode hex), и default fallback `\q`;
>    - Значения `true` и числа типа `Long`, превышающие `Integer.MAX_VALUE` (5 млрд);
>    - Экспоненты чисел с заглавной `E`, знаками `+` и `-` (`2.5E2`, `1e+3`, `1e-2`);
>    - Неполные (`\u00`) и невалидные (`\uXXXX`) unicode escape, незакрытые строки на backslash (`abc\`), синтаксические ошибки массивов (`[1 2]`), ключи без кавычек, неожиданные токены после значений, числа с несколькими точками (`1.2.3`).
> 3. *TDD и исторические гейты JaCoCo:*
>    - Добавлено 9 тестов (+8 в `ResponseHeadersTest`, +1 в `ProblemDetailTest`, быстрые unit-проверки, без сети): **1,511 / 1,511** green across all 5 modules (+9 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - `ResponseHeaders`: missed branches 34 → **15** (покрытие ветвей выросло с 81.3% до **91.80%**, закрыто 19 ветвей)!
>    - `ProblemDetail$ZeroDepJsonParser`: missed branches 29 → **24** (покрытие ветвей выросло с 79.0% до **82.60%**, закрыто 5 ветвей)!
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,881 / 4,443 (87.35%)** — новый исторический рекорд (+24 покрытые ветви за сессию, впервые преодолён рубеж 87%)!
>      * **INSTRUCTION:** **30,564 / 31,380 (97.40%)** — новый исторический рекорд!
>      * **LINE:** **6,640 / 6,839 (97.09%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **3,137 / 3,686 (85.11%)** — новый рекорд (впервые преодолён рубеж 85%)!
>    - ADR не тронут — чистый coverage-срез по контракту.
> 
> **Сессия #264 (free-аудит `ControllerValidator`: исчерпывающий coverage-срез валидатора интерфейсов контроллеров; закрыто 40 ветвей класса `ControllerValidator` — missed branches 50 → 10, ветви 90.0% → 98.00%; преодолен исторический рубеж 1,500 тестов — 1,502/1502; впервые пробит рубеж 97% Line Coverage — 97.00%; Branch Coverage достиг 86.81%, Instruction 97.34%):**
> свободный ход по классам ядра с наибольшим хвостом непокрытых ветвей;
> сужение на `ControllerValidator` (50 missed branches на входе, 90.0% coverage — третий по рангу класс по missed branches):
> 1. *Аудит (кластеры `ControllerValidator`):*
>    - Null и не-интерфейсные классы: `validate(null)` и `validate(String.class)` безопасно возвращают пустой список проблем;
>    - Алмазное наследование и статические методы интерфейса: дедупликация обхода `visited.add(current)` при повторном посещении базового интерфейса в ромбе и фильтрация статических методов `Modifier.isStatic`;
>    - Чистый LF (`\n` без `\r`) в аннотациях CRLF-защиты: `@Accept`, `@ContentType`, `@Cookie` на уровне класса и метода, а также параметр `@Cookie`;
>    - `@OnError`: статусы `> 599` (например, 600) и дедупликация дублирующихся статусов с идентичным `errorObject` (не вызывает ложных конфликтов);
>    - `@Delete`: корректное извлечение `params` из `@Delete(params = ...)` и валидация blank-имен параметров через `resolveVerbDefaultParams`;
>    - Комбинации параметров: обнаружение несовместимых сочетаний аннотаций (`@RequestBody` + `@MultipartFormFile`, `@Cookie` с другими HTTP-аннотациями, `@TargetUrl` со всеми параметрами запроса, `ResponseHeaders` + `@TargetUrl`, `UploadProgressListener` и `DownloadProgressListener` со всеми типами запросных аннотаций);
>    - Абстрактные классы в `@WithInterceptor` и `@WithMetrics`: `Modifier.isAbstract` для классов, не являющихся интерфейсами;
>    - Package-private seams: открытие швов валидатора (`collectClassProblems`, `collectMethods`, `validateMethod`, `validateInterceptorClass`, `validateMetricsClass`, `validateRetry`, `validateExpectStatus`, `validatePath`, `validatePathVariableMismatch`) позволило верифицировать поведение при null-аргументах, `Double.NaN` в `Retry.multiplier()`, и статусных кодах > 599.
> 2. *TDD и исторические гейты JaCoCo:*
>    - Добавлено 8 тестов в `ControllerValidationTest` (быстрые unit-проверки, без сети): **1,502 / 1,502** green across all 5 modules (+8 тестов, впервые преодолен рубеж 1500 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - `ControllerValidator`: missed branches 50 → **10** (покрытие ветвей выросло с 90.0% до **98.00%**, закрыто 40 ветвей)!
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,857 / 4,443 (86.81%)** — новый исторический рекорд (+39 покрытых ветвей, вплотную к 87%)!
>      * **INSTRUCTION:** **30,546 / 31,380 (97.34%)** — новый исторический рекорд!
>      * **LINE:** **6,634 / 6,839 (97.00%)** — новый исторический рекорд (впервые пробит рубеж 97%)!
>      * **COMPLEXITY:** **3,113 / 3,686 (84.45%)** — новый исторический рекорд!
>    - ADR не тронут — чистый coverage-срез по контракту.
> 
> **Сессия #263 (free-аудит `HttpTemplateBuilder`: исчерпывающий coverage-срез сборщика шаблонов HTTP-запросов; пробит рубеж 85% Branch Coverage — 85.93%, закрыто 44 ветви класса `HttpTemplateBuilder`, 19 новых тестов, гейт 1494/1494, INSTRUCTION 97.23% и LINE 96.83% — новые исторические рекорды библиотеки):**
> свободный ход по классам ядра с наибольшим хвостом непокрытых ветвей;
> сужение на `HttpTemplateBuilder` (164 missed branches на входе, 74.8% coverage — второй класс в библиотеке по missed branches):
> аудит выявил кластеры непокрытых веток сборщика запросов:
> 1. *Аудит (кластеры `HttpTemplateBuilder`):*
>    - URL & `@TargetUrl`: все 4 перестановки слэшей в `joinUrlPath` (base/path), резолюция `@TargetUrl` при наличии query (`?`), fragment (`#`), обоих (`?` и `#`), и без них, пустой путь метода, абсолютные пути методов (`http://`/`https://`), fail-fast валидация null-значений (`@TargetUrl` и `@PathVariable`), кодирование путей (`pathVariableEncoded = true` vs URL encoding);
>    - Заголовки & авторизация: слияние статических `@RequestHeader` и ambient `AuthProvider`, приоритет явных заголовков над ambient, расчёт drop-list для кросс-оригинных редиректов (ADR-0002), поддержка `Map<String, Object>`, коллекций и массивов заголовков с фильтрацией null-значений, декомпрессия `Accept-Encoding: gzip, deflate` с сохранением заголовка вызывающего;
>    - Куки: извлечение статических и динамических кук, удаление по null-значению, слияние через `Map`, поддержка `HttpCookie`, слияние с существующим заголовком `Cookie` через `; `;
>    - Трассировка: `@Trace` W3C (`traceparent`) и кастомные заголовки, поведение `forceNew = true` vs `false`;
>    - Условные запросы: обнаружение `If-None-Match`, `If-Match`, `If-Modified-Since`, `If-Unmodified-Since`;
>    - Параметры запроса: форматирование коллекций и массивов (`CSV`, `SSV`, `TSV`, `PIPES`, `BRACKETS`, `EXPLODE`), слияние с `staticDefaultParams`, динамические карты (`prefix[key]` и плоские);
>    - Тело запроса & стриминг: `createJsonBodyPayload` для `byte[]`, `String`, `InputStream`, `HttpRequest.BodyPublisher`, `Supplier<?>` (полная матрица типов: null, stream, BodyPublisher, Flow.Publisher, Path, File, byte[], String, unsupported type, missing files), адаптация `Flow.Publisher<?>` к `ByteBuffer` через `adaptToByteBufferPublisher` (передача типов, onComplete, onError), валидация отсутствия кодека при кастомных DTO (`IllegalStateException`);
>    - Multipart: валидация типа запроса, поддержка `FileContent`, `File`, `Path`, `String`, массивов и коллекций файлов, fail-fast валидация bare `byte[]`, неподдерживаемых типов и путей без имени файла, параметры в multipart во всех форматах коллекций;
>    - Urlencoded: кодирование параметров, обработка пустых и null-значений, валидация методов без параметров;
>    - Сжатие & Resilience: сжатие тела выше порога, обработка ошибок компрессора (`IOException`), резолюция `@CircuitBreaker` и `@RateLimiter` с кастомными реестрами, перехват исключений инстанцирования интерцепторов;
>    - Опции билдера: кастомный логгер, конструкторы с `HttpClient == null` и `HttpEngine`.
> 2. *TDD и гейты:*
>    - Создан исчерпывающий сьют `HttpTemplateBuilderTest` (19 тестов, без сети, быстрые unit-проверки): 1,494/1494 green across all 5 modules (+19 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - `HttpTemplateBuilder`: missed branches 164 → **120** (покрытие ветвей выросло с 74.8% до **81.60%**, закрыто 44 ветви)!
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1432 / 1432 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,818 / 4,443 (85.93%)** — новый исторический рекорд (+47 покрытых ветвей, впервые перейдён рубеж 85%)!
>      * **INSTRUCTION:** **30,511 / 31,380 (97.23%)** — новый исторический рекорд (впервые преодолён рубеж 97%)!
>      * **LINE:** **6,622 / 6,839 (96.83%)** — новый исторический рекорд (+0.84%)!
>      * **COMPLEXITY:** **3,074 / 3,686 (83.40%)** — новый исторический рекорд!
>    - ADR не тронут — чистый coverage-срез по контракту.
> 
> **Сессия #262 (free-аудит `MethodMetadata`: исчерпывающий coverage-срез контракта декларативного парсинга контроллеров; пробит рубеж 84% Branch Coverage — 84.88%, закрыто 42 ветви класса `MethodMetadata`, 14 новых тестов, гейт 1475/1475, INSTRUCTION 96.37% и LINE 95.99% — новые рекорды):**
> свободный ход по классам ядра с наибольшим хвостом непокрытых ветвей;
> сужение на `MethodMetadata` (69 missed branches на входе, 80.4% coverage):
> аудит выявил кластеры непокрытых веток контрактов:
> 1. *Аудит (кластеры `MethodMetadata`):*
>    - `@WithMetrics`: наследование из иерархии интерфейсов (`BaseMetricsApi -> SubMetricsApi -> method`), дедупликация (`seen.add`), и фильтрация не-`ClientMetricsListener` классов;
>    - `@WithInterceptor`: внутримассивная сортировка по `@Order(1) < @Order(2)`, разделение на `RequestInterceptor` и `ResponseInterceptor`, поддержка дуальных интерцепторов (`implements RequestInterceptor, ResponseInterceptor`), дедупликация;
>    - `@QueryCollectionFormat`: приоритеты разрешения: метод (`CSV`), `DEFAULT` fallback на интерфейс (`SSV`), и глобальный дефолт (`EXPLODE`);
>    - `@OnError`: регистрация статусных и default-обработчиков с уровня интерфейса и метода;
>    - `@Compress`: наследование с интерфейса, кастомные параметры (`DEFLATE`, `minSizeBytes = 1024`), и явное отключение (`enabled = false`) на методе и интерфейсе;
>    - `@CircuitBreaker` и `@RateLimiter`: переопределение методом и наследование с интерфейса;
>    - `@HttpVersion` и `@FollowRedirects`: разрешение версий и количества хопов;
>    - `@Retry`: парсинг `maxAttempts` и опционального `maxRetryAfterMs` (`>= 0` и `-1`);
>    - `@Timeout`: fail-fast валидация `IllegalArgumentException` при неположительных значениях на методе и интерфейсе;
>    - `isStaticConditional`: распознавание всех условных заголовков (`If-Match`, `If-Unmodified-Since`, `If-Modified-Since`, `If-None-Match`);
>    - `staticCookies` и `staticDefaultParams`: фильтрация пустых имен кук (`isBlank()`) и слияние `params` из HTTP-глаголов (`@Get(params = ...)`) со статическими аннотациями;
>    - `ReturnTypeInfo`: сырой `Optional` (`effectiveReturnType == Object.class`), стриминг `Stream<ServerSentEvent>` и `Stream<String>`;
>    - `ParameterDescriptor`: полное извлечение всех дескрипторов параметров (`@TargetUrl`, `@RequestBody`, `@RequestParam` с кастомным форматом, `@PathVariable(encoded = true)`, `@Cookie`, `@RequestHeader`, `@MultipartFormFile`, `ResponseHeaders`, `UploadProgressListener`, `DownloadProgressListener`, `@SetAuthDetails`, `@Authorization`);
>    - `MethodMetadata.of(null, Interface.class)` и `MethodMetadata.of(null, null)`: поддержка интерфейсных метаданных без метода.
> 2. *TDD и гейты:*
>    - Добавлено 14 тестов в `MethodMetadataTest` (без сети, быстрые unit-тесты): 1,475/1475 green across all 5 modules (+14 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - `MethodMetadata`: missed branches 69 → **27** (покрытие ветвей выросло с 80.4% до **92.33%**, +42 ветви)!
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютные 100% сохранены!
>      * **BRANCH:** **3,771 / 4,443 (84.88%)** — новый исторический рекорд (+46 покрытых ветвей, впервые перейдён рубеж 84% и почти достигнут 85%)!
>      * **INSTRUCTION:** **30,200 / 31,338 (96.37%)** — новый исторический рекорд (+0.26%)!
>      * **LINE:** **6,551 / 6,825 (95.99%)** — новый исторический рекорд (вплотную к 96%)!
>      * **COMPLEXITY:** **3,021 / 3,672 (82.27%)** — новый исторический рекорд!
>    - ADR не тронут — код без изменений (чистый coverage-срез по контракту, паттерн #192/#197/#198/#261).

> **Сессия #261 (free-аудит `HttpTemplate`, `probePayloadDecode` × Optional-возврат (ADR-0011 Aspect 2): чистый срез — optional-ветвь decode-probe была 100% непокрыта, логика верифицирована как точное зеркало optional-ветви `processResponse`; 7 guard-тестов, код не тронут; гейт 1461/1461, BRANCH 83.84% и INSTRUCTION 96.11% и LINE 95.82% — новые рекорды):**
> свободный ход по хвосту `HttpTemplate` (193 missed branches на входе),
> сужение на `probePayloadDecode` (1269–1303): ветвь `if (optional)`
> (1282–1290) — 16 missed branches при 0 покрытых. Probe вызывается только
> из retry-цикла (`!last && isRetryDecodeFailures()`, строки 1189/1472),
> и ни один тест не сочетал Optional-возврат с decode-retry, а
> `HttpTemplateDecodeProbeTest` (из ADR-0011) ходил только по
> non-optional-пути.
> 1. *Аудит (diff против `processResponse` 935–965):* ветви идентичны —
>    404/304/204/пустое тело → «decode не может упасть» (в `processResponse`
>    это `Optional.empty()`); String/byte[]/Void/void → сырой возврат/отброс
>    (codec не трогается); DTO-нога → `responseCodec(...).deserialize` —
>    само исключение codec'а. `payload == null` в probe семантически то же
>    `bytes == null || bytes.length == 0`. При `optional=true` и без явного
>    `expectStatuses` `isExpectedStatus` (1904–1918) пропускает 2xx/304/404,
>    так что probe доходит до optional-ветви, а не до unexpected-status
>    short-circuit. Логика верна — чистый срез (паттерн #192/#197/#198).
> 2. *Guard-тесты (7, `HttpTemplateDecodeProbeTest`, без WireMock —
>    package-private seam + `setOptional`/`setReturnType` + StubCodec):*
>    404/304/204 с телом и пустое тело → true при 0 вызовах codec'а
>    (каждый дизъюнкт первой конъюнкции первым истинным); String/byte[]/
>    Void/void → true при 0 вызовах (каждый дизъюнкт второй конъюнкции);
>    декодируемый DTO-пейлоад → true, codec ровно один раз;
>    небиндящийся → исключение codec'а, тот же инстанс, unwrapped.
>    JUnit-5 per-method lifecycle: свежий template/codec на тест,
>    `setOptional(true)` не течёт между тестами.
> 3. *Ситуация на входе:* #260 оборвалась до коммита (паттерн #155/#186) —
>    в дереве только счётчик 259→261, HEAD 7bfc1eb (#259); принят как есть.
> 4. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1461 / 1461** green across all 5 modules (+7, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% сохранено!
>      * **BRANCH:** **3,725 / 4,443 (83.84%)** — новый исторический рекорд (+16, ровно целевая ветвь)!
>      * **INSTRUCTION:** **30,119 / 31,338 (96.11%)** — новый исторический рекорд!
>      * **LINE:** **6,540 / 6,825 (95.82%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **2,979 / 3,672 (81.13%)**.
>    - `HttpTemplate`: missed branches 193 → **177** (ветви 81.09%).
>    - ADR не тронут — код без изменений (чистый coverage-срез, паттерн #192/#197/#198).

> **Сессия #259 (free-аудит `HttpTemplate`, хвост `exchange()`/`exchangeAsync()`, ADR-0036 метрики × pre-wire rejection (rate-limiter timeout / open circuit breaker): 37-я «тихая» находка — `attempts()` сообщал 1 при нулевых `onAttemptComplete`-колбэках на pre-wire rejection; гейт 1454/1454, BRANCH 83.48% и INSTRUCTION 95.95% и LINE 95.74% — новые рекорды):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (195 missed по
> данным #258, покрытие ветвей 79.12%), сужение на pre-wire rejection-путь
> `exchange()`/`exchangeAsync()`: timeout rate-limiter и open circuit breaker —
> оба бросают исключение **до** первой передачи на провод.
> 1. *Находка (37-я в серии):* ADR-0036 Amendment #195/#258 починили
>    short-circuit- и interceptor-failure-пути, но оставшийся pre-wire путь —
>    когда `rateLimiter.acquire()` (timeout) или `circuitBreaker.
>    acquirePermission()` (open) бросают до провода — всё ещё нарушал
>    инвариант «`attempts()` == количество `onAttemptComplete`-колбэков»:
>    sync-путь падал в общий `catch` с `attemptsHolder`, который инициализируется
>    `1` (строка 255) и перезаписывается только внутри `executeWithRetries` —
>    а та до rejection-исключения не доходит, так что `notifyCallComplete`
>    получал `1`; async-ветки rejection передавали в `notifyCallComplete`
>    литерал `1`. Слушатель видел `attempts() == 1` при **0** вызовах
>    `onAttemptComplete` — вызов физически не касался провода, но метрики
>    сообщали «одна передача».
> 2. *TDD red-first:* 4 красных (2 реjections × sync/async) —
>    `RequestPipelineTest.rateLimiterRejectionPairedMetricsLifecycle`,
>    `circuitBreakerOpenRejectionPairedMetricsLifecycle` и async-близнецы
>    `asyncTwinRateLimiterRejectionPairedMetricsLifecycle` /
>    `asyncTwinCircuitBreakerOpenRejectionPairedMetricsLifecycle`: фейки
>    `RejectionRateLimiter` / `OpenCircuitBreaker` (синглтон-классы —
>    `Enum.name()` final, enum не подошёл) + `RecordingMetrics` с переопределённым
>    `onAttemptComplete` (запись «attempt»). До фикса:
>    `expected: <[0]> but was: <[1]>`.
> 3. *Fix:* sync — `rateLimiter.acquire()` + `circuitBreaker.acquirePermission()`
>    в try/catch: перед перебросом `attemptsHolder[0] = 0`; async — обе ветки
>    rejection передают `0` вместо `1` в `notifyCallComplete(...)`.
>    Запись circuit breaker не тронута (уже исключает исключение по типу).
>    Javadoc `CallMetrics.attempts()` переформулирован: всегда равен числу
>    `onAttemptComplete`-колбэков; `0` — short-circuit (ADR-0047), упавший
>    request-интерцептор или pre-wire rejection rate-limiter/CB. ADR-0036
>    дополнен `## Amendment #259`.
> 4. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1454 / 1454** green across all 5 modules (+4 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% сохранено!
>      * **BRANCH:** **3,709 / 4,443 (83.48%)** — новый исторический рекорд!
>      * **INSTRUCTION:** **30,070 / 31,338 (95.95%)** — новый исторический рекорд!
>      * **LINE:** **6,534 / 6,825 (95.74%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **2,971 / 3,672 (80.91%)**.
>    - `HttpTemplate`: missed branches 195 → **193** (ветви 79.38%).

> **Сессия #258 (free-аудит `HttpTemplate`, хвост `exchange()`/`exchangeAsync()`, ADR-0036 метрики × pre-wire сбой request-интерцептора: 36-я «тихая» находка — жизненный цикл метрик ломался на пути сбоя request-интерцептора до провода; гейт 1450/1450, BRANCH 83.47% и INSTRUCTION 95.90% и LINE 95.70% — новые рекорды):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (196 missed по данным #257,
> покрытие ветвей 79.12%), сужение на tail `exchange()`/`exchangeAsync()` — путь
> pre-wire сбоя request-интерцептора в едином диспетчере `<T> T exchange()`.
> 1. *Находка (36-я в серии):* ADR-0036 Amendment #195 починил short-circuit-путь,
>    но сестринский pre-wire путь — когда `RequestInterceptor` **бросает** исключение
>    до того, как запрос положен на провод, — всё ещё ломал SPI внутри одного
>    логического вызова. В `catch` interceptor-failure срабатывало
>    `notifyCallComplete` с `attempts = 1`, а `notifyCallStart` — объявленное
>    «pairs 1:1 with `onCallComplete` (in-flight gauges stay balanced)» — находилось
>    ниже, за тем местом, где `catch` уже перебрасывает (sync) или возвращает
>    failed future (async). Слушатель видел `onCallComplete` **без** сопряжённого
>    `onCallStart` (in-flight gauge ушёл в минус), а `attempts()` сообщал **1**
>    для вызова, который физически не касался провода (`onAttemptComplete` — 0
>    вызовов) — то же самопротиворечие, что #195 устранил для short-circuit,
>    но на ветку сбоку.
> 2. *TDD red-first:* 2 красных (sync + async) —
>    `RequestPipelineTest.requestInterceptorFailurePairedMetricsLifecycle` и
>    `asyncTwinRequestInterceptorFailurePairedMetricsLifecycle`: request-интерцептор,
>    бросающий исключение, + записывающий `ClientMetricsListener` (`RecordingMetrics`);
>    ассерты `onCallStart` x1 **до** `onCallComplete` x1 и `attempts() == 0`.
>    До фикса: `expected: <[start, complete]> but was: <[complete]>`.
> 3. *Fix:* в interceptor-failure `catch` — `notifyCallStart` сразу до
>    `notifyCallComplete` и честный счётчик передач `0` (до движка ничего не
>    дошло). Нормальный (небросающий) путь не тронут: `notifyCallStart` по-прежнему
>    срабатывает ровно один раз на исходном месте. Javadoc `onCallStart` уточнён
>    (срабатывает и когда интерцептор **падает**, не только short-circuit).
>    ADR-0036 дополнен `## Amendment #258`.
> 4. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1450 / 1450** green across all 5 modules (+2 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% сохранено!
>      * **BRANCH:** **3,707 / 4,441 (83.47%)** — новый исторический рекорд!
>      * **INSTRUCTION:** **30,044 / 31,328 (95.90%)** — новый исторический рекорд!
>      * **LINE:** **6,527 / 6,820 (95.70%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **2,970 / 3,671 (80.90%)**.
>    - `HttpTemplate`: missed branches 196 → **195** (ветви 79.12%).

> **Сессия #257 (free-аудит `HttpTemplate`, кластер problem-detail/`@OnError`: 35-я «тихая» находка — BOM-префикс Problem Detail тела молча ронял структурированную ошибку; гейт 1448/1448, BRANCH 83.45% и INSTRUCTION 95.88% и LINE 95.69% — три новых рекорда):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (201 missed), сужение
> на кластер problem-detail: `probePayloadDecode` (20), `handleOnError` (15),
> `isLikelyProblemDetail` (11), `resolveProblemDetail` (10),
> `isProblemJsonContentType` (5).
> 1. *Находка (35-я в серии):* RFC 8259 §8.1 разрешает опциональный BOM-префикс
>    JSON-текста, но `String.trim()` не режет U+FEFF (не whitespace). Тело
>    `application/problem+json` с BOM умирало в двух местах: pre-check
>    `resolveProblemDetail` (тело отбрасывалось до любого codec'а → unexpected-
>    status исключения без `ProblemDetail`) и `ProblemDetail.fromJson` (та же
>    shape-проверка); оба call-site в `catch (Throwable ignored)` → провал тихий.
>    На zero-dep classpath (NoOp codec) `@OnError`-маппинг падал на голый
>    `ProblemDetail.forStatus(statusCode)` — title/detail/type/instance/
>    properties молча терялись.
> 2. *TDD red-first:* 4 красных + 2 guard — юнит
>    `ProblemDetailTest.fromJsonToleratesLeadingByteOrderMark` (BOM, BOM+пробелы,
>    BOM-only → IAE, двойной BOM → IAE: «ровно один» по формулировке RFC);
>    `ProblemDetailWireMockTest` (unexpected status — красный, `@OnError` —
>    guard на Jackson-classpath); `ZeroDepStandaloneWireMockTest` ×2 (NoOp codec —
>    fromJson-fallback единственный путь, обе ноги красные).
> 3. *Fix:* `ProblemDetail.fromJson` — после `trim()` снять один лидирующий
>    U+FEFF + пере-`trim()`; `HttpTemplate.resolveProblemDetail` — идентичная
>    нормализация до pre-check. `handleOnError` не тронут (fromJson-fallback
>    покрыт, codec-путь BOM терпел всегда). ADR-0064 дополнен `## Amendment #257`.
> 4. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1448 / 1448** green across all 5 modules (+5 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% сохранено!
>      * **BRANCH:** **3,706 / 4,441 (83.45%)** — новый исторический рекорд!
>      * **INSTRUCTION:** **30,032 / 31,324 (95.88%)** — новый исторический рекорд!
>      * **LINE:** **6,525 / 6,819 (95.69%)** — новый исторический рекорд!
>      * **COMPLEXITY:** **2,969 / 3,671 (80.88%)**.

> **Сессия #256 (free-аудит `HttpTemplate`: пересечение Optional × стриминг, ADR-0023 × ADR-0015/0016 — реальный разрыв, найден и закрыт; гейт 1443/1443, BRANCH 83.34% и INSTRUCTION 95.82% — новые рекорды):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (класс №1 в JaCoCo по missed
> branches — 202, покрытие 77.95%).
> 1. *Наблюдение:* ADR-0023 (Optional) и ADR-0015/0016 (стриминг) в коде и в тестах
>    никогда не пересекались: стриминговые ветки `processResponse` возвращали сырые
>    line/event-стримы **до** блока `if (optional)`, а тот покрывал только
>    `Optional<InputStream>`-ногу. Итог: `Optional<Stream<String>>` /
>    `Optional<Stream<ServerSentEvent>>` на успехе возвращали сырой `Stream` (→
>    `ClassCastException` на типизированном присваивании у вызывающего), а на
>    404/304 — сырой поток тела вместо `Optional.empty()`.
> 2. *TDD red-first:* новый сьют `HttpTemplateOptionalStreamTest` (8 тестов, без
>    WireMock — stub `HttpClient` по образцу `HttpTemplateStreamWireTest`): 5 красных
>    (ноги бага — обёртка на 200 sync/async + события, 404/304 → `Optional.empty()`
>    при закрытом стриме) + 3 зелёных (работающая `Optional<InputStream>`-нога +
>    guard-тесты неизменного non-Optional поведения).
> 3. *Fix:* в обеих стриминговых ветках `processResponse` при `optional == true`
>    артефакт обёрнут `Optional.of(...)` на успехе; на 404/304/204 стрим закрывается
>    (close проксируется: event-стрим → line-стрим → `closeQuietly(effectiveStream)`)
>    и возвращается `Optional.empty()`. ADR-0023 дополнен `## Amendment #256`.
> 4. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1443 / 1443** green across all 5 modules (+8 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**; **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% сохранено!
>      * **BRANCH:** **3,698 / 4,437 (83.34%)** — новый исторический рекорд (+20 покрытых ветвей)!
>      * **INSTRUCTION:** **29,996 / 31,304 (95.82%)** — новый исторический рекорд!
>      * **LINE:** **6,516 / 6,815 (95.61%)** — новый исторический рекорд.
>      * **COMPLEXITY:** **2,966 / 3,669 (80.84%)**.

> **Сессия #255 (free-аудит `HttpTemplate` / завершение WIP прерванной #254: package-private seam `handleCacheResponse` + unit-матрица ADR-0063 cache-dispatch (11 тестов) и детерминированная zero-backoff ветвь async retry; гейт 1435/1435, Branch Coverage впервые пересёк 83% — 83.19%):**
> свободный ход по хвосту непокрытых ветвей `HttpTemplate` (216 missed по данным #253).
> #254 оборвалась до коммита (паттерн #155/#186): в дереве лежал WIP — main-код лишь
> лишился `private` на `handleCacheResponse` (+ нормативный комментарий о намеренном
> package-private seam), всё остальное — тесты. Принят, верифицирован и зафиксирован.
> 1. *Наблюдение:* post-wire диспетчер RFC 9111 из ADR-0063 (`handleCacheResponse`:
>    304-revalidation → `update`, fresh 200/301/404 GET → `put`, unsafe 200..399 →
>    `invalidate`, стриминговый streamify синтезированного 200) покрывался только
>    сквозными WireMock-тестами — unit-матрицы не было. Ветвь `delayMillis <= 0` в
>    async retry (прямая `attemptAsync`-рекурсия вместо schedule) покрывалась только
>    «лотерейным» FULL-jitter нулём (1/11) на дефолтной 10-миллисекундной политике —
>    покрытие ветви зависело от соли JVM.
> 2. *Fix/покрытие:* `handleCacheResponse` переведён на package-private (seam
>    намеренный — unit-тесты ходят по матрице без сети); новый сьют
>    `HttpTemplateCacheDispatchTest` (11 тестов, `RecordingCache` — записывающий
>    фейк `HttpCache`): pass-through при `httpCache == null`/null-аргументах
>    (zero-garbage fast path), 304 → синтезированный 200, 304 при вытесненном entry
>    (`update` → null, raw 304 насквозь), 304 на стриминговом возвращении (re-wrap в
>    живой stream / null-body без streamify), `put` для 200/301/404, no-op для
>    204/302/500, invalidation для POST/PUT/PATCH/DELETE на 200..399, no-op за
>    пределами (199/400/500). Плюс
>    `HttpTemplateStreamWireTest.asyncRetryZeroBackoffTakesTheDirectRecursion`:
>    `initialBackoff(Duration.ZERO)` детерминирует ветвь прямой рекурсии
>    (порядок `send-1 → close-failed-attempt → send-2`).
> 3. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1435 / 1435** green across all 5 modules (+12 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% покрытие ВСЕХ методов библиотеки (0 missed methods)!
>      * **BRANCH:** **3,678 / 4,421 (83.19%)** — впервые за серией пересечён 83-й рубеж!
>      * **INSTRUCTION:** **29,944 / 31,266 (95.77%)** — новый исторический рекорд!
>      * **LINE:** **6,504 / 6,805 (95.58%)**.
>      * **COMPLEXITY:** **2,958 / 3,661 (80.80%)**.

> **Сессия #253 (free-аудит / харденинг: устранение тихого сброса явного `@RequestHeader` на кросс-оригинных редиректах — 34-я «тихая» находка серии, новые рекорды JaCoCo: 95.74% Instruction / 82.95% Branch / 95.58% Line Coverage):**
> свободный ход развития качества и надежности кодовой базы.
> 1. *Находка (34-я в серии):* `HttpTemplateBuilder` собирал список `authHeaderNames` (заголовки, удаляемые на кросс-оригинных редиректах) из «сырого» списка `AuthProvider.getAuthHeaders()`. Из-за этого явный `@RequestHeader` контроллера, затеняющий ambient auth-заголовок **по имени** (реалистичный кейс: клиент с `bearerAuth(...)` + per-call `@RequestHeader("Authorization")` override — merged-запрос уже несёт явное значение, см. `createHeaders`), молча удалялся на кросс-оригинном хопe, и редирект улетал **без `Authorization` вообще**, нарушая обещание ADR-0002: «явные значения `@RequestHeader` переживают редирект».
> 2. *Fix:* в `HttpTemplateBuilder.build()` drop-list теперь вычисляется как ambient auth-имена **минус** явные `@RequestHeader`-имена контроллера (статические `methodMetadata.staticHeaders()` + динамические параметры `p.isRequestHeader()`, сравнение без учёта регистра). Оба пути редиректа (sync и async) проходят через `HttpTemplate.nextHop()`, поэтому покрываются одним исправлением. Остаточный краевой кейс задокументирован как non-goal в поправке: Map-типизированный динамический `@RequestHeader`, runtime-ключ которого в рантайме совпадает с ambient auth-именем, всё ещё удаляется (имя известно только в момент вызова).
> 3. *TDD и исторические гейты JaCoCo:*
>    - Red-first тест: `CrossOriginRedirectTest.crossOriginRedirectPreservesExplicitAuthOverride` (hop 1 несёт явный токен; hop 2, кросс-оригинный, теперь несёт его тоже) + fixture `RedirectMethodController.getWithExplicitAuthOverride`; до фикса — красный (WireMock: `expected Authorization: Bearer explicit-token but was: (empty)` на hop 2).
>    - Всего тестов: **1423 / 1423** green across all 5 modules (+1 тест, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% покрытие ВСЕХ методов библиотеки (0 missed methods)!
>      * **BRANCH:** **3,667 / 4,421 (82.95%)** — новый исторический рекорд!
>      * **INSTRUCTION:** **29,935 / 31,266 (95.74%)** — новый исторический рекорд (+0.05% за сессию)!
>      * **LINE:** **6,504 / 6,805 (95.58%)** — новый исторический рекорд (+0.04% за сессию)!
>      * **COMPLEXITY:** **2,949 / 3,661 (80.55%)**.
>    - ADR-0002 дополнен `## Amendment #253`.
>
> **Сессия #251 (free-аудит / харденинг: закрытие краевых условий в 8 подсистемах и пробитие рубежа 82.94% Branch Coverage при 95.69% Instruction Coverage):**
> свободный ход развития качества и надежности кодовой базы.
> 1. *Харденинг и верификация краевых веток периферии:*
>    - `CallMetrics`: верифицировано извлечение `statusCode()` из вложенного `HandledException` (`t.getCause()`), обработка пустых ответов/сбоев (`statusCode == -1`, `isSuccess == false`), и не-2xx ответов без исключения (`isSuccess == false`);
>    - `ApiKeyAuth`: верифицирован обход методов с `@Authorization`, безопасная работа при `context == null` или `context.method() == null`, корректное добавление query-параметра при наличии И существующего query, И фрагмента URI (`https://example.com/api?tab=all#details`), а также динамические поставщики ключей (`Supplier<String>`);
>    - `UnexpectedStatusException`: протестированы все варианты форматирования `getMessage()` при различных комбинациях null/empty `title` и `detail`, а также безопасный возврат `""` в `getRawBody()` при `rawBody == null`;
>    - `ProblemDetailException`: протестирован 3-аргументный конструктор при `details == null`, `details instanceof ProblemDetail`, и произвольных объектах, а также все варианты сообщения `getMessage()`;
>    - `ProblemDetail`: проверена очистка свойств в `setProperties(null)`, фильтрация null/blank имен в `setProperty`, сериализация всех комбинаций полей в `toJson()`, парсинг нечисловых статусов в `fromJson()`, а также полный контракт `equals()` и `hashCode()`;
>    - `ResponseHeaders`: протестированы краевые случаи парсинга заголовков `Link` (запятые внутри кавычек, множественные токены `rel`, неэкранированный `rel`, пустые/битые заголовки), а также возврат `Optional.empty()` в `locationUri()` при невалидных URI со спецсимволами/пробелами;
>    - `CacheControl`: протестированы null-элементы/имена/значения в списке заголовков, пустые токены в `Cache-Control`, кавычки вокруг значений, отрицательные/нечисловые директивы `max-age` и `stale-while-revalidate`, а также возврат `EMPTY` при отсутствии директив;
>    - `TraceContext`: протестирован парсинг некорректных нешестнадцатеричных `traceId` и `spanId`, а также краевые биты флагов в `isSampled()`;
>    - `ReconnectPolicy.Builder`: протестирована полная fail-fast валидация параметров (`maxAttempts < 0`, отрицательный `initialBackoff`, `initialBackoff > maxBackoff`, `backoffMultiplier < 1.0` и `NaN`, null-checks);
>    - `ServerSentEventReconnector`: протестирован выброс `NoSuchElementException` в `next()` на пустом потоке;
>    - `AbstractHttpClient`: протестированы безопасные дефолты акцессоров (`getCookieHandler()`, `getCookieStore()`, `getProxySelector()`, `getAuthenticator()`, `getHttpClient()`) при `moduleInfo == null`, а также поведение при кастомном не-`CookieManager` обработчике кук.
> 2. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1422 / 1422** green across all 5 modules (+10 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% покрытие ВСЕХ методов библиотеки (0 missed methods)!
>      * **BRANCH:** **3,655 / 4,407 (82.94%)** — преодолен рубеж 82.9% (+1.14% за сессию)!
>      * **INSTRUCTION:** **29,852 / 31,198 (95.69%)** — новый исторический рекорд (+0.04% за сессию)!
>      * **LINE:** **6,492 / 6,795 (95.54%)** — новый исторический рекорд (+0.09% за сессию)!
>      * **COMPLEXITY:** **2,944 / 3,654 (80.57%)** — впервые преодолён рубеж 80.5%!
>
> **Юбилейная сессия #250 (free-аудит / харденинг: закрытие краевых веток resilience, auth, SSE, cookie, problem-details и рекорд JaCoCo 95.65% Instruction / 81.80% Branch Coverage):**
> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Харденинг и верификация краевых веток периферии:*
>    - `RateLimiter`: протестировано восстановление флага `Thread.currentThread().isInterrupted()` и возврат `false` при прерывании нити в `tryAcquire(Duration)` (`RateLimiterTest`);
>    - `CircuitBreaker`: протестирован возврат `Duration.ZERO` в `remainingWaitDuration()` при не-OPEN состояниях, вытеснение старого сбоя успехом в кольцевом буфере с декрементом `failureCount`, расчет метрик в состоянии `HALF_OPEN` и немедленный переход обратно в `OPEN` при получении сбоя (`CircuitBreakerTest`);
>    - `SingleFlightTokenManager`: протестировано оборачивание проверяемых исключений (checked exceptions) от поставщика токенов в `RuntimeException("Failed to acquire token", t)` (`TokenManagerTest`);
>    - `ReconnectPolicy`: протестирован расчет задержек `Jitter.EQUAL`, возврат `0` при `bound <= 0`, и валидация `stallTimeout` / `maxBackoff` в билдере (`ServerSentEventReconnectTest`);
>    - `FileCookiePersister`: протестирован перехват `IOException` с пробросом `UncheckedIOException` в `saveAll()` (`CookiePersistenceUnitTest`);
>    - `ProblemDetail` & `ZeroDepJsonParser`: протестирована сериализация `Iterable<?>` (списки/множества) и спецсимволов `\b`, `\f`, `\r` в properties, а также обработка ошибок парсера при незакрытых строках со слэшем `\"abc\\`, неполных `\u` кодах и пропущенных разделителях в массивах (`ProblemDetailTest`);
>    - `ControllerValidator`: протестировано отклонение пустых плейсхолдеров `${}` в путях методов, валидация `@CircuitBreaker` со `slidingWindowSize < minimumNumberOfCalls` и порогом `> 100%`, а также `@Retry` с отрицательными таймаутами бэкоффа (`ControllerValidationTest`);
>    - `GsonCodec`: протестирован перехват `JsonParseException` при сериализации с трансляцией в `JsonSyntaxException` (`GsonCodecTest`);
>    - `HttpTemplate`: протестировано создание пользовательских исключений через конструкторы `(String, int)` и `HandledException` fallback при отсутствии стандартных конструкторов в `instantiateThrowable` (`OnErrorWireMockTest`).
> 2. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1412 / 1412** green across all 5 modules (+13 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% покрытие ВСЕХ методов библиотеки (0 missed methods)!
>      * **INSTRUCTION:** **29,840 / 31,198 (95.65%)** — новый исторический рекорд (+0.68% за сессию, преодолен рубеж 95.6%)!
>      * **LINE:** **6,486 / 6,795 (95.45%)** — новый исторический рекорд (+0.51% за сессию)!
>      * **BRANCH:** **3,605 / 4,407 (81.80%)** — новый исторический рекорд (+0.61% за сессию, преодолен рубеж 81.8%)!
>
> **Сессия #249 (free-аудит / харденинг: устранение падения `HttpTemplate.fallbackMethod()` из-за package-private видимости — 33-я «тихая» находка серии, абсолютные рекорды JaCoCo: 100.00% Class Coverage (143/143) и 100.00% Method Coverage (1418/1418)):**

> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Находка (33-я в серии):* в `HttpTemplate.fallbackMethod()` использовался `HttpTemplate.class.getMethod("exchange")`. Метод `exchange()` в `HttpTemplate` объявлен package-private, из-за чего `Class.getMethod` (ищущий исключительно `public` методы) безусловно выбрасывал `NoSuchMethodException: ru.jrestly.http.HttpTemplate.exchange()`, оборачивающийся в `IllegalStateException: exchange method not found`. При вызове шаблона с `method == null` попытка создать `RequestContext` всегда приводила к аварийному завершению. Fix: заменено на `HttpTemplate.class.getDeclaredMethod("exchange")`. Оформлен `ADR-0047` `## Amendment #249`.
> 2. *Закрытие последних 10 непокрытых методов во всей библиотеке:*
>    - `HttpTemplate.fallbackMethod()`: протестирован в `HttpTemplateStreamContractTest`;
>    - `HttpTemplate.lambda$exchangeAsync$6`: протестирована запись success и failure в `CircuitBreakerWireMockTest` на асинхронном пути (`callFlakyAsync`);
>    - `TrailerCompletingInputStream.lambda$new$0`: протестирован непустой список трейлеров с `trailersConsumer == null` в `WireResponseUnitTest`;
>    - `HttpTemplateBuilder.lambda$applyTracingHeaders$5`: протестирован `@Trace(w3c = true, forceNew = true)` с заменой существующего заголовка `traceparent` в `RequestTracingWireMockTest`;
>    - `HttpTemplateBuilder.lambda$createHeaders$1`: протестировано слияние статических заголовков `@RequestHeader` с клиентскими заголовками аутентификации `authHeader(...)` в `StaticHeadersAndParamsTest`;
>    - `HttpTemplateBuilder$1.onError`: протестирован проброс ошибки `Flow.Publisher.onError` в `RequestBodyStreamingWireMockTest`;
>    - `WireResponse.lambda$new$0`: протестирован дефолтный `trailersSupplier` при непустых трейлерах в `WireResponseUnitTest`;
>    - `MultipartBodyPublisher$1.cancel`: протестирован прямой вызов и закрытие потока `ofStream().cancel()` в `MultipartPartFlexibilityTest`;
>    - `ClientMetricsListener.lambda$composite$0`: протестирован `onCallComplete` на пустом композите `composite()` в `ClientMetricsValidationTest`;
>    - `CompositeClientMetricsListener.onRetry`: протестировано делегирование `onRetry` в дочерние слушатели при `composite.size() >= 2` в `ClientMetricsValidationTest`.
> 3. *TDD и исторические гейты JaCoCo:*
>    - Всего тестов: **1399 / 1399** green across all 5 modules (+7 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1418 / 1418 (100.00%)** — абсолютное 100% покрытие ВСЕХ методов библиотеки (0 missed methods)!
>      * **INSTRUCTION:** **29,630 / 31,198 (94.97%)** — новый исторический рекорд!
>      * **LINE:** **6,451 / 6,795 (94.94%)** — новый исторический рекорд!
>      * **BRANCH:** **3,578 / 4,407 (81.19%)** — новый исторический рекорд!
>
> **Сессия #248 (free-аудит / харденинг: масштабное закрытие edge-cases в транспорте, кэшировании стримов, default-методах SPI и рекорд JaCoCo 94.53% Instruction / 99.29% Method Coverage):**
> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Транспорт и жизненный цикл (`AbstractHttpClientLifecycleTest`):*
>    - Верифицированы фабричные методы и акцессоры `getHttpClient()`, `getHttpEngine()` в `AbstractHttpClient`;
>    - Протестирован 1-аргументный конструктор `JdkHttpEngine(rawHttpClient)` без владения клиентом (`close()` не закрывает внешний HTTP-клиент, `rawClient()` возвращает переданный инстанс).
> 2. *Интерцепторы и контекст (`RequestContextTest`):*
>    - Покрыты default-методы `RequestContext`: `shortCircuit(WireResponse)`, `shortCircuitResponse()`, `isShortCircuited()`;
>    - Детально протестирован `SimpleResponseContext`: мутация атрибутов `setAttribute(key, value)`, `attributes()`, неизменяемость через `Collections.unmodifiableMap`, методы доступа к рефлексии `method()` и аргументам `args()`.
> 3. *Фасад клиента и авторизация (`JRestlyClientBuilderTest`):*
>    - Протестированы методы `authHeader(Supplier<String>)` в `JRestlyClient.Builder` и `SimpleModuleInfo.Builder`;
>    - Верифицированы все default-методы интерфейса `ModuleInfo` (`getAuthDetails()`, `getUrl()`, `getHttpCache()`, `getRateLimiter()`, `getCircuitBreaker()`, `getRetryPolicy()`, `getTracing()`, `getCookieStore()`, `getContentCompressors()`, `getOnErrorParsers()`, `getInterceptors()`, `getCodecs()`, `getHttpEngine()`);
>    - Протестированы мутаторы `SimpleModuleInfo.SimpleEnvironment` (`getUrl()`, `getAuthDetails()`, `setAuthDetails()`).
> 4. *HTTP-кэш и стриминг (`HttpCacheWireMockTest`):*
>    - Протестирована прозрачная трансформация закэшированного ответа в `InputStream` через `toStreamWireResponse`: как при мгновенном Cache HIT, так и при условной 304 Not Modified ревалидации на стриминговых методах контроллера.
> 5. *Трейлеры и потоки (`WireResponseUnitTest`, `ProgressBodyPublisherTest`, `MultipartPartFlexibilityTest`):*
>    - Детально протестирован `TrailerCompletingInputStream`: побайтовое чтение с CAS-триггером trailers на EOF, дефолтные null supplier/consumer;
>    - Протестирована отмена подписки `subscription.cancel()` в `MultipartBodyPublisher`;
>    - Верифицировано делегирование ошибок `onError(Throwable)` в downstream subscriber в `ProgressBodyPublisher`.
> 6. *Заголовки и метрики (`ResponseHeadersTest`, `ClientMetricsValidationTest`):*
>    - Проверен `ResponseHeaders.requestId()` с fallback на заголовок `X-Correlation-ID`;
>    - Покрыт default-метод `ClientMetricsListener.onRetry`.
> 7. *Контракт стриминга и акцессоры (`HttpTemplateStreamContractTest`):*
>    - Проверены вспомогательные методы доступа и конфигурации `HttpTemplate`.
> 8. *TDD и гейты:*
>    - Всего тестов: **1392 / 1392** green across all 5 modules (+16 тестов, 0 failures, 0 errors, 0 warnings under `-Werror`).
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)** — абсолютное 100% покрытие всех классов библиотеки!
>      * **METHOD:** **1408 / 1418 (99.29%)** — новый исторический рекорд (осталось лишь 10 непокрытых методов во всем проекте)!
>      * **INSTRUCTION:** **29,492 / 31,198 (94.53%)** — новый рекорд (+0.99% за сессию)!
>      * **LINE:** **6,423 / 6,795 (94.53%)** — новый рекорд (+1.14% за сессию)!
>      * **BRANCH:** **3,557 / 4,407 (80.71%)** — новый рекорд (+0.54% за сессию)!
>
> **Сессия #246 (free-аудит / качество: верификация контрактов WireResponse, ResponseHeaders, PersistentCookieStore, TraceContext, ProblemDetail, исторический рубеж JaCoCo 80.17% Branch / 93.51% Instruction Coverage):**
> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Сьют `WireResponseUnitTest`:*
>    - Создан специализированный сьют `ru.jrestly.http.WireResponseUnitTest` (+10 тестов), детально верифицирующий все методы `WireResponse` и `WireResponse.Builder`:
>      `of(statusCode)`, `of(statusCode, String)` (null/non-null), `of(statusCode, byte[])` (null/empty/non-empty), `of(statusCode, headers, String/byte[])`, `header(name)` case-insensitive поиск и null-check, `hasBody()`, `Builder.body(byte[])`, `Builder.body(String, Charset)`, `Builder.stream(InputStream)` со сбросом байтов, `Builder.version(null)` дефолт к `HTTP_1_1`, `Builder.headers(null)` / `Builder.trailers(null)` null-safety, а также `equals()`, `hashCode()`, и форматирование `toString()`.
> 2. *ResponseHeaders:*
>    - `ageSeconds()`: детальные проверки неотрицательного парсинга, нулевого возраста, отрицательных значений (`OptionalLong.empty()`), нечисловых строк и отсутствующего заголовка;
>    - `captureCallable(Callable<T>)`: проверен успешный возврат и связывание заголовков, проброс checked exceptions (например, `IOException`) с гарантированной очисткой ThreadLocal `CURRENT_CAPTOR`, и вложенные вызовы с восстановлением предыдущего captor;
>    - `Set-Cookie2` заголовок: верифицирован парсинг и объединение с `Set-Cookie`, регистронезависимый поиск `cookie(name)` / `cookieValue(name)`, и `cookieMap()` с дефолтом `""` для cookie без значений;
>    - `populate` 4-аргументная перегрузка с `HttpClient.Version.HTTP_2` и HTTP/2 trailers (`declaredTrailers`, `firstTrailer`, `trailerValues`).
> 3. *PersistentCookieStore & FileCookiePersister:*
>    - `PersistentCookieStore.getURIs()` делегирование к underlying store и `Builder.delegate(customDelegate)`;
>    - Обработка `.local` доменов при preload (автоматическое связывание с bare host без `.local`), обработка cookie с пустым/null доменом;
>    - `FileCookiePersister` на путях без родительской директории (относительные пути).
> 4. *TraceContext & TracePropagators:*
>    - `TraceContext.of(traceId, spanId, traceFlags)` 3-аргументная фабрика, `TraceContext.parse(traceparent)` 1-аргументный метод;
>    - W3C Level 1 forward-compatibility: версии 01+ с дополнительными метаданными успешно парсируются;
>    - `isSampled()` побитовая проверка младшего бита traceFlags ("00", "01", "02", "03", "10", "11");
>    - Дефолтные фабрики `TracePropagators.correlationId()`, `TracePropagators.correlationId(headerName)`, безопасный пустой composite, threadLocal без fallback генерации, защита существующего `tracestate` от перезаписи, и строгая null/blank валидация аргументов.
> 5. *ProblemDetail & ZeroDepJsonParser:*
>    - Проверка безопасного возврата `null` при несоответствии типов в `getProperty(name, Class<T>)`;
>    - `Builder.instance(URI)` и фильтрация пустых/null имен свойств в `Builder.property`;
>    - Проверка fail-fast ошибок `ZeroDepJsonParser` при невалидных числах (`1.2.3`, `1e+`), незакрытых скобках во вложенных объектах/массивах, и не-строковых ключах.
> 6. *TDD и гейты:*
>    - Всего тестов: **1376 / 1376** green across all 5 modules (+21 тест, 0 failures, 0 errors).
>    - Javadoc чист под `-Werror`, 0 warnings.
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**
>      * **BRANCH:** **80.17%** (3,533 / 4,407) — впервые преодолен рубеж 80%!
>      * **INSTRUCTION:** **93.51%** (29,174 / 31,198) — новый исторический рекорд!
>      * **LINE:** **93.39%** (6,346 / 6,795) — новый исторический рекорд!
>      * **METHOD:** **96.76%** (1,372 / 1,418) — новый исторический рекорд!
>    - ADR-0064 дополнен `## Amendment #246`.
>
> **Сессия #245 (free-аудит / качество: устранение бесконечного цикла в `MoshiCodec.copyValue` на null-токенах — 32-я «тихая» находка серии, очистка мертвого кода в `HttpTemplateBuilder`, рекорд JaCoCo 92.85% Instructions / 79.08% Branches):**
> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Находка (32-я в серии):* в `MoshiCodec.copyValue` при обработке ветки `case NULL` ридер вызывал `writer.nullValue()`, но забывал потребить токен `reader.nextNull()`. При наличии `null` значений в массивах (или объектах) ридер зависал в бесконечном цикле, непрерывно аллоцируя сегменты памяти буфера Okio вплоть до `java.lang.OutOfMemoryError: Java heap space`. Fix: добавлен вызов `reader.nextNull()` в ветке `case NULL`.
> 2. *Гигиена и очистка кода в `HttpTemplateBuilder`:*
>    - Удалены мертвые неиспользуемые private методы `resolveInterfaceAccept`, `resolveMethodAccept`, `resolveInterfaceContentType`, `resolveMethodContentType`, оставшиеся после перехода на статическое предвычисление заголовков в `MethodMetadata.parseStaticHeaders`.
> 3. *Периферийные компоненты и edge-cases:*
>    - `CircuitBreakerTest`: добавлены детальные проверки валидации входных параметров `CircuitBreakerConfig` (порог сбоев, размер окна, таймауты, проверки на null) и кастомного предиката `recordException`;
>    - `InMemoryHttpCacheTest`: протестирован контракт `SimpleCachedResponse` с edge-cases (null headers/body/version, валидаторы etag/lastModified, замещение существующего заголовка `Age`);
>    - `CookiePersistenceUnitTest`: протестирован `FileCookiePersister` с кастомным `Charset` и строгой null-валидацией;
>    - `TokenManagerTest`: протестирован `SingleFlightTokenManager` с обработкой `Error`, `null` токенов и дефолтного нулевого буфера;
>    - `JacksonCodecDefaultsTest`: протестирована обработка ошибок при десериализации битых байт-массивов и сериализации несериализуемых типов;
>    - `MethodMetadataTest`: протестированы аксессоры и флаги параметров (`hasMultipartParams`, `hasRequestParams`, `hasPathVariables`, `hasRequestHeaders`, `methodAccept`, `interfaceAccept`, `interfaceTrace`);
>    - `ProblemDetailTest`: протестированы дефолтные RFC заголовки для всех 20 статусов, сериализация `URI` и кастомных объектов в properties, экранирование управляющих символов `< 0x20`.
> 4. *TDD и гейты:*
>    - Всего тестов: **1355 / 1355** green across all 5 modules (+12 тестов, 0 failures, 0 errors).
>    - Javadoc чист под `-Werror`, 0 warnings.
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**
>      * **INSTRUCTION:** **92.85%** (28,967 / 31,198) — новый исторический рекорд!
>      * **LINE:** **92.73%** (6,301 / 6,795) — новый исторический рекорд!
>      * **BRANCH:** **79.08%** (3,485 / 4,407) — преодолен рубеж 79%!
>      * **METHOD:** **95.91%** (1,360 / 1,418) — преодолен рубеж 95.9%!
>    - ADR-0007 дополнен `## Amendment #245`.
>

> **Сессия #244 (free-аудит / качество: верификация публичного фасада `JRestlyClient.Builder`, сериализация вложенных Map в `ProblemDetail`, преодоление рубежа 92% Instruction Coverage при 100% Class Coverage):**
> свободный ход развития качества и устойчивости кодовой базы.
> 1. *Фасад `JRestlyClient.Builder`:*
>    - Создан специализированный сьют `JRestlyClientBuilderTest.java` (+10 тестов), детально верифицирующий все методы публичного фасада билдера и их трансляцию в `ModuleInfo`:
>      `inMemoryCache()` и `inMemoryCache(int)` (RFC 9111 HTTP-кэш), `virtualThreads(boolean)` / `useVirtualThreads()`, `executor`, `cookieHandler`, `cookiePolicy`, `proxy`, `noProxy`, `authenticator`, `proxyCredentials`, `credentials`, `requestCompression`, `contentCompressor`, `decompression`, `tracePropagator`, `correlationId` (в т.ч. ThreadLocal и custom suppliers), `w3cTracing`, `circuitBreaker`, `circuitBreakerRegistry`, `rateLimiter`, `rateLimiterRegistry`, `tokenManager`, `retryOnUnauthorized`, `bearerAuth`, `basicAuth`, `apiKeyHeader`, `apiKeyQuery`, `shortCircuit`, `mock` (unconditional, predicate, dynamic supplier), `codecs`, `connectTimeout`, `socketTimeout`, `httpVersion`, `validateControllers`, `retry`, `httpEngine`.
> 2. *ProblemDetail & ZeroDepJsonParser:*
>    - Протестирована и верифицирована сериализация вложенных `Map<?, ?>` и `null` значений в extension properties для `toJson()` / `fromJson()`;
>    - Протестированы числа `Long` (> Integer.MAX_VALUE), парсинг escape-символов (`\/`, `\b`, `\f`, `\r`, `\t`), экранированные кавычки во вложенных структурах и строковые статусы;
>    - Протестирован `ProblemDetail.Builder.properties(Map)`.
> 3. *Периферийные компоненты:*
>    - `FileCookiePersister.of(File)` протестирован в `CookiePersistenceUnitTest.java`;
>    - `OnErrorParser` получил тесты на обратный порядок вызова legacy-сеттеров (`setErrorClass` до `setStatuses`), `canParse` с `legacyStatuses` без класса и `getErrorClass()`;
>    - `InMemoryHttpCache` получил тесты на `update` некэшированного URI, `put` некэшируемых ответов (статус 500, null body, без директив/валидаторов) и невалидный HTTP-date.
> 4. *TDD и гейты:*
>    - Всего тестов: **1343 / 1343** green across all 5 modules (+19 тестов, 0 failures, 0 errors).
>    - Javadoc чист под `-Werror`, 0 warnings.
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**
>      * **INSTRUCTION:** **92.02%** (28,738 / 31,231) — преодолён 92% рубеж!
>      * **LINE:** **91.92%** (6,248 / 6,797) — преодолён 91.9% рубеж!
>      * **BRANCH:** **77.76%** (3,433 / 4,415).
>      * **METHOD:** **95.29%** (1,355 / 1,422).
>    - ADR-0064 дополнен `## Amendment #244`.
>
> **Сессия #243 (free-аудит / харденинг: устранение искажения массивов в `ProblemDetail.toJson()` — 31-я «тихая» находка серии, рубеж 90% Instruction / Line Coverage при 100% Class Coverage):**
> свободный ход развития качества кодовой базы.
> 1. *Находка (31-я в серии):* в `ProblemDetail.appendJsonValue` проверка на списочные структуры поддерживала только `Iterable<?>`. При передаче в extension properties нативных массивов Java (`String[]`, `int[]`, `Object[]` и др.) сериализатор проваливался в `else` ветку, вызывал `val.toString()` и записывал в JSON имя типа `"[Ljava.lang.String;@5e265ba4"` вместо валидного JSON-массива `[...]`. Кроме того, в `ZeroDepJsonParser.parseString` последовательности `\uXXXX` без 4 шестнадцатеричных символов или с невалидным hex приводили к тихому проглатыванию или неконтролируемому `NumberFormatException`.
> 2. *Fix:*
>    - `ProblemDetail.appendJsonValue` проверяет `val.getClass().isArray()` и рекурсивно форматирует элементы через `java.lang.reflect.Array` (полная поддержка примитивных и объектных массивов);
>    - `ZeroDepJsonParser.parseString` строго валидирует ровно 4 шестнадцатеричные цифры после `\u` и бросает fail-fast `IllegalArgumentException`;
>    - Расширено покрытие fluent-сеттеров, строковых геттеров, конструкторов `ProblemDetailException`, директив `CacheControl` и расчета `freshnessLifetime` по `Expires`/`Date` в `InMemoryHttpCache`.
> 3. *TDD и гейты:*
>    - Новые unit-тесты: `serializesArraysInProperties` (red-first), `fluentSettersAndPropertyAccess`, `unicodeEscapesInZeroDepParser`, `problemDetailExceptionConstructors`, `testToStringAndDirectives`, `expiresAndDateFreshnessCalculation`, `safeNullHandling`, `onErrorParserEmptyDefaults`, `onErrorParserDefaultOnly` (+9 тестов, суммарно **1324/1324** тестов green across all 5 modules).
>    - Javadoc чист под `-Werror`, 0 warnings.
>    - **JaCoCo Coverage:**
>      * **CLASS:** **143 / 143 (100.00%)**
>      * **INSTRUCTION:** **90.85%** (28,373 / 31,231) — преодолён 90% рубеж!
>      * **LINE:** **90.64%** (6,161 / 6,797) — преодолён 90% рубеж!
>      * **BRANCH:** **76.92%** (3,396 / 4,415).
>    - ADR-0064 дополнен `## Amendment #243`.
>
> **Сессия #242 (free-аудит / харденинг: устранение зависания и бесконечного цикла в `ProblemDetail$ZeroDepJsonParser` — 30-я «тихая» находка серии, 100% Class Coverage 143/143):**
> свободный ход после упаковки релиза 0.10.0 (сессия #241, highest ADR 0065). Провел глубокий аудит парсера `ZeroDepJsonParser` и отчёта JaCoCo.
> 1. *Находка (30-я в серии):* в `ProblemDetail$ZeroDepJsonParser.parseValue` нераспознанные токены (например, `[?]`, `[abc]`, незаполненные значения `{"key": }`) сваливались в числовой парсер, считывали 0 символов (`start == i`), бросали `NumberFormatException`, ловили его и возвращали пустую строку `""` с не сдвинутым индексом `i`. В `parseArray` это приводило к бесконечному циклу `list.add("")` с исчерпанием памяти (`OutOfMemoryError`). Кроме того, ключи с экранированными символами давали неверное смещение в `i - key.length() - 2`.
> 2. *Fix:*
>    - Числовой парсер строго проверяет стартовую цифру или знак минус;
>    - Нераспознанные токены мгновенно выбрасывают `IllegalArgumentException("Unexpected token at index X: 'c'")`;
>    - Пропуск ключей объекта использует прямой вызов `skipQuotedString(json, i)` без неверных арифметических сдвигов;
>    - Незакрытые строки, скобки массивов и объектов, пропущенные двоеточия и запятые выбрасывают fail-fast `IllegalArgumentException`.
> 3. *Default Methods & 100% Class Coverage:*
>    - Добавлены тесты default методов для `PayloadCodec.prettyPrint` и `PayloadCodecProvider.priority()`.
>    - Число покрытых классов достигло **143 / 143 (100.00%)** — абсолютный рекорд качества за всю историю проекта!
> 4. *Гейты:* `./gradlew test javadoc jacocoTestReport` — **1315/1315** тестов зелёные (+4 теста), 0 failures/errors, 0 warnings under `-Werror`.
>    JaCoCo: **89.93% Instructions**, **89.77% Lines**, **75.50% Branches**, **100.00% Classes**. ADR-0064 дополнен `## Amendment #242`.
>
> **Сессия #241 (релизный цикл 0.10.0: ADR-0065, версионирование, CHANGELOG.md, README.md, POM 0.10.0 и полный гейт):**
> упаковка монументального релиза 0.10.0 (сессии #94–#241, 45 ADR от ADR-0020 до ADR-0064).
> 1. *Документ ADR-0065:* оформлен `docs/0.10.0/ADR-0065-release-0.10.0-cycle.md` — систематизация всех направлений 0.10.0 (безопасность/SSL, движки OkHttp/Apache5, кодеки XML, Zero-Garbage hot-path, JaCoCo, три Vital Gaps: `@TargetUrl`, RFC 9111 HTTP Cache, RFC 9457 Problem Details).
> 2. *Версионирование:* бамп версий `0.9.0` → `0.10.0` в корневом `build.gradle` и во всех субмодулях (`codec-jackson-xml`, `engine-okhttp`, `engine-apache5`).
> 3. *CHANGELOG.md & README.md:* добавлена секция 0.10.0 в CHANGELOG.md; в README.md обновлен статус, расширен список Features и добавлены разделы с примерами для `@TargetUrl`, RFC 9111 HTTP Cache и Problem Details.
> 4. *Гейты и POM:* `./gradlew test javadoc jacocoTestReport generatePomFileForMavenJavaPublication` — 1311/1311 тестов зелёные, Javadoc чист под `-Werror`, JaCoCo 89.4% Instructions / 98.6% Classes, POM 0.10.0 содержит 0 runtime dependencies (Zero-Dep Core нерушим).
> 5. *Highest ADR:* **0065** (next free **0066**).
>
> **Сессия #240 (стабилизация и полный гейт: верификация ADR-0064, устранение doclint warning и 1311/1311 green):**
> завершение цикла реализации трех ключевых функциональных разрывов (Vital Gaps #1–#3 из ADR-0061).
> 1. *Диагностика и Doclint Gate:*
>    - В сессии #239 был подготовлен полный стек ADR-0064 (Problem Details for HTTP APIs RFC 7807/RFC 9457), но сборка прервалась из-за doclint warning `no comment` на поле `problemDetail` в `ProblemDetailException` (в силу наследования от `Serializable`).
>    - Добавлен нормативный Javadoc и тег `@serial` в `ProblemDetailException`.
> 2. *Полный гейт:*
>    - `./gradlew test javadoc jacocoTestReport` — BUILD SUCCESSFUL.
>    - Счётчик тестов: **1311/1311** (core 1231 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors.
>    - Javadoc чист под `-Werror`, 0 warnings.
>    - JaCoCo: **89.38% Instructions**, **89.17% Lines**, **74.69% Branches**, **98.60% Classes** (141/143 классов покрыто тестами).
>    - Все три Vital Gaps из конкурентной матрицы ADR-0061 (Gap 1 `@TargetUrl`, Gap 2 RFC 9111 HTTP Cache, Gap 3 RFC 7807/RFC 9457 Problem Details) зафиксированы в коде, тестах и документации.
>
> **Сессия #239 (фича: ADR-0064 Problem Details for HTTP APIs (RFC 7807 / RFC 9457) & Error Ergonomics):**
> реализация третьего критического разрыва функциональности (Vital Gap #3 из ADR-0061).
> 1. *Пакет `ru.jrestly.problem`:*
>    - `ProblemDetail`: zero-dependency модель по RFC 9457 (§3.1): `type` (дефолт `about:blank`), `title` (с дефолтными HTTP фразами), `status`, `detail`, `instance`, `properties` (extension members map).
>    - Встроенный zero-dep JSON парсер `ProblemDetail.fromJson(String)` и компактный сериализатор `ProblemDetail.toJson()`.
>    - Полная совместимость с внешними кодеками: аннотации `@JsonAnyGetter` / `@JsonAnySetter` для Jackson, поддержка Gson и Moshi.
>    - Фабрики `ProblemDetail.forStatus(int)`, `forStatusAndDetail(int, String)` и `ProblemDetail.builder()`.
>    - `ProblemDetailException`: наследник `HandledException`, несущий `ProblemDetail` с лаконичным форматированием в `getMessage()`.
> 2. *Расширение иерархии исключений:*
>    - `HandledException`: метод `public Optional<ProblemDetail> getProblemDetail()`.
>    - `UnexpectedStatusException`: поле `problemDetail`, 4-аргументный конструктор, переопределение `getProblemDetail()`, обогащенный `getMessage()`.
>    - `ClientErrorException` и `ServerErrorException`: 4-аргументные конструкторы с поддержкой `ProblemDetail`.
> 3. *Диспетчеризация и авто-резолвинг в `HttpTemplate`:*
>    - Авто-детекция Problem Details при неожиданных 4xx/5xx: проверка `application/problem+json` или структуры JSON (наличие `status`, `title`, `detail`, `type`, `instance`). Попытка через `PayloadCodec` с прозрачным fallback на zero-dep `ProblemDetail.fromJson`.
>    - Декларативный `@OnError`: поддержка `errorObject = ProblemDetail.class` (выбрасывает `HandledException` с `getProblemDetail()`) и `errorObject = ProblemDetailException.class` (выбрасывает `ProblemDetailException` напрямую).
>    - 100% паритет sync `exchange()` и async `exchangeAsync()` (`CompletableFuture`).
> 4. *Тесты и Гейт:*
>    - Unit-тесты `ProblemDetailTest` (9 тестов): дефолты, фабрики, builder, RFC 9457 Out-of-Credit парсинг, toJson roundtrip, escaping, error cases, equals/hashCode.
>    - WireMock e2e `ProblemDetailWireMockTest` (8 тестов): 403 ClientErrorException + extension members, 500 ServerErrorException, application/json problem members, @OnError ProblemDetail, @OnError ProblemDetailException, async CompletableFuture, non-JSON 502 HTML, arbitrary JSON.
>    - Highest ADR: **0064** (next free **0065**). Все три Vital Gaps из ADR-0061 полностью закрыты!
>
> **Сессия #238 (фича: ADR-0063 Transparent RFC 9111 HTTP Cache SPI + In-Memory Store):**
> реализация критического разрыва функциональности (Vital Gap #2 из ADR-0061).
> 1. *SPI Package `ru.jrestly.cache`:*
>    - `HttpCache`: `get`, `put`, `update` (304 merging), `invalidate`, `clear`, `size`.
>    - `CachedResponse` & `SimpleCachedResponse`: thread-safe immutable entries with dynamic RFC 9111 `Age` header computation, `isFresh()`, `mustRevalidate()`, `hasValidators()`.
>    - `CacheControl`: парсер директив заголовка (`max-age`, `no-cache`, `no-store`, `must-revalidate`, `stale-while-revalidate`, `private`, `public`).
>    - `InMemoryHttpCache`: потокобезопасный LRU-кэш на `LinkedHashMap` + `ReentrantLock`, нормализация URI-ключей, слияние заголовков по RFC 9111 §4.3.4 (сохранение representation headers `Content-Type`/`Content-Length`), фильтрация статусов (200, 301, 404).
>    - `HttpCaches`: фабрики `inMemory()` и `inMemory(int maxEntries)`.
> 2. *Интеграция в конфигурацию:*
>    - `ModuleInfo.getHttpCache()`, `SimpleModuleInfo.Builder.httpCache(...)`.
>    - `JRestlyClient.Builder.httpCache(...)`, `inMemoryCache()`, `inMemoryCache(int maxEntries)`.
> 3. *Диспетчеризация в `HttpTemplate` (100% паритет sync `exchange()` и async `exchangeAsync()`):*
>    - Cache HIT: безопасный метод (`GET`), `cached.isFresh()`, `!cached.mustRevalidate()` и отсутствие `no-cache` в запросе ➔ немедленный возврат ответа с вычисленным `Age` заголовком, `attempts = 0` (шорт-циркуит в метриках, `CircuitBreaker` не задействуется).
>    - Условная ревалидация: при устаревании или `must-revalidate`/`no-cache` инжектируются `If-None-Match` и `If-Modified-Since` (не затирая пользовательские заголовки); при получении `304 Not Modified` кэш обновляется и прозрачно синтезируется `200 OK` с кэшированным телом; при получении `200 OK` кэш сохраняет свежий ответ.
>    - Инвалидация: при небезопасных методах (`POST`, `PUT`, `DELETE`, `PATCH`) и статусах 200..399 кэш инвалидирует целевой URI.
>    - Директивы `Cache-Control: no-store` полностью обходят кэш на чтение и запись.
>    - Zero-garbage hot path при отключенном кэше (`httpCache == null`).
> 4. *Эргономика:* методы `ResponseHeaders.age()` и `ResponseHeaders.ageSeconds()`.
> 5. *Тесты и Гейт:*
>    - Unit-тесты: `CacheControlTest` (5 тестов), `InMemoryHttpCacheTest` (7 тестов).
>    - WireMock e2e: `HttpCacheWireMockTest` (+10 тестов: cache hit, age header, metrics attempts=0, revalidation 304, revalidation 200, request no-cache, request no-store, response no-store, unsafe method invalidation, async CompletableFuture, byte[] return).
>    - Гейт **1293/1293** (core 1213 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
>      `./gradlew test javadoc jacocoTestReport` зелёный под `-Werror`, 0 warnings.
>    - JaCoCo: **90.75% Instructions**, **90.67% Lines**, **77.17% Branches**, **98.55% Classes** (136/138 классов покрыто).
>    - Highest ADR: **0063** (next free **0064**). Следующий шаг — Gap #3: RFC 7807 / RFC 9457 Problem Details.
>
> **Сессия #237 (фича: ADR-0062 Dynamic Endpoint Target URL Override via `@TargetUrl`):**
> реализация критического разрыва функциональности (Vital Gap #1 из ADR-0061).
> 1. *Аннотация `@TargetUrl`:* добавлена в `ru.jrestly.annotation` (параметр метода, runtime retention)
>    для `java.net.URI`, `java.net.URL`, `CharSequence`/`String`.
> 2. *Эргономика:* HTTP-глаголы (`@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`) получили `path() default ""`
>    для лаконичных контрактов (`@Get String download(@TargetUrl URI uri);`).
> 3. *Пайплайн разрешения URL:* в `HttpTemplateBuilder.createUrl()` добавлено динамическое разрешение:
>    - если runtime-значение `null` — мгновенный `IllegalArgumentException("@TargetUrl parameter value cannot be null")`;
>    - если `path` пустой — целевой URL берется как есть (включая query-параметры S3 presigned URL);
>    - если `path` относительный — корректная нормализация слэшей при склеивании;
>    - плейсхолдеры `${id}` из `@PathVariable` и query-параметры из `@RequestParam` корректно дополняют URL.
> 4. *100% Fail-Fast Валидация в `ControllerValidator`:*
>    - проверка типа: только `URI`, `URL`, `CharSequence`/`String`;
>    - арность: не более одного `@TargetUrl` на метод;
>    - взаимное исключение: запрет сочетания с `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestBody`, `@MultipartFormFile`, а также side-channel хуками `ResponseHeaders`, `UploadProgressListener`, `DownloadProgressListener`.
> 5. *Zero-Reflection Hot-Path:* `targetUrlParamIndex` кэшируется в `MethodMetadata` при инициализации прокси.
> 6. *Тесты и Гейт:* red-first сьют `ru.jrestly.TargetUrlTest` (+12 тестов, unit-валидация + WireMock e2e: URI, URL, String, path variables, query params, null check, async `CompletableFuture`).
>    Гейт **1270/1270** (core 1190 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
>    `./gradlew test javadoc jacocoTestReport` зелёный под `-Werror`, 0 warnings.
>    JaCoCo: **91.35% Instructions**, **91.13% Lines**, **78.71% Branches**, **98.50% Classes** (131/133 классов покрыто).
>    Highest ADR: **0062** (next free **0063**). Следующий шаг — Gap #2: RFC 9111 HTTP Cache SPI + Store.
>
> **Сессия #236 (стратегия/архитектура: ADR-0061 конкурентный анализ, аудит избыточности и внедрение JaCoCo quality baseline):**
> ответ на стратегический запрос Антона в mailbox (2026-10-17 15:18) о контроле качества кодовой базы, сравнении с конкурентами,
> поиске жизненно необходимых фичей и аудите на предмет лишнего (bloat).
> 1. *Конкурентный ландшафт (Retrofit 2.11, OpenFeign 13.5, Spring 6 HTTP Interfaces, OkHttp 4/5):*
>    составлена детальная матрица по зависимостям, прозрачности типов возврата, производительности на hot-path (~5–9 µs/op
>    zero-garbage dispatch vs рефлексия конкурентов) и 100% fail-fast валидации контрактов при создании прокси.
> 2. *Жизненно необходимые фичи (Vital Gaps):*
>    (a) **Gap 1 (критический): Dynamic Endpoint / Target URL Override (`@TargetUrl` / `URI` parameter)** — в реальных API
>    (S3 presigned URLs, HATEOAS Link pagination, webhooks, dynamic routing) URL назначения определяется в рантайме. В Retrofit
>    есть `@Url`, в Feign — `URI baseUri`, в Spring — `URI url`. В jRestly базовый URL жестко зафиксирован в клиенте, а `@Get(path)`
>    статичен — это главный практический барьер для промышленного применения библиотеки.
>    (b) **Gap 2 (высокая ценность): Прозрачный RFC 9111 HTTP Cache SPI + In-Memory Store** — ETag/304, Cache-Control (max-age,
>    stale-while-revalidate) для снижения задержек и трафика.
>    (c) **Gap 3 (средняя ценность): Problem Details (RFC 7807 / RFC 9457)**.
> 3. *Аудит избыточности (Bloat Audit):* зафиксированы границы ядра по ADR-0001 (Zero-Dep Core) и ADR-0003 (Non-goals: не строим Spring Cloud).
>    `CircuitBreaker` и `RateLimiter` остаются компактными локальными SPI + default in-memory без внешних распределенных комбайнов.
> 4. *Quality Control Baseline:* в `build.gradle` интегрирован **JaCoCo 0.8.12** (`test.finalizedBy jacocoTestReport`).
>    Сняты первые точные метрики покрытия: **91.37% Instructions**, **91.15% Lines**, **78.92% Branches**, **98.50% Classes** (131/133 классов покрыты тестами).
>    Все пакеты имеют покрытие от 86.5% до 100%.
> 5. *Документы:* оформлен `docs/0.10.0/ADR-0061-competitive-analysis-and-quality-control-baseline.md`; `ROADMAP.md` обновлен до **ADR-0061** (next free **0062**);
>    mailbox и дневник обновлены.
>    Гейт **1258/1258** (core 1178 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
>    `./gradlew test javadoc jacocoTestReport` зелёный под `-Werror`, 0 warnings. Следующий ход — реализация Gap 1 (`@TargetUrl`).
>
> **Сессия #235 (free-аудит: `@ExpectStatus` и `@FollowRedirects` не валидировались eager-валидатором — 29-я «тихая» находка серии, 100% семейство аннотаций закрыто):** свободный
> ход из контекста #234 (активная зона пуста, highest ADR 0060). Добил оставшийся хвост
> по декларативным аннотациям: все аннотации с конфигурируемыми параметрами имели fail-fast
> проверки в `ControllerValidator`, кроме `@ExpectStatus` и `@FollowRedirects`.
> Факт: (1) пустой `@ExpectStatus(statuses = {})` превращал любой HTTP-ответ (включая 200 OK)
> в `UnexpectedStatusException`, т.к. `isExpectedStatus` проверял `expectStatuses.contains(code)`
> на пустом списке — метод становился молча неработоспособным без единой подсказки при создании контроллера;
> статусы вне диапазона `[100, 599]` также проскакивали в отличие от `@OnError` и `@Retry`;
> (2) отрицательный `@FollowRedirects(count < 0)` принимался на создании прокси, в рантайме
> редиректы не выполнялись (`hopsLeft > 0` false), но при 3xx-ответе логгер выдавал ложное
> `"Too many redirects, returning the last response"`. Fix: `ControllerValidator.validateMethod`
> получил проверки: `@ExpectStatus statuses must not be empty`, `@ExpectStatus status must be in [100, 599], was X`,
> и `@FollowRedirects count must not be negative, was X`. Javadoc `ControllerValidator` дополнен.
> Публичный API не тронут. TDD red-first: +2 теста в `ControllerValidationTest`
> (`rejectsInvalidExpectStatusConfigurationEagerly` и `rejectsInvalidFollowRedirectsConfigurationEagerly`) —
> красные на до-фикс коде ровно по отсутствию проверок (0 проблем вместо 3 и 2) → зелёные после фикса;
> no-false-positive на валидных значениях (`{200, 201}`, `count = 0`, `count = 3`).
> ADR-0004 → `## Amendment #235`. Гейт **1258/1258** (core 1178 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures/errors, `./gradlew test javadoc` зелёный под `-Werror`,
> 0 warnings. Семейство декларативных аннотаций контроллеров закрыто на 100%. Дальше — free-аудит
> (Path/File-тела метрик, зависимость других деклараций от окружения сборки) или концепт ADR-0061+
> (HTTP-кэш RFC 9111 ждёт решения Антона; вопрос в mailbox).

> **Сессия #234 (free-аудит: `ResponseHeaders` не валидировал HTTP-аннотации и кратность параметров listener'ов/headers — 28-я «тихая» находка серии):** свободный
> ход из контекста #233 (активная зона пуста, highest ADR 0060). Исследовал семейство
> side-channel параметров-хуков (`ResponseHeaders`, `UploadProgressListener`,
> `DownloadProgressListener`). У сиблингов `UploadProgressListener` и
> `DownloadProgressListener` уже была eager-проверка на запрет HTTP-аннотаций
> (ADR-0030/ADR-0033), а `ResponseHeaders` не проверялся вовсе. При ошибочном аннотировании
> (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestBody`,
> `@MultipartFormFile`) метод молча проходил валидацию, а в рантайме `ResponseHeaders.toString()`
> уходил на провод, уродуя запрос. Кроме того, ни у одного из трех хук-параметров не
> проверялась кратность (aridity): при объявлении двух `ResponseHeaders` (или двух
> `UploadProgressListener`/`DownloadProgressListener`) рантайм `MethodMetadata`
> запоминал только первый (`respHeadersIdx < 0`), а второй тихо оставался пустым (тихий no-op).
> Fix: `ControllerValidator.validateMethod` проверяет: (1) `ResponseHeaders` запрещает
> HTTP-аннотации (`method(): ResponseHeaders parameter must not be annotated with HTTP parameter annotations`);
> (2) `ResponseHeaders`, `UploadProgressListener` и `DownloadProgressListener` допускаются не
> более одного раза на метод (`method(): multiple parameters of type X, at most one is allowed`).
> Javadoc `ControllerValidator` уточнен. Публичный API не тронут. TDD red-first: новый сьют
> `ResponseHeadersValidationTest` (11 тестов: 10 падающих red-first тестов на каждый тип
> невалидной конфигурации + eager `ControllerValidationException` на создании прокси, 1 тест
> no-false-positive на валидном объявлении) — красные на до-фикс коде (10 failed, 1 passed)
> → все 11 зелёные. ADR-0022 → `## Amendment #234`.
> Гейт **1256/1256** (core 1176 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures/errors, `./gradlew test javadoc`
> зелёный под `-Werror`, 0 warnings. Дальше — free-аудит (Path/File-тела метрик;
> `@ExpectStatus`/`@FollowRedirects` валидация) или концепт ADR-0061+ (HTTP-кэш
> RFC 9111 ждёт решения Антона; вопрос в mailbox).

> **Сессия #233 (free-аудит: `payloadParamCount` в `ControllerValidator` считал
> `@PathVariable` payload-параметром — form/multipart с одним `@PathVariable`
> тихо собирал и слал пустое тело — 27-я «тихая» находка серии):** свободный ход из
> контекста #216 (активная зона пуста, highest ADR 0060). Взял подсказку #216
> («форма `payloadParamCount`, считающая `@PathVariable`»). Исторически
> `payloadParamCount` исключал не-payload типы отрицательным фильтром
> (`!ResponseHeaders && !UploadProgressListener && !DownloadProgressListener &&
> !@Cookie && !@RequestHeader`), но пропустил `@PathVariable`. Метод form-urlencoded
> или multipart, принимающий только `@PathVariable` (например, `@Post(path = "/users/${id}",
> requestType = RequestType.APPLICATION_FORM_URLENCODED) void submit(@PathVariable("id") String id);`),
> успешно проходил eager-валидацию (0 проблем), но `HttpTemplateBuilder.createUrlEncodedBody`
> собирает тело строго из `@RequestParam` (`createRequestParams`), а
> `createMultipartParts` — строго из `@MultipartFormFile` и `@RequestParam`. В итоге
> `createUrlEncodedBody` отдавал `new byte[0]`, `createMultipartParts` — пустой список
> частей, и запрос молча уходил на провод с пустым телом (ни ошибки, ни лога) — тот же
> класс, что #207–#216. Fix: хрупкий блэклист заменён на позитивную проверку:
> `MULTIPART_FORM_DATA` требует хотя бы один `@MultipartFormFile` или `@RequestParam`;
> `APPLICATION_FORM_URLENCODED` требует хотя бы один `@RequestParam`. Если ни одного нет —
> добавляется в общий агрегированный `ControllerValidationException` при создании прокси
> (`method(): MULTIPART_FORM_DATA method must have at least one parameter` /
> `method(): APPLICATION_FORM_URLENCODED method must have at least one parameter`).
> Публичный API не тронут; методы, сочетающие `@PathVariable` с параметрами тела,
> работают как раньше. TDD red-first:
> `ControllerValidationTest.rejectsFormOrMultipartWithoutBodyParametersEagerly`
> (form и multipart с одним `@PathVariable` → по 1 проблеме + eager исключение;
> сочетания `@PathVariable` с `@RequestParam` / `@MultipartFormFile` — no-false-positive)
> — падал на до-фикс коде ровно по `assertEquals(1, formProblems.size())` (список был пуст)
> → зелёный. ADR-0024 → `## Amendment #233`.
> Гейт **1245/1245** (core 1165 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures/errors, `./gradlew test javadoc`
> зелёный под `-Werror`, 0 warnings. Дальше — free-аудит (Path/File-тела метрик;
> зависимость других деклараций от окружения сборки) или концепт ADR-0061+ (HTTP-кэш
> RFC 9111 ждёт решения Антона; вопрос в mailbox).

> **Сессия #216 (free-аудит: `@MultipartFormFile` + `@RequestParam` на одном
> параметре тихо выбрасывал request-param-биндинг — 26-я «тихая» находка серии,
> поправка наблюдения #215):** свободный ход из контекста #215 (активная зона
> пуста, highest ADR 0060). #215 оставил наблюдение: пары среди четырёх
> не-hub-биндингов `{@PathVariable, @RequestParam, @RequestHeader,
> @MultipartFormFile}` «принимаются, оба биндинга срабатывают — double-binding».
> Проверил это утверждение по коду — для трёх пар верно, но **одна пара
> асимметрична**: `HttpTemplateBuilder.createMultipartParts` разрешает параметр
> цепочкой `if (p.isMultipartFile()) {…} else if (p.isRequestParam()) {…}`, т.е.
> при обеих аннотациях на одном параметре побеждает файл, а `@RequestParam`-ветка
> **не достигается**. Второго пути для `@RequestParam` на multipart-методе нет:
> `HttpTemplateBuilder.build` зовёт `createRequestParams()` только вне
> form/multipart (§280), поэтому query-строки там не собирается. Итог: значение
> `@RequestParam` **молча отсутствует** в запросе (ни ошибки, ни лога) — тот же
> класс, что #207–#215. Не покрыт ни guard'ом обратного направления #214 (файл
> требует MULTIPART), ни hub-guard'ом `@RequestBody`/`@Cookie` #215 (пара из двух
> не-hub'ов). Fix: `ControllerValidator.validateMethod` для `@MultipartFormFile` +
> `@RequestParam` на одном параметре добавляет `method(): parameter must not
> combine @MultipartFormFile with @RequestParam (only the file part is assembled,
> the @RequestParam binding is silently dropped)` в общий агрегированный
> `ControllerValidationException` при создании прокси; javadoc
> `ControllerValidator` и `@MultipartFormFile` уточнены. Публичный API не тронут;
> раздельные file- и text-field-параметры ведут себя как раньше. TDD red-first:
> `ControllerValidationTest.rejectsMultipartFormFileCombinedWithRequestParamEagerly`
> (оба аннотирования на одном параметре → 1 проблема + eager исключение;
> раздельные `@RequestParam`/`@MultipartFormFile` — no-false-positive) — красный на
> до-фикс коде ровно по `assertEquals(1, problems.size())` (список пуст) →
> зелёный. Риск ложных срабатываний проверен: ни один реальный параметр в дереве
> не несёт обе аннотации — полный прогон зелёный. ADR-0024 → `## Amendment #216`.
> Гейт **1244/1244** (core 1164 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures/errors, `./gradlew test javadoc`
> зелёный под `-Werror`, 0 warnings. Дальше — free-аудит по той же оси
> (не-hub-пары: оставшиеся сочетания `@PathVariable`/`@RequestHeader` с
> `@MultipartFormFile`/`@RequestParam` double-bind'ят — не тихий drop; либо
> форма `payloadParamCount`, считающая `@PathVariable`) или концепт ADR-0061+
> (HTTP-кэш RFC 9111 ждёт слова Антона; вопрос в mailbox).

> **Сессия #215 (free-аудит: `@RequestBody` на form-urlencoded/multipart-методе
> тихо выбрасывался — 25-я «тихая» находка серии, симметричное закрытие пары
> «payload-аннотация ⇄ requestType» из #214):** свободный ход из контекста #214
> (активная зона пуста, highest ADR 0060). Продолжил ось «асимметричный guard»
> (#214): у пары «payload-биндинг ⇄ requestType» была закрыта **одна** половина —
> `@MultipartFormFile` теперь требует `MULTIPART_FORM_DATA` (ADR-0024 #214). Вторая
> половина — `@RequestBody` — не проверялась никто. Факт: `@RequestBody`
> сериализуется JSON-кодеком и читается **только** в ветке `APPLICATION_JSON`
> (`HttpTemplateBuilder.build` → `createJsonBodyPayload`, строка ~292). На
> `APPLICATION_FORM_URLENCODED` тело собирает `createUrlEncodedBody` (только
> `@RequestParam`), на `MULTIPART_FORM_DATA` — `createMultipartParts` (только
> `@RequestParam`/`@MultipartFormFile`); ни один путь не зовёт `createBody` →
> аргумент `@RequestBody` **молча выбрасывается** из запроса (ни ошибки, ни лога).
> Проверено зондом: валидатор молчал (0 проблем). Тот же класс «тихой»
> мисконфигурации, что #207–#214. Fix: `ControllerValidator.validateMethod` для
> каждого `@RequestBody`-параметра при `requestType != APPLICATION_JSON` добавляет
> `method(): @RequestBody parameter requires requestType = APPLICATION_JSON` в
> общий агрегированный `ControllerValidationException` при создании прокси.
> `@Get` не затронут (отбивается отдельным сообщением, `resolveRequestType(@Get)`
> = JSON); нормальный JSON-путь ведёт себя как раньше. Публичный API не тронут.
> TDD red-first: `ControllerValidationTest.rejectsRequestBodyWithoutJsonRequestTypeEagerly`
> (form-urlencoded + multipart → по 1 проблеме и eager исключение; JSON-метод —
> no-false-positive) — красный на до-фикс коде ровно по
> `assertEquals(1, formProblems.size())` (список пуст) → зелёный. ADR-0035 →
> `## Amendment #215`. Наблюдение (не трогал): составной guard остаётся
> hub-центричным — пара из двух `{@PathVariable, @RequestParam, @RequestHeader,
> @MultipartFormFile}` по-прежнему принимается (оба биндинга срабатывают: это
> double-binding, а не тихий drop; хабы `@RequestBody`/`@Cookie` не тронуты).
> Гейт **1243/1243** (core 1163 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures/errors, `./gradlew test javadoc`
> зелёный под `-Werror`, 0 warnings. Дальше — free-аудит или концепт ADR-0061+
> (HTTP-кэш RFC 9111 ждёт слова Антона; вопрос в mailbox).

> **Сессия #214 (free-аудит: `@MultipartFormFile` на методе без `requestType =
> MULTIPART_FORM_DATA` тихо выбрасывал части — 24-я «тихая» находка серии,
> обратная сторона уже проверенной пары):** свободный ход из контекста #213
> (активная зона пуста, highest ADR 0060). Продолжил ось #210/#211 «парные
> декларативные конструкции»: `ControllerValidator` уже режет *обратную* пару —
> `MULTIPART_FORM_DATA`-метод без единого payload-параметра («must have at least
> one parameter») — но **прямую** пару не проверял никто:
> `@MultipartFormFile`-параметр на методе, чей `requestType` остался дефолтным
> `APPLICATION_JSON` (обычный `@Post/@Put/@Patch/@Delete`) или
> `APPLICATION_FORM_URLENCODED`. Части собираются **только** внутри ветки
> `MULTIPART_FORM_DATA` (`HttpTemplateBuilder.createMultipartParts`); во всех
> прочих ветках `parts` остаётся `null`, тело строится своим путём, а
> аргументы-файлы **молча выбрасываются** из запроса — без ошибки и без лога
> (тот же класс, что #207–#213). Проверено зондом (валидатор молчит: 0 проблем)
> и red-first тестом. Fix: `ControllerValidator.validateMethod` считает
> эффективный `RequestType` один раз и для каждого `@MultipartFormFile`-параметра
> на не-multipart методе добавляет `method(): @MultipartFormFile parameter
> requires requestType = MULTIPART_FORM_DATA` в общий агрегированный
> `ControllerValidationException` при создании прокси. Публичный API не тронут;
> корректный multipart-метод ведёт себя как раньше. TDD red-first:
> `ControllerValidationTest.rejectsMultipartFormFileWithoutMultipartRequestTypeEagerly`
> (файл на JSON-`@Post` → 1 проблема + eager исключение; тот же метод с
> `requestType = MULTIPART_FORM_DATA` — no-false-positive) — красный на до-фикс
> коде ровно по `assertEquals(1, problems.size())` (список пуст) → зелёный.
> ADR-0024 → `## Amendment #214`. Наблюдение (не трогал):
> `MethodMetadata.hasMultipartParams()` — мёртвый пакетно-приватный аксессор
> (вероятно, задумывался ровно под эту проверку). Гейт **1242/1242**
> (core 1162 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
> benchmarks 15), 0 failures/errors, `./gradlew test javadoc` зелёный под
> `-Werror`, 0 warnings. Дальше — free-аудит или концепт ADR-0061+ (HTTP-кэш
> RFC 9111 ждёт слова Антона; вопрос в mailbox).

> **Сессия #213 (free-аудит/проектное решение: `0`-таймаут был «принимаемым, но
> невыразимым» на JDK-движке — 23-я находка серии, закрытие non-goal #200):**
> свободный ход из контекста #212 (активная зона пуста, highest ADR 0060).
> Взял оставшийся хвост из #200: `connectTimeout(0)`/`socketTimeout(0)`
> валидны по ADR-0027 §3 («non-negative»), но JDK-движок отвергает
> `Duration.ZERO` — `HttpClient.Builder.connectTimeout` **на создании клиента**
> и `HttpRequest.Builder.timeout` **на первом запросе** — голым
> `IllegalArgumentException: Invalid duration: PT0S`, тогда как OkHttp/Apache5
> читают `0` как «без таймаута». Одна и та же принятая конфигурация вела себя
> по-разному, а на дефолтном движке падала поздно и непонятно. Класс тот же,
> что #184/#199/#200 («поведение зависит от движка/платформы»), но грань новая:
> не валидация входа, а **недостающая семантика легального значения**.
> Решение (архитектурное, автономия Антона): **`0` = «без таймаута» на всех
> движках.** Fix: единый `HttpTemplate.effectiveSocketTimeout(methodTimeout,
> moduleInfo)` — `@Timeout` всегда побеждает, иначе `socketTimeout == 0` →
> `null`-опция (движки не ставят дедлайн; OkHttp/Apache5 и так гейтили `> 0`);
> `JdkHttpEngine.create` применяет `connectTimeout` только при `> 0` (дефолт
> JDK — безграничный). Негативные значения по-прежнему отбиваются билдерами
> (#200), `@Timeout` — строго положительный. Javadoc `ModuleInfo`,
> `SimpleModuleInfo.Builder`, `JRestlyClient.Builder`, `HttpEngineOptions`
> уточнены. TDD red-first: 2 e2e-теста в `TimeoutWireMockTest` —
> `zeroConnectTimeoutDisablesConnectTimeout` (создание клиента + вызов) и
> `zeroSocketTimeoutDisablesRequestTimeout` (ответ с задержкой) — красные на
> до-фикс коде ровно по `Invalid duration: PT0S` (оба), зелёные после.
> ADR-0027 → `## Amendment #213`. Гейт **1241/1241** (core 1161 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> 0 failures/errors, `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.
> Дальше — free-аудит или концепт ADR-0061+ (HTTP-кэш RFC 9111 ждёт слова
> Антона; вопрос в mailbox от #208, ответа нет).
>
> **Сессия #212 (free-аудит: `name()`-fallback параметрных биндингов без флага
> `-parameters` тихо уезжал как `arg0` — 22-я «тихая» находка серии, класс
> «парная декларация» #210/#211 с другой стороны):** свободный ход из контекста
> #211 (активная зона пуста, highest ADR 0060). Ось #210 «парные декларативные
> конструкции» дала ещё одну жилу: имя-по-умолчанию параметрных биндингов. У
> `@RequestParam`/`@RequestHeader`/`@Cookie`/`@PathVariable` пустой `name()`
> резолвится в имя Java-параметра (`MethodMetadata.parse`: `name().isEmpty() ?
> p.getName() : name`), а компилятор хранит имена параметров **только под флагом
> `-parameters`** (README прямо называет требование). Без флага `Parameter
> .getName()` отдаёт синтетический `arg0` — и декларация молча ломается:
> **query-ключ/заголовок/кука уходят под именем `arg0`** (сервер их игнорирует;
> ни ошибки, ни лога), а `@PathVariable` перестаёт матчить `${...}`-плейсхолдер
> и в #210 давал голое `arg0`-сообщение без причины. Проверено зондом: интерфейс,
> скомпилированный без `-parameters`, для `@RequestParam String query` даёт **0**
> проблем валидатора — полная тишина. Тот же класс «тихой» мисконфигурации, что
> ленивый `@Retry` (#207) и blank name-bearing (#208/#209), но здесь не «забыли
> проверить атрибут», а **пропустили требование окружения сборки**. Fix:
> `ControllerValidator.validateParameterNames` — для каждого параметрного
> биндинга с пустым `name()` (и без `Map`/`HttpCookie`, у которых имена берутся
> из записей/инстанса — не флагается) проверяет `Parameter.isNamePresent()`;
> false → общий агрегированный `ControllerValidationException` при создании
> прокси (`method(): @RequestParam requires an explicit name() when the controller
> is compiled without '-parameters' …`). `@PathVariable` с недоступным fallback'ом
> исключается из mismatch-множества Amendment #210, чтобы осталось только
> actionable-сообщение, а не голый `arg0`. Публичный API не тронут; корректно
> скомпилированный контроллер (`-parameters`) ведёт себя как раньше. TDD
> red-first: `ControllerValidationTest.rejectsParameterNameFallbackWithoutParameters
> FlagEagerly` (интерфейс компилируется **в рантайме** `ToolProvider` дважды —
> без флага → 3 проблемы, с флагом → 0; `Map`-параметр и явный `name()` — 0) +
> `reportsUnnamedPathVariableWithoutParametersFlagActionably` (нет `arg0` в
> сообщениях) — красные на до-фикс коде ровно по целевым ассертам → зелёные.
> ADR-0017 → `## Amendment #212`. Гейт **1239/1239** (core 1159 + engine-okhttp 25
> + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Дальше — free-аудит
> по той же оси (приём «декларация ⇄ требование окружения сборки» — новый),
> концепт ADR-0061+ (HTTP-кэш ждёт слова Антона) или fixtures-javadoc.

> **Сессия #211 (free-аудит: `path()` глагола не проверялся на URI-легальность —
> 21-я «тихая» находка серии, прямое продолжение оси #210 «парные декларативные
> конструкции»):** свободный ход из контекста #210 (активная зона пуста, highest
> ADR 0060). #210 закрыл *пару* шаблон ⇄ `@PathVariable`, но оставил нехоженой
> **форму самой половины**: `path()` глагола (`@Get/@Post/@Put/@Patch/@Delete`)
> никогда не сверялся с тем, что `URI.create` вообще примет. Путь (или абсолютный
> URL) с URI-нелегальным символом доживает до **отправки**, где
> `HttpTemplate.createUri` → `URI.create(url)` падает голым
> `IllegalArgumentException` — без имени метода и без полного URL. Реалистичные
> ловушки: пробел (`@Get(path = "/api/ us")`), CR/LF (то же + риск инъекции в
> request-line), **Retrofit/JAX-RS-стиль `{id}`** вместо jRestly'шного `${id}`
> (`@Get(path = "/users/{id}")` — падает на `{`), битый percent-escape
> (`/api/100%` → `Malformed escape pair`). Тот же класс, что #207–#210. Fix:
> `ControllerValidator.validatePath` маскирует `${...}`-плейсхолдеры
> same-length-строкой легального символа (валидный шаблон не спотыкается,
> индексы в сообщении выровнены с исходным путём) и прогоняет результат через
> `URI.create`; любое `IllegalArgumentException` уходит в общий агрегированный
> `ControllerValidationException` при создании прокси
> (`method(): path must be a valid URI reference: <reason>`). Проверка **точно
> зеркалит** строку, которую реально скормит фреймворк (относительный путь, либо
> абсолютный URL при `startsWith("http")`), поэтому ложных срабатываний нет:
> non-ASCII символы пути, sub-delims, `%XX`, `?`/`#`, а также IPv6-скобки в
> authority (`http://[::1]/api/items` — легальны в authority, но НЕ в path)
> проходят так же, как на отправке. Публичный API не тронут; корректный путь
> ведёт себя как раньше. TDD red-first: новый
> `ControllerValidationTest.rejectsInvalidUriCharactersInPathEagerly` (пробел,
> `{id}`, битый `%` → 3 проблемы + eager исключение; `%20`/`?x=1` и IPv6-URL —
> no-false-positive) — красный ровно по `assertEquals(3, problems.size())` →
> зелёный. ADR-0025 → `## Amendment #211`. Гейт **1237/1237** (core 1157 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> 0 failures/errors, `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.
> Дальше — free-аудит по той же оси (осталось: `@ExpectStatus(statuses = {})` /
> `@FollowRedirects(count < 0)` — решение #207 «фича, не дыра») или концепт
> ADR-0061+ (HTTP-кэш ждёт ответа Антона; вопрос в mailbox от #208, ответа нет).
>
> **Сессия #210 (free-аудит: path-template ⇄ `@PathVariable` не сверялись eager'ом
> — 20-я «тихая» находка серии, новая ось «декларативная пара обязана совпадать»):**
> свободный ход из контекста #209 (активная зона пуста, highest ADR 0060). Семья
> name-bearing атрибутов закрыта (#208/#209) — свет аудита смещён на **парные
> декларативные конструкции**: путь глагола (`@Get/@Post/@Put/@Patch/@Delete`
> `path()`) и его `@PathVariable`-параметры — две половины одного контракта
> (ADR-0025), но `ControllerValidator` их никогда не сверял. Два тихих исхода:
> (a) placeholder `${name}` без `@PathVariable`-параметра — остаётся в URL, и
> `URI.create` режет его уже **на отправке** голым
> `IllegalArgumentException: Illegal character in path at index N` (проверено
> зондом), не называя ни метода, ни плейсхолдера; (b) `@PathVariable` без
> placeholder — `createUrl` делает `path.replace("${name}", value)`, для
> отсутствующего плейсхолдера это no-op → значение параметра **молча
> выбрасывается** из запроса (ни ошибки, ни лога). Тот же класс, что ленивый
> `@Retry` (#207) и blank-атрибуты (#208/#209). Fix: `ControllerValidator.
> validateMethod` извлекает `${...}` из резолвнутого пути и сверяет множеством с
> эффективными именами `@PathVariable` (явный `name()` либо имя Java-параметра
> при blank), оба расхождения — в общий агрегированный
> `ControllerValidationException` при создании прокси. Публичный API не тронут;
> корректный шаблон ведёт себя как раньше. TDD red-first: новый
> `ControllerValidationTest.rejectsPathVariableTemplateMismatchEagerly`
> (unresolved placeholder → 1 проблема; unused `@PathVariable` → 1 проблема;
> eager исключение на обоих; валидный шаблон — no-false-positive) — красный
> ровно по `assertEquals(1, unresolved.size())` → зелёный. ADR-0025 →
> `## Amendment #210`. Гейт **1236/1236** (core 1156 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Дальше — free-аудит
> по новой оси или концепт ADR-0061+ (HTTP-кэш ждёт решения Антона).
>
> **Сессия #209 (free-аудит: `@MultipartFormFile.partName = ""` тихо уезжал на
> провод как `name=""` — 19-я «тихая» находка серии, закрытие семьи name-bearing
> required-атрибутов):** свободный ход из контекста #208 (активная зона пуста,
> highest ADR 0060). Продолжил приём #207/#208 — «сверять состав семьи
> декларативных аннотаций» — до последнего незакрытого члена. `@MultipartFormFile
> .partName` — required-атрибут (без дефолта), единственная name-bearing
> аннотация **без fallback на имя Java-параметра**: `@RequestParam`/`@RequestHeader`/
> `@Cookie` в `MethodMetadata.parse` резолвят `name().isEmpty() ? p.getName() :
> name`, а `partName()` берётся как есть. Blank ничего не роняет: часть уходит с
> `Content-Disposition: form-data; name=""` — без ошибки на клиенте и без
> возможности сервера связать часть с полем формы (RFC 7578 §4.2 требует `name`).
> Тот же класс тихой мисконфигурации, что `@SetAuthDetails.headerName` (#208) и
> null `@Compress` (#190). В #208 я отложил этот пункт как «серверная семантика
> неоднозначна» — пересмотрел: отличие от сиблингов (нет fallback) делает пустое
> значение именно **дырой валидации**, а не спорной семантикой. Fix:
> `ControllerValidator.validateMethod` — blank-проверка параметра
> (`method(): @MultipartFormFile partName must not be blank`) в общий
> агрегированный `ControllerValidationException` при создании прокси; CRLF не
> дублирую (его уже громко режет канонический конструктор `MultipartPart`).
> Публичный API не тронут. TDD red-first: новый
> `ControllerValidationTest.rejectsBlankMultipartFormFilePartNameEagerly`
> (blank → 1 проблема + eager исключение; валидное имя — no-false-positive) —
> красный ровно по `assertEquals(1, problems.size())` → зелёный. ADR-0024 →
> `## Amendment #209`. Гейт **1235/1235** (core 1155 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Семья name-bearing
> атрибутов закрыта полностью. Дальше — free-аудит или концепт ADR-0061+
> (HTTP-кэш ждёт решения Антона — вопрос в mailbox от #208).
>
> **Сессия #208 (free-аудит: `@SetAuthDetails(headerName = "")` тихо отключал
> захват креды — 18-я «тихая» находка серии, прямое продолжение #207):** свободный
> ход из контекста #207 (активная зона пуста, highest ADR 0060). Продолжил приём
> #207 — сверка «семьи декларативных аннотаций» на предмет, у всех ли членов
> семейства eager-валидация. Взгляд упал на **name-bearing** аннотации:
> `@Trace.header`, `@Cookie.name`, `@RequestHeader.name`, `@CircuitBreaker.name`,
> `@RateLimiter.name` — все валидируют blank; **`@SetAuthDetails.headerName`
> (required-атрибут без дефолта) — единственная без проверки**. Blank-имя не падает:
> `AuthProvider.captureAuthDetails(headerName, …)` фильтрует заголовки ответа через
> `headerName.equalsIgnoreCase(header.name())`, что не совпадает ни с чем →
> токен **никогда не захватывается**, клиент бесконечно шлёт устаревшую/пустую
> креду. Тот же класс «тихого no-op», что null `@Compress` (#190) и ленивый
> `@Retry` (#207). Fix: `ControllerValidator.validateMethod` получил blank-проверку
> (`method(): @SetAuthDetails headerName must not be blank`) в общий агрегированный
> `ControllerValidationException` при создании прокси; публичный API не тронут,
> корректно названный захват работает как раньше. TDD red-first: новый
> `ControllerValidationTest.rejectsBlankSetAuthDetailsHeaderNameEagerly`
> (blank → 1 проблема + eager `ControllerValidationException`; валидное имя —
> no-false-positive) — красный ровно по `assertEquals(1, problems.size())` → зелёный.
> ADR-0046 → `## Amendment #208`. Гейт **1234/1234** (core 1154 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors,
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Не тронуты (наблюдение):
> `@MultipartFormFile.partName` (required, blank → part с пустым `name=""`, но
> серверная семантика неоднозначна — не «дыра»), `@ExpectStatus`/`@FollowRedirects`
> (инварианты нигде не объявлены — фича, #207). Дальше — free-аудит или концепт
> ADR-0061+.
>
> **Сессия #207 (free-аудит: невалидный `@Retry` проскакивал eager-валидацию
> контроллера — 17-я «тихая» находка серии):** свободный ход из контекста #206
> (активная зона пуста). Проверял «дублирующие входные пути» (#200) на уровне
> декларативных аннотаций и нашёл дыру в **контракте `ControllerValidator`**:
> инварианты `@Retry` (`times >= 1`, `multiplier >= 1.0`,
> `initialBackoffMs <= maxBackoffMs`, `statuses ∈ [100,599]`) живут **только** в
> `RetryPolicy.Builder.build()`, который вызывается лениво в
> `MethodMetadata.parse` на **первом вызове**. Все прочие декларативные
> аннотации с инвариантами валидируются eager'ом в `ControllerValidator`
> (`@Timeout`, `@Compress`, `@Trace`, `@CircuitBreaker`, `@RateLimiter`,
> `@Accept`, `@ContentType`, `@Cookie`, `@OnError`) — с агрегированным
> actionable-сообщением при создании прокси, так что `validateControllers(true)`
> (дефолт) отклоняет контроллер заранее. `@Retry` был **единственной дырой**:
> битая декларация проходила валидацию, прокси создавался, и только первый
> вызов падал голым `IllegalStateException` из недр парсинга метаданных вместо
> `ControllerValidationException` с именем метода и поля. Fix: новый
> `ControllerValidator.validateRetry` зеркалит правила билдера (нелегальные
> `times`, отрицательный/инвертированный backoff, `multiplier < 1.0`,
> out-of-range status — всё в один список, с префиксом `method():`), вызван из
> `validateMethod`; `maxRetryAfterMs` намеренно не проверяется (сохраняет
> конвенцию `-1`/отрицательное = unset). Поведение валидной политики не
> изменилось. TDD red-first: новый
> `ControllerValidationTest.rejectsInvalidRetryConfigurationEagerly` (4 целевых
> проблем + eager `ControllerValidationException` при `client.get(...)` +
> no-false-positive на валидной политике) — красный на до-фикс коде (список
> проблем пуст) → зелёный. ADR-0040 → `## Amendment #207`. Гейт **1233/1233**
> (core 1153 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
> benchmarks 15), 0 failures/errors, `./gradlew test javadoc` зелёный под
> `-Werror`, 0 warnings. Non-goals (наблюдение, не трогал): `@ExpectStatus` и
> `@FollowRedirects` тоже не валидируются, но их инварианты нигде не объявлены
> (нет «дыры», а новая фича — отдельное решение). Дальше — free-аудит или
> концепт ADR-0061+.
>
> **Сессия #206 (гигиена: закрытые эпики ADR-0058/0059/0060 дистиллированы в
> `ROADMAP.md`):** свободный ход из контекста #205 (все кандидаты закрыты).
> Взят перманентный пункт «гигиена проекта»: `ROADMAP.md` обрывался на
> ADR-0057 и держал лишь заглушку-«status update» про 0058/0059/0060. Заменена
> на три полноценные секции в стиле роадмапа (problem/solution/verification/
> gates + ссылка на source-of-truth): ADR-0058 (внешние движки: `engine-okhttp`
> #150–#156 с фиксом дедлока `PublisherInputStream` и пассивным транспортом,
> `engine-apache5` #157, `BENCHMARKS.md` #158, Finding #158 → #159),
> ADR-0059 (SSE-lifecycle Ф1 #161 + стриминговые трейлеры Ф2 #163 + stall-guard
> Ф3 #175), ADR-0060 (pluggable payload formats Ф1 #178 + `codec-jackson-xml`
> Ф2 #179 + ServiceLoader-автоподбор Ф3 #180). Добавлен блок «Current status
> (2026-10-17)»: все 4 кандидата закрыты, highest ADR **0060**, next free
> **0061**, гейт **1232/1232**. Заявление роадмапа проверено по факту
> (test-results XML): ровно **1232/1232** (core 1152 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), 0 failures/errors;
> `./gradlew test javadoc` BUILD SUCCESSFUL. Код не менялся — только docs +
> память. Дальше — свободный ход (ADR-0061+ концепт или свежий аудит).
>
> **Сессия #205 (кандидат №1 test-javadoc, батч №6 — добит корневой `ru.jrestly`
> и **кандидат №1 закрыт**):** свободный ход из контекста #204. Выбран финальный
> батч: class-level javadoc в **25** оставшихся корневых тест-классах
> `ru.jrestly` — конфигурация/протокол (`AcceptContentTypeWireMockTest`,
> `ArrayParameterWireMockTest`, `CollectionFormatWireMockTest`,
> `PathVariableEncodingTest`, `PathVariableEncodedTest`), resilience
> (`CircuitBreakerWireMockTest`, `RateLimiterWireMockTest`), метрики
> (`ClientMetricsWireMockTest`), cookies (`CookieConfigAndResponseTest`,
> `CookieSessionWireMockTest`), редиректы (`CrossOriginRedirectTest`),
> интерцепторы (`DeclarativeInterceptorWireMockTest`,
> `InterceptorAnnotationTest`), окружение (`ExecutorConfigTest`, `HttpsSslTest`,
> `VirtualThreadAndExecutorWireMockTest`), multipart (`MultipartTest`,
> `MultipartFlexibilityWireMockTest`), `@OnError` (`OnErrorConfigTest`,
> `OnErrorWireMockTest`), кодек-SPI (`PayloadCodecWireMockTest`,
> `ZeroDepStandaloneWireMockTest`), прокси (`ProxyIntegrationWireMockTest`),
> стриминг тела (`RequestBodyStreamingWireMockTest`), charset
> (`ResponseCharsetWireMockTest`). Затем — **8** оставшихся «настоящих»
> тест-классов вне корневого пакета: `engine` (`CustomHttpEngineIntegrationTest`,
> `HttpEngineResolverTest`, `JdkHttpEngineTest`), `json` (`NoOpJsonCodecTest`),
> субмодуль `codec-jackson-xml` (`JacksonXmlCodecTest`,
> `JacksonXmlWireMockTest`), `benchmarks` (`BenchmarkHttpServerTest`,
> `BenchmarkSmokeTest`) — итого **33 класса**. Только doc-комментарии, логика не
> тронута. **Кандидат №1 закрыт:** во всём дереве без class-level javadoc
> остались только **15** вспомогательных типов `ru.jrestly.fixtures`
> (контроллеры/DTO/провайдеры для тестов — не тест-классы, осознанный non-goal).
> Замер-скан: все `@Test`-классы корня, субмодулей `engine-okhttp`/`engine-apache5`/
> `codec-jackson-xml`/`benchmarks` — 0 без doc. `./gradlew test javadoc` —
> **1232/1232** (core 1152 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), 0 failures; javadoc-гейт (`-Werror`,
> только `src/main`) не затронут, 0 warnings.
>
> **Сессия #204 (кандидат №1 test-javadoc, батч №5 — корневой `ru.jrestly`,
> 19 тест-классов):** свободный ход из контекста #203. Продолжил корневой
> пакет: лаконичный class-level javadoc (стиль батчей №1/#185 … №4/#203:
> 4–6 строк, «что покрывает + unit/e2e + ADR», без пересказа тестов) в **19**
> классах — кодеки/JSON (`JacksonCodecPrettyPrintTest`, `JsonCodecResolverTest`,
> `JsonCodecWireTest`), валидация (`AcceptContentTypeValidationTest`,
> `ClientMetricsValidationTest`, `CollectionFormatValidationTest`,
> `ControllerValidationTest`, `CookieValidationTest`,
> `DownloadProgressValidationTest`, `UploadProgressValidationTest`),
> прогресс/e2e (`DownloadProgressWireMockTest`, `UploadProgressWireMockTest`),
> стриминг (`ResponseStreamingTest`, `ServerSentEventsAndLineStreamingTest`),
> сжатие/трассировка (`RequestCompressionWireMockTest`,
> `RequestTracingWireMockTest`), прокси/auth (`ProxyCacheConcurrencyTest`,
> `ProxyConfigTest`, `AuthTest`). Только doc-комментарии, логика не тронута.
> Замер: 83 файла корневого `ru.jrestly`, без class-level javadoc было 44 →
> стало **25** (общий счёт по дереву: 44 — корневой 25 + фикстуры 15 + `engine`
> 3 + `json` 1). `./gradlew test javadoc` — **1232/1232**, javadoc-гейт
> (`-Werror`, только `src/main`) не затронут, 0 warnings.
>
> **Сессия #203 (кандидат №1 test-javadoc, батч №4 — корневой `ru.jrestly`,
> 18 тест-классов):** свободный ход из контекста #202. Продолжил батч:
> наведён лаконичный class-level javadoc (стиль батчей №1/#185, №2/#201,
> №3/#202: 4–6 строк, «что покрывает + unit/e2e + ADR», без пересказа тестов)
> в **18** корневых тест-классах — глаголы/редиректы/статусы/таймауты/ядро:
> `GetMethodTest`, `PostMethodTest`, `PutMethodTest`, `PatchMethodTest`,
> `DeleteMethodTest`, `RedirectTest`, `RedirectMethodTest`, `RedirectNoFollowTest`,
> `StatusHandlingTest`, `ErrorHandlingTest`, `TimeoutConfigTest`,
> `TimeoutWireMockTest`, `RequestBodyValidationTest`, `RequestHeadersTest`,
> `StaticHeadersAndParamsTest`, `ClientLoggerConfigTest`,
> `AbstractHttpClientLifecycleTest`, `BaseWireMockTest`. Только doc-комментарии,
> логика не тронута. Замер: 168 тест-классов в корневом `src/test/java`, без
> class-level javadoc осталось **63** — из них корневой `ru.jrestly` (следующий
> батч, ещё 44) и вспомогательные `ru.jrestly.fixtures` 15 (не тест-классы) +
> `engine` 3, `json` 1. `./gradlew test javadoc` — **1232/1232**, javadoc-гейт
> (`-Werror`, только `src/main`) не затронут, 0 warnings.
>
> **Сессия #202 (кандидат №1 test-javadoc, батч №3 — `ru.jrestly.http`,
> 18 тест-классов):** свободный ход из контекста #201. Продолжил запланированный
> батч: наведён лаконичный class-level javadoc (стиль батчей №1/#185 и №2/#201:
> 4–6 строк, «что покрывает + unit/e2e + ADR», без пересказа тестов) во **всех
> 18** тест-классах пакета `ru.jrestly.http` — `WireRequestTest`, `OnErrorParserTest`,
> `MethodMetadataTest`, `HttpTemplateUriTest`, `HttpTemplateCompressionTest`,
> `ResponseHeadersTest`, `ZeroGarbagePipelineTest`, `WireResponseTrailersTest`,
> `ProgressBodyPublisherTest`, `ResponseHeadersPopulateTrailersTest`,
> `StreamingTrailersTest`, `HttpTemplateTimeoutPrecedenceTest`,
> `ProgressListenerResolutionTest`, `AuthHeaderProviderTest`,
> `MultipartPartFlexibilityTest`, `InterceptorResolutionTest`,
> `ProgressBodySubscriberTest`, `ExecutorResolutionTest`. Только doc-комментарии,
> логика не тронута. Пакет `ru.jrestly.http` **закрыт** (0 без doc). Замер:
> 168 тест-классов в корневом `src/test/java`, без class-level javadoc осталось
> **81** — из них `ru.jrestly` 62 (корневой, следующий батч), `ru.jrestly.fixtures`
> 15 (вспомогательные типы, не тест-классы), `engine` 3, `json` 1.
> `./gradlew test javadoc` — **1232/1232**, javadoc-гейт (`-Werror`, только
> `src/main`) не затронут, 0 warnings.
>
> **Сессия #201 (кандидат №1 test-javadoc, батч №2 — 14 тест-классов в
> 5 пакетах):** свободный ход из контекста #200. Сначала дожал эвристику
> #200 («дублирующие входные пути») на оставшихся кандидатах: `int` ×
> `Duration`-перегрузки в `RateLimiterConfig`/`CircuitBreakerConfig`
> (`resilience`) — чисто (валидация в каноничном конструкторе record'а,
> сеттеры без собственной проверки и не должны её иметь — fail-fast в
> `build()`); `TracePropagators`/`TraceIdGenerators` — чисто (все входы
> проверяются). Свет аудита исчерпан → запланированный кандидат №1. Наведён
> лаконичный class-level javadoc (стиль батча №1: 4–6 строк, «что покрывает +
> unit/e2e + ADR») в `ru.jrestly.cookie` (`CookiePersistenceUnitTest`,
> `PersistentCookieWireMockTest`), `ru.jrestly.codec` (`CodecRegistryTest`,
> `PayloadCodecResolverTest`), `ru.jrestly.compression` (`CompressConfigTest`,
> `CompressionAlgorithmTest`, `ContentCompressorTest`), `ru.jrestly.tracing`
> (`TraceConfigTest`, `TraceContextTest`, `TracePropagatorTest`),
> `ru.jrestly.interceptor` (`InterceptorConfigurationTest`, `RequestContextTest`,
> `ShortCircuitExecutionTest`, `ShortCircuitInterceptorTest`) — **14 классов**.
> Только doc-комментарии, логика не тронута. Замер: 157 тест-классов, без
> class-level javadoc осталось **88** (в основном `ru.jrestly` 62 и
> `ru.jrestly.http` 18) — батч №3 → `http` или корневой `ru.jrestly`.
> `./gradlew test javadoc` — **1232/1232**, javadoc-гейт (`-Werror`) не
> затронут, 0 warnings.
>
> **Сессия #200 (свободный аудит: `connectTimeout(int)`/`socketTimeout(int)` без
> fail-fast валидации — 16-я «тихая» находка, класс #190 «заявленная защита
> отсутствует»):** свободный ход из контекста #199; вместо долгого среза взял
> точечную сверку «дублирующих входных путей». Под фонарём — `SimpleModuleInfo.Builder`:
> его `Duration`-перегрузки таймаутов валидируют вход (`Objects.requireNonNull`
> + `isNegative()` → IAE, ADR-0027 §3 «non-null and non-negative checks»), а
> **примитивные** `connectTimeout(int)`/`socketTimeout(int)` не проверяли ничего
> и клали значение как есть. Отрицательный таймаут доезжал до транспорта, и
> поведение расходилось **по движку при одной и той же конфигурации**:
> `engine-apache5` под `if (timeout > 0)` молча **выключал** таймаут (поток мог
> ждать бесконечно — тот же класс риска, что #184/#199);
> `engine-okhttp`/JDK бросали `IllegalArgumentException` (OkHttp `checkDuration`,
> JDK `HttpRequest.Builder.timeout`) — но поздно, при создании клиента/первом
> запросе, с невнятным сообщением, а не при конфигурации. Fix — int-перегрузки
> получили ту же fail-fast проверку (`< 0` → IAE `… must not be negative`),
> фасад `JRestlyClient.Builder` наследует её делегированием; javadoc обеих пар
> уточнён. Осознанный non-goal (задокументирован в ADR): `0` остаётся валидным
> (§3 говорит «non-negative»), но на JDK-движке `Duration.ZERO` неприемлем
> (`HttpRequest.Builder.timeout` его отвергает) — смена семантики нуля — это
> отдельное проектное решение, не пробел валидации. TDD red-first: +1 тест в
> `TimeoutConfigTest` (int-перегрузки кидают IAE на отрицательных, позитивные
> по-прежнему принимаются, фасад наследует) — красный на старом коде ровно по
> `assertThrows` → зелёный. Гейт **1232/1232** (core 1152 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), `./gradlew test
> javadoc` зелёный под `-Werror`, 0 warnings. ADR-0027 → `## Amendment #200`.
> Дальше — test-javadoc батч №2 (`cookie`/`codec`/`compression`/`tracing`/
> `interceptor`) или тот же «дифф дублирующих входных путей» (int × Duration
> перегрузки, движки × ядро).
>
> **Сессия #199 (free-аудит: `Jitter.DECORRELATED` в `ReconnectPolicy` снимал
> `maxBackoff`):** свободный ход из контекста #198 (фонд «ADR против кода» по
> принятым ADR'ам пройден — выбран аудит жизненного цикла/мутабельности). Под
> фонарём оказался `ServerSentEventReconnector` (брошенный watchdog — уже
> задокументированный non-goal ADR-0059 §3, не трогал) и его политика
> задержек. Найден **реальный рассинхрон кода с нормативным пунктом Ф1**
> («задержка … опционально экспоненциальный рост с `Jitter` … и `maxDelay`»)
> и с javadoc самого `Jitter.DECORRELATED` (`min(maxBackoff, …)`):
> `ReconnectPolicy.nextDelayMillis` строил «пол» декорализованного джиттера из
> серверного `retry:` **до** обрезки потолком (`base = serverRetryMillis`, не
> клампится) и при `range = ceiling - base <= 0` возвращал `base` как есть.
> Следствие: `retry: 60000` при потолке 30 с парковал поток потребителя на всё
> запрошенное время — тот же класс, что #184 («некапнутый Retry-After»), но в
> джиттере реконнекта; `NONE`/`EQUAL`/`FULL` соблюдали кап (тянули из
> клампнутого `bound`), расходился только `DECORRELATED`. Fix — пол клампится
> (`min = min(max, base)`), розыгрыш из `[min, ceiling]`, `Math.min(max, min +
> draw)` — зеркально уже безопасной ветке `RetryPolicy.nextDelayMillis`
> (ADR-0040). TDD red-first: +2 теста (`decorrelatedJitterCappedByMaxBackoff`
> — юнит-seam; `serverRetryAboveMaxBackoffDoesNotUncapReconnect` — сквозной,
> `retry: 600` + `maxBackoff 100 мс`, `ReconnectInfo.delay() <= 100`), красные
> на старом коде ровно по целевым ассертам (`60000 > 1000`, `600 > 100`) →
> зелёные. ADR-0059 → Amendment #199. Гейт **1231/1231** (core 1151 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Дальше —
> test-javadoc батч №2 (`cookie`/`codec`/`compression`/`tracing`/`interceptor`)
> или свободный аудит (`ModuleInfo`/`SimpleModuleInfo` — геттеры чисты,
> `getEnvironment()` всегда свежий — задокументировано; `Environment`
> `get/setAuthDetails` фактически не используются в main).
>
> **Сессия #198 (аудит: ADR-0035 целиком — ЧИСТЫЙ срез, пять нормативных
> стыков закреплены предохранителями):** 17-й срез нити «ADR против кода»,
> свет ADR-0035 (стриминг тела запроса) — последний нехоженый ADR из
> принятого фонда. §1–§4 сверены по коду: все восемь типов тела
> (`Flow.Publisher<ByteBuffer>`/`<String>`, `BodyPublisher`, `InputStream`,
> `Supplier<InputStream>`/`<BodyPublisher>`/`<Flow.Publisher>`, `Path`, `File`)
> проводятся через `createJsonBodyPayload`; §2 — явный `@ContentType` чтится,
> стриминговые типы падают на `application/octet-stream`, `String`/`byte[]`/
> POJO — на `requestType.mime()`; §3 — supplier переживает retry и 307/308
> (`nextHop` сохраняет `bodyPublisherSupplier`), `logRequest` пишет
> `<streaming body>` не трогая живой поток; §4 — `ControllerValidator` режет
> множественный `@RequestBody`, комбинации и `@Get`-тело. Рассинхронов нет —
> **чистый срез** (третий за серию после #192/#197). Непокрытые стыки → +5
> предохранителей: (1) `Supplier<BodyPublisher>` пересепляется на каждый retry
> (503→200, 2 вызова); (2) `Supplier<Flow.Publisher<ByteBuffer>>` — свежий
> publisher; (3) 307-редирект переигрывает стриминговый supplier на втором
> хопе; (4) `File`-тело — известный `Content-Length` + дефолт octet-stream;
> (5) `logRequest` пишет плейсхолдер и **не** вызывает supplier. Все зелёные —
> структурная чувствительность. Два дрейфа формулировок задокументированы без
> правки кода: §2 ссылается на `@Post(contentType=…)`, хотя атрибут зовётся
> `requestType` (+`@ContentType`); §3 «content length when available» — длину
> нельзя узнать, не подняв supplier, что сам же §3 запрещает. Non-goal: `null`
> стриминговый аргумент → Content-Type = `requestType.mime()`, а не
> octet-stream (объявленный тип параметра не читается для null-случая). ADR-0035
> → Amendment #198, проверен и закрыт. Гейт **1229/1229** (core 1149 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Дальше — free-аудит
> (`ModuleInfo`/`SimpleModuleInfo` мутабельность, `ServerSentEventReconnector`
> жизненный цикл) или test-javadoc батч №2 (`cookie`/`codec`/`compression`/
> `tracing`/`interceptor`).
>
> **Сессия #197 (аудит: ADR-0038 × ADR-0059 — ЧИСТЫЙ срез, три не покрытых
> теста стыка закреплены предохранителями):** 16-й срез нити «ADR против
> кода» — свет ADR-0038 (HTTP/2/трейлеры) × ADR-0059 (стриминг-трейлеры).
> ADR-0038 §1–§6 сверён полностью: модель `WireResponse` (version/trailers/
> supplier, совместимые конструкторы, equals без supplier), захват
> транспорта — все три движка несут version в обоих контурах (buffered и
> streaming), иерархия `@HttpVersion` (метод > sub > super; направление
> цикла корректно — `collectInterfaces` предпорядковый), `ResponseHeaders`
> (version/isHttp2/isHttp1/trailers/firstTrailer/trailerValues/
> declaredTrailers, case-insensitive, capture-forwarder'ы),
> `CallMetrics.version()`, `logResponse`-протокол, `handleDecompression`
> (все три компонента на всех ветках), `processResponse`-прокидка. ADR-0059
> Ф2 сверён: CAS-хук EOF+close, sync+async (тот же хвост), движки
> (OkHttp/Apache5 ленивые, JDK — платформенно пусто), `populateTrailers`
> (synchronized, идемпотентен). `CURRENT_CAPTOR` — ThreadLocal, но javadoc
> «synchronous» — осознанная граница. Рассинхронов нет — **чистый срез**
> (второй за серию после #192). Непокрытые стыки → +2 предохранителя в
> `StreamingTrailersTest` (синтетический `GzipStreamingEngine`):
> (1) стриминг × прозрачный gzip × трейлеры — поправка #163 утверждает
> «hook сохраняется при стриминговой декомпрессии», впервые проверено
> (payload распакован, supplier 1× на EOF, значения на том же holder);
> (2) пара Ф2.4 «declaredTrailers() — сразу, значения — после дрена»
> зафиксирована вместе (до чтения: version/declared, trailers пуст; после:
> значения + case-insensitive lookup); (3) version на стриминговом пути
> (e2e-версионные тесты были только буферные). Оба зелёные —
> предохранители; чувствительность структурная. ADR-0038 проверен и
> закрыт; ADR-0059 → Amendment #197. Гейт **1224/1224** (core 1144 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
> 15), `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Дальше —
> ADR-0035 целиком (контракт BodyPublisher: retry-совместимость, progress ×
> streaming, nullable publisher) или free-аудит (`ModuleInfo`/
> `SimpleModuleInfo` мутабельность, `ServerSentEventReconnector` жизненный
> цикл) или test-javadoc батч №2.
>
> **Сессия #196 (аудит: ADR-0036 × ADR-0046 — закрыт non-goal #194: номера
> попыток в `onAttemptComplete`/`onRetry` стали сквозными, per-call):**
> 15-й срез нити «ADR против кода» — на этот раз доведение до конца
> отложенного. После #194 `attempts()` суммирует обе фазы 401-рефреша, но
> слушательские номера оставались пер-фазовыми: для 503 → 401 → 200
> `attempts() == 3`, а третий `onAttemptComplete` нумеровался **1** —
> последний номер ≠ `attempts()`, индексы попыток не уникальны внутри
> вызова. Fix: `attemptOffset` (число передач первой фазы) прокинут через
> оба контура — `executeWithRetries`/`scheduleRetry` (sync) и
> `executeAsyncWithRetries`/`attemptAsync`/`scheduleAsyncRetry` (async);
> слушательские номера = `attempt + offset`. Окно retry-политики, backoff и
> DEBUG-лог `Attempt N/M` остались пер-фазовыми (`maxAttempts` ограничивает
> каждую фазу, refresh-фаза начинает backoff с нуля); внутренние счётчики
> (`attemptsHolder`/`attemptsRef`) не тронуты. Javadoc `@param attempt`
> `onAttemptComplete`/`onRetry` — сквозная нумерация + инвариант «последний
> номер вызова = `attempts()`». TDD behavior-red: 4 красных ровно по целевым
> ассертам (expected 2/3 but was 1; sync ×2, async ×2) → зелёный. +1 e2e
> `refresh401AfterRetriesContinuousNumbersAsync` (async 503→401→200: 1, 2, 3,
> onRetry attempt=1, `attempts()==3`), 3 теста #194 усилены sequence-ассертами
> (sync 1,2; sync 1,2,3; async 1,2). Гейт **1222/1222** (core 1142 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
> 15), `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. ADR-0036
> → Amendment #196; non-goal #194 помечен resolved. Свет ADR-0036 выжат
> (#193 bytes × стриминг, #194 attempts × refresh, #195 attempts ×
> short-circuit, #196 номера × refresh); остался non-goal #193 (Path/File-тела
> — -1; кандидат на engine-reported byte accounting). Дальше — ADR-0038
> (HTTP/2/трейлеры) или ADR-0035 целиком.
>
> **Сессия #195 (аудит: ADR-0036 × ADR-0047 — short-circuit скрывал нулевой
> счётчик попыток: `attempts()` клэмпился до 1):** 14-й срез нити «ADR против
> кода» — стык ADR-0036 (метрики) × ADR-0047 (short-circuit), кандидат (c) из
> заметок #193 подтверждён. ADR-0047 «Network Bypass» прямо говорит: «Number
> of network attempts is tracked as 0» — во внутренних счётчиках так и есть
> (`attemptsHolder[0] = 0` / `attemptsRef.set(0)`), но слушатель видел
> `attempts() == 1`: `notifyCallComplete` клэмпил значение
> `Math.max(1, attempts)`, чтобы пережить компактный конструктор
> `SimpleCallMetrics`, отбрасывавший 0. SPI противоречил самому себе в рамках
> одного вызова: `attempts() == 1` при **нулевом** числе `onAttemptComplete`
> (передач на провод не было) — и `onCallStart` срабатывал, хотя javadoc
> обещает «before the initial request is initiated», а запрос на провод не
> уходил. Зацепка, что срез нечист, нашлась в старом тесте
> `shortCircuitInvokesResponseInterceptorAndMetrics`: display-name уже гласил
> «with attempts=0» (по ADR-0047), а ассерт держал 1 (по коду). Fix: честный
> счётчик — `attempts() == 0` для short-circuit (совпадает с числом
> `onAttemptComplete`), иначе ≥ 1; `SimpleCallMetrics` принимает `attempts >=
> 0` (ослабление валидации — обратно совместимо); javadoc `attempts()` и
> `onCallStart` уточнены (start бьётся 1:1 с complete — в том числе при
> short-circuit, gauge-пары не рвутся). Circuit breaker 0 уже обрабатывал
> (`attempts > 0`, async — `shortCircuited == null`). TDD red-first: 3 красных
> ровно по целевым ассертам (expected 0 but was 1; IAE на 0) → зелёный. +1
> тест `shortCircuitMetricsLifecycle` (sync + async: start×1,
> attempt×0, complete×1, attempts()==0, isRetried()==false), 2 ассерта
> обновлены (e2e 1→0; unit: 0 — валидно, -1 — IAE). Гейт **1221/1221**
> (core 1141 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
> benchmarks 15), `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.
> ADR-0036 → Amendment #195. Дальше — ADR-0038 (HTTP/2/трейлеры) или
> ADR-0035 целиком; не тронуты: пер-фазовая нумерация `onAttemptComplete` в
> refresh-фазе (non-goal #194) и `Math.max(1, …)`-аналог в ветке
> interceptor-threw (там «вызов состоялся» — 1 осознанно).

> **Сессия #194 (аудит: ADR-0036 × ADR-0046 — прозрачный 401-рефреш затирает
> счётчик `attempts()`):** тринадцатый срез нити «ADR против кода» — 12-я
> «тихая» находка, кандидат (a) из заметок #193 подтверждён на обоих контурах.
> ADR-0036 §2 обещает `attempts()` — «total attempts executed», а 401-рефреш
> (ADR-0046) — это **две** фазы передачи: первый `executeWithRetries`/
> `executeAsyncWithRetries` и второй — после `tokenManager.invalidate()`. Фазы
> делили один счётчик (`int[] attemptsHolder` на sync-контуре, `AtomicInteger
> attemptsRef` на async), и вторая фаза **затирает** первую: слушатель видел
> `attempts() == 1` и `isRetried() == false` для вызова, физически ушедшего на
> провод дважды; с `@Retry` ещё хуже — 503 → 401 → 200 отчитывался как 1
> вместо 3. Fix: фазы суммируются — sync: значение первой фазы сохраняется
> перед вторым `executeWithRetries` и прибавляется в holder после; async:
> значение берётся в 401-ветке и в `whenComplete` второй фазы (успех **или**
> сбой) прибавляется через `accumulateAndGet` до того, как терминальные стадии
> читают `attemptsRef` (порядок зависимостей CFE: свой `whenComplete`
> навешивается раньше продолжения `thenCompose`). Javadoc `CallMetrics.attempts()` уточнён
> (включая refresh-фазу, не менее 1). TDD: behavior-red (3 e2e-теста, на
> старом коде — ровно по целевым ассертам: expected 2/3 but was 1,
> `isRetried()` false) → зелёный. +3 теста в `ClientMetricsWireMockTest`
> (новый интерфейс `RefreshMetricsClient`; sync 401→200; sync 503→401→200 = 3
> с по-одному `onAttemptComplete` на передачу и одним `onRetry`; async
> 401→200). Гейт **1220/1220** (core 1140 + engine-okhttp 25 + engine-apache5
> 25 + codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный
> под `-Werror`, 0 warnings. ADR-0036 → Amendment #194 (non-goal задокументирован:
> номера `attempt` в `onAttemptComplete`/`onRetry` стартуют с 1 в refresh-фазе
> — per-phase, а не per-call; кандидат на отдельный срез). Дальше — ADR-0038
> (HTTP/2/трейлеры) или ADR-0035 целиком; мелкий кандидат (b) из #193 не тронут:
> `onCallStart` при short-circuit.

> **Сессия #193 (аудит: ADR-0036 × ADR-0035 — метрики материализовали
> стриминг-тело лишний раз):** двенадцатый срез нити «ADR против кода» —
> впервые не «формулировка ADR vs код», а стык двух ADR'ов. `@WithMetrics`-
> слушатель активировал `computeRequestBytes`, который для замера
> `requestBytes()` вызывал `bodyPublisherSupplier().get()` — для
> ADR-0035 `Supplier`-тел это **лишний вызов продающего пользователя на
> логический вызов** (после реальной отправки, в момент call-complete), с
> `catch (Throwable ignored)`. Для `Supplier<InputStream>` замер всё равно
> `-1` (`ofInputStream().contentLength()` по определению неизвестен) —
> двойная материализация ради нуля, против контракта «supplier вызывается по
> числу отправок» (держался только `testUploadSupplierAcrossRetry` без
> метрик) и против ADR-0035 §3 (hot-путь не должен трогать живые стримы;
> `logRequest` пишет `<streaming body>` без supplier'а). Fix: supplier-тела →
> `-1` (unknown), как и multipart; `byte[]`-тела — точный замер; supplier не
> трогаем. `computeRequestBytes`/`computeResponseBytes` — package-private
> seam (паттерн #190/#191), Javadoc `CallMetrics.requestBytes()` уточнён,
> ADR-0036 → Amendment #193 (non-goal задокументирован: Path/File-тела —
> `-1` вместо размера файла; кандидат на engine-reported byte accounting).
> TDD: compile-red (stash → 8 ошибок) → behavior-red (e2e: supplier 2×
> вместо 1×; unit: 1 вызов вместо 0) → зелёный. +7 тестов (6 unit в новом
> `HttpTemplateMetricsBytesTest` + e2e `testUploadSupplierWithMetricsListener`),
> гейт **1217/1217** (core 1137 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный
> под `-Werror`, 0 warnings. ADR-0036 проверен по §1–§6 (интерфейс/модель/
> аннотация/конфигурация/валидация/fault-tolerance — чист); следующий свет —
> ADR-0038 (HTTP/2/трейлеры) или ADR-0035 целиком (контракт BodyPublisher,
> retry-совместимость, progress); мелкие кандидаты: `attempts()` в потоке
> 401-refresh (второй `executeWithRetries` затирает счётчик первого — сумма
> не считается), `onCallStart` при short-circuit (запрос на провод не уходил).

> **Сессия #192 (аудит: ADR-0034 — query-коллекции: срез ЧИСТ, границы `DEFAULT`-наследования зафиксированы
> предохранителями):** одиннадцатый срез нити «ADR против кода» — и впервые
> **чистый** за всю серию. Все нормативные пункты ADR-0034 §1–§7 сверены по
> коду: enum/аннотации/таргеты (§1–§3) совпадают; иерархия резолвинга
> `parseDefaultCollectionFormat` (метод → иерархия child→parent → EXPLODE,
> `collectInterfaceHierarchy` предпорядковый обход + обратный цикл — порядок
> «от потомка к предку» корректен) совпадает с §4; значения (§5): скаляры —
> всегда `param=value`, коллекции/не-byte-массивы фильтруются по null, пустые
> пропуском, dynamic-map — через тот же `appendParamValues` с эффективным
> форматом; form/multipart-симметрия (§6) — `createUrlEncodedBody` дёргает те
> же `createRequestParams`, multipart BRACKETS/CSV/SSV/TSV/PIPES в
> `appendMultipartCollectionParts` — один part или `[]`-имена; валидация
> (`ControllerValidator`, тип + метод, рекурсия по иерархии) — §7; SSV
> `+`→`%20` нормализацией `HttpTemplate.encode`. Границы, которые срез не
> покрывал тестами: (1) `@QueryCollectionFormat(DEFAULT)` на методе — наследует
> интерфейс (§1 «inherits»); (2) `format = DEFAULT` на параметре — фолбэк на
> формат метода; (3) трёхуровневая иерархия child-first (раньше только
> двухуровневая); (4) пустая коллекция пропуском **из form-тела** (§5
> «omitted from query/form»). +4 предохранителя в
> `CollectionFormatUriResolutionTest` (зелёные на текущем коде — фиксация
> семантики, не фикс бага). Грабли: `bc` в контейнере нет — счётчики тестов
> считать awk'ом. +4 теста, гейт **1210/1210** (core 1130 + engine-okhttp 25 +
> engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15), `./gradlew test
> javadoc` зелёный под `-Werror`, 0 warnings. ADR-0034 проверен и закрыт;
> следующие срезы — ADR-0036 (метрики), ADR-0035 (стриминг тела), ADR-0038
> (HTTP/2/трейлеры).

> **Сессия #191 (аудит «конкурентный жизненный цикл», идея #187: CircuitBreaker
> чист, RateLimiter — платформозависимый тайминг бюджета ожидания):** десятая
> «тихая» находка серии — но не рассинхрон с ADR, а скрытая
> платформозависимость. `TokenBucketRateLimiter.tryAcquire(Duration)` вёл учёт
> бюджета инкрементально: `remainingWaitNanos -= (waitTime - waited)`, где
> `waited` — возврат `Condition.awaitNanos()`, т.е. **оценка** фактического
> сна, зависящая от платформы. Инструментация поймала на этой JVM
> **отрицательные** `waited` (-0.1…-4.8 мс) — оценка parkNanos перестреливает
> запрос, и формула здесь «случайно» работает (бюджет списывается на всё время
> сна). На платформе с `waited ≈ waitTime` бюджет не списывается вовсе:
> потерявший гонку за рефиллом токен ждёт второй полный период refill без
> учёта в timeout → блокировка до ~2× заявленного (контракт ADR-0043:
> «ожиданием до `timeout`»). Fix: бюджет закреплён за абсолютным
> `System.nanoTime()`-дедлайном (однократно на входе), остаток пересчитывается
> из дедлайна на каждой итерации через новый package-private seam
> `RateLimiters.remainingBudget(deadlineNanos, nowNanos)` (clamp ≥ 0); возврат
> `awaitNanos` в учёт больше не идёт — сон лишь оптимизация, дедлайн —
> источник правды. `InMemoryCircuitBreaker` проверена чисто: весь мутабельный
> state под lock'ом или volatile, реентерабельная вложенность
> `metrics()`→`checkAutoHalfOpenTransition()`→`transitionToHalfOpenState()`
> (глубина 3) ок, сбой проба в HALF_OPEN реоткрывает сразу (поглощает
> stale-пробы), конфиг валидирует жёстко. TDD: compile-red (stash → 5 ошибок
> на отсутствие seam'а) → зелёный. Грабли: поведенческий барьерный тест (2
> нити, общий 150 мс window, один рефилл-токен — ровно один победитель, никто
> не ждёт дольше timeout) на старом коде зелёный **из-за негативной оценки
> awaitNanos** — платформа маскирует баг; гейт на seam (паттерн #190),
> поведенческий тест — кроссплатформенный предохранитель. Вывод: эффект,
> замаскированный платформой, требует seam-гейта + предсказуемого
> детерминированного теста. +2 теста, гейт **1206/1206** (core 1126 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. ADR-0043 —
> Amendment #191.

> **Сессия #190 (аудит: ADR-0041 §4 — валидация `@Compress(algorithm != null)`
> заявлена, но не реализована; null-алгоритм — тихий no-op):** девятая
> «тихая» находка серии. ADR-0041 §4 обещал две проверки `ControllerValidator`'а
> (`minSizeBytes >= 0` и `algorithm != null`); с оригинала #119
> (`3a2c22f`) жила только пороговая. Нюанс JLS: `algorithm()` — enum-член
> с дефолтом, и `@Compress(algorithm = null)` не компилируется (JLS 9.7.1) —
> пункт был аспирационным, но рантайм null допускал молча:
> `parseCompression` → `EffectiveCompression(true, true, null, 0)` →
> `resolveCompressor(null)` → `null` → компрессия пропущена **без ошибки и без
> заголовка** («компрессия, которой нет»). Fix: проверка добавлена на обоих
> уровнях через общий package-private seam `ControllerValidator.validateCompress`
> (пороговая проверка переехала туда же, сообщения без изменений). TDD:
> compile-red (stash → 2 ошибки на отсутствие seam'а) → зелёный; гейт —
> proxy-аннотация с `algorithm() == null` (единственный способ выразить
> null-алгоритм мимо javac) + позитивный предохранитель. Остальной срез
> ADR-0041 чист: SPI/enum/реестр/дефолты/приоритеты/опт-аут/порог
> `>= minSizeBytes`/гард двойной компрессии (статика + динамика заголовков)
> совпадают; `body`/`bodyPublisherSupplier` взаимно исключают друг друга,
> `contentCompressors` — `Map.copyOf`, `handleDecompression` — по §5.3 +
> ADR-0057. ADR-0041 → `## Amendment #190`. +1 тест, гейт **1204/1204**
> (core 1124 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
> benchmarks 15), `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.
> Вывод: в «аспирационных» пунктах ADR (валидации, которые статически
> недостижимы) искать живой рантайм-путь — там и прячется тихий no-op.

> **Сессия #189 (аудит: `@Order` резортировал интерцепторы сквозь уровни и аннотации
> — ADR-0044 не соблюдён):** восьмой «тихий» рассинхрон серии, на этот раз —
> «утечка скопа». ADR-0044 прямо говорит: «Only sorts within a single
> `@WithInterceptor` value array», «The inter-level ordering guarantee
> (Global → Super → Sub → Method) is not affected», «Cross-annotation
> ordering still follows declaration order». Код `MethodMetadata.parseInterceptors`
> (переехал из `HttpTemplateBuilder` в ADR-0051; глобальная сортировка была
> ещё в оригинальном #122) делал пер-декларационную `sortedByOrder` (правильно)
> **плюс** финальную глобальную стабильную сортировку всего разрешённого
> списка по `@Order` — поверх всех аннотаций и всех уровней скопа. Следствия
> (оба детерминированы): sub/method-интерцептор с `@Order(-10)` опережал
> super-интерцептор с `@Order(0)` (инверсия пирамиды ADR-0029), а второй
> `@WithInterceptor` с отрицательным `@Order` выдвигался перед первым. Тихо:
> при всех нулевых значениях (обычный случай) стаб-сортировка — no-op,
> расхождение видно только когда `@Order` реально используется на разных
> уровнях/аннотациях. Fix: финальная глобальная сортировка удалена — каждая
> декларация уже отсортирована внутри себя (`sortedByOrder`); публичный API
> не тронут, Javadoc `@WithInterceptor` уточнён («within each declaration»).
> TDD red-first: 2 красных теста в `InterceptorResolutionTest`
> (`orderAnnotationDoesNotCrossScopeLevels`,
> `orderAnnotationDoesNotReorderAcrossAnnotations`) — красные ровно по
> целевым ассертам, зелёные после; все старые порядковые тесты
> (ADR-0044 ×3, `pipelineOrderingHierarchy`, dedup) не тронуты. ADR-0044
> дополнен `## Amendment #189`. +2 теста, гейт **1203/1203** (core 1123 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. Вывод по нити:
> класс «тихих» багов серии — скопы/границы применения механик (кап
> Retry-After — скоп дефолта, пропигатор — скоп аннотации, сортировка —
> скоп декларации); ADR сверять по граничным формулировкам «где/в пределах
> чего». ADR-0044 проверен и закрыт; следующие срезы — ADR-0041 (сжатие
> запроса) ✓ закрыт в #190, ADR-0034 (query-коллекции), ADR-0036 (метрики),
> ADR-0035 (стриминг тела), ADR-0038 (HTTP/2/трейлеры).

> **Сессия #188 (аудит: `@Trace(enabled=false)` пропускал клиентский
> `TracePropagator` — ADR-0042 §6.4 не выполнена):** нить «ADR против кода»
> (#184), срез ADR-0042 (tracing). Седьмой «тихий» рассинхрон серии:
> `applyTracingHeaders` при неактивной аннотации делал ранний `return` и
> не применял глобальный `TracePropagator` из `ModuleInfo`, хотя ADR прямо
> говорит: «Если аннотации нет (или она не переопределяет клиентский уровень),
> применяется глобальный TracePropagator». Асимметрия с ADR-0043, где
> `enabled = false` у `@CircuitBreaker`/`@RateLimiter` корректно падает на
> клиентский уровень. Последствие: метод с `@Trace(enabled=false)` на клиенте
> с `correlationId(...)`/`w3cTracing()`/`tracePropagator(...)` терял заголовок
> корреляции целиком — тихий обрыв сквозной трассировки. Fix: пропигатор
> применяется при **отсутствующей или неактивной** аннотации (логика вынесена
> в `applyTracePropagator`); активная аннотация по-прежнему перекрывает
> клиентский уровень (без двойной инъекции). Javadoc `@Trace`/`enabled()`
> уточнён, ADR-0042 дополнен `## Amendment #188`. TDD red-first: 2 красных
> e2e-теста в `RequestTracingWireMockTest` (12: correlationId-фолбэк на
> `@Trace(enabled=false)` — красный ровно по `verify(X-App-Trace-ID)`; 13:
> w3c-фолбэк — красный по отсутствию `traceparent`) + 1 предохранитель (14:
> активная аннотация побеждает пропигатор — зелёный до и после). +3 теста,
> гейт **1201/1201** (core 1121 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный под
> `-Werror`, 0 warnings. Публичный API не тронут. Грабли: `./gradlew test
> --tests 'X'` без `:` падает на `:benchmarks:test` (фильтр ко всем субмодулям)
> — использовать `:test --tests 'X'`. Аудит-нить продолжается; ADR-0042
> проверен и закрыт, ADR-0043 (resilience) проверен по дефолтам/аннотациям/
> приоритетам/валидации — чист (предикат `recordException` применяется внутри
> `recordFailure`, что удовлетворяет §6). Следующий ход — свободный (батч №2
> test-javadoc или новый аудит-свет).

> **Сессия #187 (аудит-нить возобновлена: `SingleFlightTokenManager` — завершившийся лидер
> затирал свежий in-flight future):** шестой «тихий» рассинхрон серии, на этот раз —
> «конкурентный жизненный цикл». Подозрение, заведённое в #185 (и не тронутое):
> `invalidate()` безусловно чистит `inFlight`, а завершающий лидер
> `inFlight.set(null)` мог сбить **новую** in-flight-аквизицию, которую другой
> вызывающий успел установить за это время. Сессия #186 начала фикс, но оборвалась
> до коммита (WIP в дереве: CAS-фикс + детерминированный latch-тест) — принята,
> проверена red-first и зафиксирована. Fix: лидер чистит только свой future —
> `inFlight.compareAndSet(future, null)` в success- и failure-ветках; чужой свежий
> future переживает. TDD red-first подтверждён на старом коде: новый
> `TokenManagerTest.finishingLeaderDoesNotClobberNewerInFlightAcquisition`
> (3 нити, 4 latch'а, без blind-wait'ов: лидер в аквизиции → `invalidate()` →
> второй вызывающий ставит свежий future → лидер завершается → третий **должен
> присоединиться** к нему, а не начать новый supplier-вызов) — красный на старом
> коде ровно по single-flight-ассерту, зелёный после. +1 тест, гейт **1198/1198**
> (core 1118 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
> benchmarks 15), `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.
> Публичный API не тронут (контракт `invalidate()` — как в javadoc; фикс — внутри
> реализации). Аудит-нить продолжается; следующий ход — кандидат №1
> (test-javadoc, батч №2: `cookie`/`codec`/`compression`) или новый аудит-свет.

> **Сессия #185 (кандидат №1 test-javadoc — первый батч; аудит-нить приостановлена):**
> после пяти подряд «тихих» багов (#181–#184) взял запланированный кандидат №1:
> наведение лаконичного class-level javadoc в тестовых классах. Замер по дереву:
> **148 тест-классов, у 42 есть doc-комментарий**, у 106 — нет (многосессионная работа).
> Первый батч — сквозные подсистемы: пакеты `ru.jrestly.resilience` (3 класса:
> `CircuitBreakerTest`, `RateLimiterTest`, `ResilienceConfigTest`) и `ru.jrestly.auth`
> (4: `AuthorizationWireMockTest`, `AuthInterceptorsTest`, `TokenManagerTest`,
> `TokenTest`) — всего **7 классов**. Стиль — по эталону `HttpVersionConfigTest`:
> 2–4 строки, «что покрывает + unit/e2e», без пересказа каждого теста (Антон:
> комментарии должны быть лаконичными). Изменения — только doc-комментарии, без
> правок логики и без новых тестов. Гейт: `./gradlew test` зелёный, root **1117/1117**
> (failures 0), полный гейт **1197/1197** без изменений; javadoc-гейт корня/субмодулей
> смотрит только на `src/main`, поэтому не затронут. Осталось ~99 тест-классов без
> class-level javadoc — продолжать батчами (следующие кандидаты: `cookie`, `codec`,
> `compression`, `tracing`, `interceptor`, затем корневой `ru.jrestly`).

> **Сессия #184 (аудит: `RetryPolicy` — дефолтный потолок `Retry-After` не применялся
> вопреки ADR-0040):** пятый подряд «тихий» рассинхрон, на этот раз внутри уже
> принятого ADR. Решение ADR-0040 прямо говорит: `maxRetryAfter` (дефолт `null`)
> при обращении падает на `maxBackoff`, а `@Retry.maxRetryAfterMs() default -1`
> означает «дефолт `maxBackoffMs`». Код же применял потолок **только** при явно
> заданном `maxRetryAfter` (`if (maxRetryAfter != null) { … }`). Следствие: при
> дефолте серверный `Retry-After: 86400` парковал вызывающий поток на **24 часа** —
> ровно тот зависание, ради предотвращения которого пункт 3 ADR и писался. При
> этом builder-javadoc и формула в class-javadoc **уже** описывали fallback;
> расходился только код (и производная формулировка `getMaxRetryAfter()` «no cap
> applied»). Fix: эффективный потолок `maxRetryAfter != null ? maxRetryAfter :
> maxBackoff`, ожидание `max(bound, min(cap, retryAfter))` в обоих случаях;
> поле-конфиг остаётся `null` при незаданном (публичный геттер не тронут —
> совместимо), javadoc геттера/класса уточнены. TDD red-first: новый
> `RetryPolicyJitterTest.defaultRetryAfterCapFallsBackToMaxBackoff` (24 ч → 1000 мс)
> — красный на старом коде (возвращал 86 400 000), зелёный после. Два старых
> wall-clock теста `RetryTest` держали недокументированное «без потолка»
> (`maxBackoff` 50 мс против `Retry-After` 1 с/3 с) — теперь явно ставят
> `maxRetryAfter(10 с)`, продолжая проверять, что заголовок поднимает ожидание.
> ADR-0040 дополнен `## Amendment #184`. +1 тест, гейт **1197/1197** (core 1117 +
> engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.

> **Сессия #183 (аудит: `AuthHeaderProvider` — рассинхрон `isAuthorized()` с реально
> отправляемыми заголовками + неатомарная публикация пары):** четвёртый подряд шаг
> нити «общие мутабельные структуры» (#181 гонка кэша прокси, #182 живые списки
> `ResponseHeaders`). Найден **детерминированный** разлад контракта:
> `AuthHeaderProvider.isAuthorized()` возвращал `initialHeaderName != null ||
> headerValue != null`, тогда как `getAuthHeaders()` отправляет креды только при
> **обеих** непустых частях. Следствие: имя без значения (или
> `updateAuthHeader(null, v)`) → клиент считает себя авторизованным,
> `isLoginInBackgroundNeeded` не запускает логин, а на провод уходит **пусто** —
> тихая «авторизация в никуда». Плюс пара `headerName`/`headerValue` хранилась
> двумя не-`volatile` полями и писалась из `@SetAuthDetails`/`updateAuthHeader`
> неатомарно → читатель мог увидеть **рваную** пару (имя одного обновления +
> значение другого). Fix: пара хранится одним неизменяемым `Header` в
> `volatile`-поле (`complete(name, value)` → пара или null),
> `getAuthHeaders()`/`isAuthorized()` читают один и тот же снимок — инвариант
> «authorized ⇔ есть что отправить». Поведение при полной паре не изменилось
> (`AuthTest`/`TestAuthProvider` не тронуты). TDD red-first: новый
> `AuthHeaderProviderTest` (6 тестов) — 4 красных на старом коде (неполная пара
> авторизует; `isAuthorized` расходится в матрице состояний; стресс-тест поймал
> рваную пару `(name, value)` из двух апдейтов), зелёные после. +6 тестов, гейт
> **1196/1196** (core 1116 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный под
> `-Werror`, 0 warnings. Эпики не трогались — чистая robustness-находка.

> **Сессия #182 (аудит: `ResponseHeaders` отдавал мутабельные внутренние списки — мёртвый
> код вместо защиты):** продолжение нити «общие мутабельные структуры». Под фонарём
> оказался `ResponseHeaders.doPopulate`/`populateTrailers`: там строился локальный
> `unmodifiableMap` (обёртка каждой `List<String>` в `Collections.unmodifiableList`),
> но результат **выбрасывался** — в поле присваивался `Collections.unmodifiableMap(map)`
> от исходной `TreeMap`, чьи значения — **живые `ArrayList`**. Итог: javadoc обещает
> «unmodifiable map/list of header values», а `map().get("Set-Cookie").add(...)` /
> `allValues(...).add(...)` **мутируют состояние холдера** (и видны другим читателям,
> включая `firstValue`/`cookie()`), плюс на **каждый ответ** впустую аллоцировался
> `LinkedHashMap` и прогонялся цикл копирования (вразрез с zero-garbage эпиком ADR-0055).
> Fix: `map.replaceAll((k, v) -> Collections.unmodifiableList(v))` перед
> `unmodifiableMap` (case-insensitive lookup сохранён — оборачиваем ту же `TreeMap` in
> place; связка с `LinkedHashMap` дала бы case-sensitive регрессию) в обоих местах,
> мёртвый цикл удалён. TDD red-first: 2 новых теста в `ResponseHeadersTest`
> (`assertThrows(UnsupportedOperationException)` на `allValues(...).add(...)` и
> `map().get(...).add(...)`; отдельный на `trailerValues(...)`) — **красные** на старом
> коде (списки мутабельны), зелёные после. Гейт **1190/1190** (core 1110 + okhttp 25 +
> apache5 25 + codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный
> под `-Werror`, 0 warnings. Эпики не трогались — третья robustness-находка подряд.

> **Сессия #181 (аудит: скрытая гонка в кэше прокси — `HashMap` + `computeIfAbsent`):**
> свободный ход-аудит по нити Антона «что не запланировано и не видно». Найдена и
> исправлена **реальная потоковая гонка** в `AbstractHttpClient.controllers`:
> кэш прокси-контроллеров был обычным `HashMap`, мутируемым через
> `computeIfAbsent` без синхронизации. `HashMap.computeIfAbsent` выполняет
> mapping-функцию **вне** какого-либо замка и после неё проверяет `modCount` —
> две нити, одновременно зовущие `client.get(Api.class)` (ленивое
> разыменование контроллера на общем клиенте в многопоточном сервере),
> падают с `ConcurrentModificationException` (а в других окнах — теряют
> созданный прокси/создают его дважды). Фикс: поле → `ConcurrentHashMap`
> (сосед `interceptorInstances` уже был CHM; `JRestlyClient.get()` и
> `AbstractHttpClient.get()` пользуются тем же полем). TDD red-first:
> новый `ProxyCacheConcurrencyTest` с детерминированным барьером в
> `createController` (расширяет окно гонки; на CHM барьер просто истекает по
> таймауту) — **красный** на старом `HashMap` ровно ожидаемым
> `ConcurrentModificationException`, зелёный на CHM. +1 тест, гейт
> **1188/1188** (core 1108 + engine-okhttp 25 + engine-apache5 25 +
> codec-jackson-xml 15 + benchmarks 15), `./gradlew test javadoc` зелёный
> под `-Werror`, 0 warnings. Эпики не трогались — чистая robustness-находка.

> **Сессия #180 (ADR-0060 — Ф3 реализована: ServiceLoader-автоподбор кодеков; ADR закрыт полностью):**
> новый SPI `ru.jrestly.codec.PayloadCodecProvider` (`isAvailable()`/`createCodec()`/
> `default priority()`) — точный аналог `HttpEngineProvider` (ADR-0058), плюс
> `PayloadCodecResolver`: реестр собирается в порядке «явные → обнаруженные
> (по убыванию `priority()`, стабильно) → JSON-дефолт». Подхват работает и в
> канонической фабрике `JRestlyClient` (`SimpleModuleInfo`), и в default-методе
> `ModuleInfo.getCodecRegistry()`. Явный `codec(...)` впереди обнаруженных;
> полная замена `codecs(...)` отключает автоподбор. Первый официальный
> провайдер `JacksonXmlCodecProvider` (`priority = 50`) в субмодуле
> `codec-jackson-xml`: XML теперь подключается **без единого вызова** —
> `builder().baseUrl(...).build()` уже умеет XML (доступность зондируется по
> `XmlMapper`). +9 тестов (core 8: `PayloadCodecResolverTest` 5 + e2e
> `PayloadCodecAutoDetectionTest` 3; субмодуль +1). Гейт **1187/1187**
> (core 1107 + okhttp 25 + apache5 25 + codec-jackson-xml 15 + bench 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings. README дополнен
> секцией «Payload codecs: JSON, XML & custom formats». **Кандидат №4 закрыт
> полностью.** Следующий ход — свободный (кандидат №1 test-javadoc или аудит).

> **Сессия #179 (ADR-0060 — Ф2 реализована: XML-адаптер, ADR → Accepted):** новый
> субмодуль `jrestly-codec-jackson-xml` (`codec-jackson-xml/`,
> `Automatic-Module-Name: ru.jrestly.codec.jacksonxml`). Единственный публичный
> тип `ru.jrestly.codec.jacksonxml.JacksonXmlCodec implements PayloadCodec` —
> обёртка над Jackson `XmlMapper`: ctor от явного mapper'а либо `defaults()`
> (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_XML_DECLARATION=false`,
> `findAndRegisterModules()` → jsr310 из classpath через service-file, ядро не
> тянет). `supports` claim'ит `application/xml` / `text/xml` / `*+xml`
> (RFC 6839); побайтовый контракт (null/empty → null, null → empty, prettyPrint
> с fallback). Зависимости субмодуля: `api project(':')` +
> `api jackson-dataformat-xml:2.18.2` (транзитивно databind/core + woodstox),
> `compileOnly` jsr310 — **ядро по-прежнему строго zero-dep**. +14 тестов
> (`JacksonXmlCodecTest` 10, e2e `JacksonXmlWireMockTest` 4: DTO под
> `@ContentType(application/xml)` уходит реальным XML, типизированный ответ по
> `Content-Type: application/xml` связывается, JSON-дефолт цел, без кодека —
> fail-fast). **1178/1178** (core 1099 + okhttp 25 + apache5 25 + codec-jackson-xml
> 14 + bench 15), javadoc под `-Werror` зелёный, 0 warnings. Формат payload
> теперь реально расширяемый: XML из коробки, любой свой формат — один
> интерфейс `PayloadCodec`. Следующий ход — свободный (Ф3 ServiceLoader
> опциональна; иначе другой кандидат активной зоны).

> **Сессия #178 (ADR-0060 — Ф1 реализована: SPI ядра):** новый пакет
> `ru.jrestly.codec` (`PayloadCodec`, `CodecRegistry`, `JsonCodecAdapter`) +
> плумбинг (`ModuleInfo.getCodecRegistry()` default, `SimpleModuleInfo`
> `codec(...)`/`codecRegistry(...)`, `JRestlyClient.Builder.codec(...)`/`codecs(...)`).
> Селекция запроса по эффективному `Content-Type` (явный заголовок → `@ContentType`
> → `requestType.mime()`): нет подходящего кодека и body — bindable DTO →
> **fail-fast** вместо тихой JSON-подмены; raw-типы минуют кодек. Селекция ответа:
> 5 call-site'ов `HttpTemplate` (вкл. `probePayloadDecode`/`@OnError`) берут кодек
> по `Content-Type` ответа через `resolveOrDefault` → JSON-by-default (обратная
> совместимость). Multipart — по media-типу части вместо `contains("json")`.
> `setJsonCodec` обнуляет производный реестр (грабли: тесты строят шаблон
> билдером, потом меняют кодек). `NoOpPayloadCodec` осознанно не заводил —
> покрыт `JsonCodecAdapter(NoOpJsonCodec)`. +11 тестов (`CodecRegistryTest` 7,
> e2e `PayloadCodecWireMockTest` 4). **1164/1164** (core 1099 + okhttp 25 +
> apache5 25 + bench 15), javadoc под `-Werror` зелёный, 0 warnings. Следующий ход
> — Ф2 (XML-адаптер `jrestly-codec-jackson-xml`).

> **Сессия #177 (концепт ADR-0060 «Pluggable payload formats»):** оформлен
> `docs/0.10.0/ADR-0060-pluggable-payload-formats-and-media-type-aware-codec-spi.md`
> (Status: Proposed) — медиа-тип-осознанный SPI `PayloadCodec` + `CodecRegistry`
> рядом с нетронутым `JsonCodec` (`JsonCodecAdapter` как мост). Корневой факт
> уточнён **зондом** (правка к #176): `@ContentType("application/xml")` + DTO
> **не** роняет тело — он сериализует DTO в **JSON и шлёт его под заголовком
> `application/xml`** (проверено WireMock: header `application/xml`, body
> `{"name":"Anton","age":42}`). Т.е. обещание javadoc `@ContentType` нарушено
> на проводе (JSON-байты под XML-типом), а не отброшено. Response-путь тоже
> игнорирует `Content-Type` ответа (5 call-site'ов `jsonCodec` в `HttpTemplate`).
> Решение: селекция кодека по request/response `Content-Type`
> (`resolveOrDefault` → JSON для обратной совместимости), fail-fast на запросе
> при незарегистрированном media-типе вместо тихой подмены, zero-dep ядро,
> XML — внешний адаптер (фаза 2). Фазы: Ф1 SPI ядра / Ф2 `jrestly-codec-jackson-xml`
> / Ф3 ServiceLoader-автоподбор. Код не менялся — только ADR + память.

> **Сессия #176 (мелкий аудит-фикс + новый стратегический кандидат №4 «XML & кастомные форматы»):**
> (1) Аудит-фикс: запросный URI-энкодер `ApiKeyAuth` (query-размещение ключа) отставал
> от остальной поверхности запроса — `URLEncoder` оставлял пробел как `+`
> (form-encoding), тогда как `HttpTemplate.encode` и `HttpTemplateBuilder.createUrl`
> нормализуют его в `%20` (RFC 3986: в query `+` — литеральный плюс). Вынес
> `encodeQueryComponent` + red-first тест. **1153/1153** (core 1088 + okhttp 25 +
> apache5 25 + bench 15), javadoc под `-Werror`, 0 warnings.
> (2) Антон (mailbox, 2026-10-16) поднял новую нить: **поддержка XML и вообще
> кастомных форматов** — «не JSON'ом единым». Аудит по коду подтвердил реальный,
> незапланированный пробел: формат полезной нагрузки структурно захардкожен под JSON.
> `RequestType` — enum ровно из трёх значений (`APPLICATION_JSON`,
> `APPLICATION_FORM_URLENCODED`, `MULTIPART_FORM_DATA`), и DTO-тело сериализуется
> **только** при `APPLICATION_JSON` (`HttpTemplateBuilder:289`): при
> `@ContentType("application/xml")` DTO молча **выбрасывается** (body == null), при
> этом сам javadoc `@ContentType` рекламирует `application/xml` как пример. SPI
> назван и сформован JSON-only (`JsonCodec`, `JsonCodecResolver` пробует только
> Jackson/Gson/Moshi, `prettyPrint`, `NoOpJsonCodec`). Заведён **Кандидат №4 —
> ADR-0060: pluggable payload formats (Codec SPI общего вида)**, детали ниже.

> **Сессия #175 (ADR-0059 — Ф3 реализована, спайк пройден):** idle-stall guard
> оставлен в коде после удачного спайка на всех трёх транспортах. Восстановлен
> и доделан незакоммиченный WIP прерванной сессии: `ReconnectPolicy.stallTimeout(Duration)`
> (default null/выключено, положительность валидируется, `hasStallTimeout()`),
> сторожевой виртуальный поток `jrestly-sse-stall-guard` в `ServerSentEventReconnector`
> (`markWaitStart`/`markWaitEnd` вокруг блокирующего `hasNext()`; при простое > таймаута
> `stalled=true` + закрытие активного стрима **вне монитора** → потребитель отпускается
> с `UncheckedIOException(SocketTimeoutException)` → обычный реконнект Ф1). Спайк:
> JDK `InputStream.close()` будит блокированного читателя (`IOException: closed`, ~41 мс) —
> новый e2e-гейт `JdkStreamingCloseUnblockedTest` (реальный клиент над raw-TCP сервером);
> OkHttp/HC5 — parity-тесты `closingStreamingInputStreamUnblocksBlockedReaderThread`.
> Рефактор watchdog: foreign `close()` вынесен из-под монитора. Все три фазы ADR-0059
> закрыты. Гейты: **1152/1152** (core 1087 + okhttp 25 + apache5 25 + bench 15),
> `./gradlew test javadoc` зелёный под `-Werror`, 0 warnings.

> **Сессия #163 (ADR-0059 — Ф2 реализована):** завершение трейлеров на
> стриминговом пути (`WireResponse.trailersSupplier` + `populateTrailers` +
> терминальные хуки + engine parity) по TDD red-first (compile-red на
> отсутствующие методы → 14 новых тестов: 3 в WireResponseTrailersTest,
> 3 в ResponseHeadersPopulateTrailersTest, 6 в StreamingTrailersTest,
> 1 в OkHttpEngineParityTest, 1 в Apache5HttpEngineParityTest).
> Добавлен 7-й компонент `Supplier<List<Header>> trailersSupplier` в
> `WireResponse` (дефолт `List::of`/static copy; явный 6-аргументный конструктор
> сохранён; `equals`/`hashCode` игнорируют supplier; `Builder.trailersSupplier`).
> `ResponseHeaders.populateTrailers(trailers)` потокобезопасен и идемпотентен,
> обновляет `trailers()` и `trailerMap`; поддержан `ResponseHeaders.capture` /
> `captureCallable` через `attachTrailerForwarder`. Package-private
> `TrailerCompletingInputStream` перехватывает EOF (-1) и `close()`, вызывая
> однократный CAS-запрос supplier (`AtomicBoolean`). `HttpTemplate.processResponse`
> оборачивает `stream` хуком при наличии supplier и получателей заголовков;
> стриминговая декомпрессия (`handleDecompression`) сохраняет supplier.
> Движки `engine-okhttp` и `engine-apache5` поставляют ленивые трейлеры после
> вычитывания тела (`response.trailers()`, `entity.getTrailers()`); JDK движок
> остаётся пустым (платформенная граница). **1145/1145** (core 1082 + okhttp 24 +
> apache5 24 + bench 15), javadoc зелёный под `-Werror`, 0 warnings.
> Впереди Ф3 (spike stall guard).

> **Сессия #161 (ADR-0059 — Ф1 реализована):** `ServerSentEvent.reconnecting(
> opener, ReconnectPolicy)` по TDD red-first (32 compile-errors → 18 юнитов +
> 1 e2e). Новый публичный API: `ReconnectPolicy` (maxAttempts = реконнекты
> после первой связи, default 3; initialBackoff 3000 мс WHATWG; maxBackoff
> 30 с; multiplier 1.0; `Jitter.NONE` — ждём ровно серверный `retry:`;
> `stopOnDone` true; `onReconnect(Consumer<ReconnectInfo>)`) и
> `ReconnectInfo(attempt, lastEventId, delay, cause)`. Ленивый
> `ServerSentEventReconnector`: реконнект на `UncheckedIOException`/чистом
> EOF/падении opener'а; реплей `Last-Event-ID` = последний виденный `id`
> (персистится парсером, #142); задержка — серверный `retry:` → fallback,
> capped maxBackoff, grown по multiplier, jitter последним слоем;
> `Thread.sleep` на потоке потребителя (без скрытых исполнителей); `close()`
> отменяет реконнект; `[DONE]` терминален и сразу освобождает соединение;
> прерывание ожидания — `UncheckedIOException(InterruptedIOException)` +
> флаг; прочие RuntimeException не реконнектятся. E2E WireMock
> «dropped → resumed»: второй стаб требует `Last-Event-ID: 2` (обрыв — чистый
> EOF: Fault не отдаёт тело перед сбросом), `findAll` подтверждает 2 запроса
> и заголовок; `retry: 50` дошёл до `ReconnectInfo.delay`. ADR-0059 дополнен
> Amendment #161; впереди Ф2 (трейлеры) и Ф3 (stall guard, spike-gated).
> **1131/1131** (core 1070 + okhttp 23 + apache5 23 + bench 15), javadoc
> зелёный, 0 warnings.

> **Сессия #160 (кандидат №2 — аудит зоны + концепт ADR-0059):** оформлен
> `docs/0.10.0/ADR-0059-sse-stream-resilience-and-streaming-trailer-completion.md`
> (Status: Proposed) — три пробела, подтверждённых по коду: (A) у
> установленного SSE-стрима нет жизненного цикла — `RetryPolicy` (ADR-0040)
> работает только до тела, `Last-Event-ID` в `src/main` не упоминается вовсе,
> обрыв прилетает потребителю как `UncheckedIOException`; (B) нет idle-stall
> guard — JDK `HttpRequest.timeout()` не покрывает чтение тела, зависшая
> SSE-сеть держит поток вечно; (C) трейлеры в стриминге теряются — все три
> движка строят `WireResponse` с `List.of()` до EOF (буферные пути
> OkHttp/Apache5 работают, JDK — платформенно не умеет; подтверждено `javap`:
> trailer-методов нет ни у `HttpResponse`, ни у `BodySubscriber`, ни у
> `ResponseInfo`). Решение: **Ф1** — `ServerSentEvent.reconnecting(opener,
> ReconnectPolicy)` с EventSource-семантикой (opener получает nullable
> `lastEventId`, серверный `retry:` + `Jitter` ADR-0040, `stopOnDone` для
> LLM, bounded attempts, `onReconnect`-хук); **Ф2** — 7-й компонент
> `WireResponse.trailersSupplier` (прецедент совместимых конструкторов
> ADR-0038) + `ResponseHeaders.populateTrailers` + терминальные хуки на
> стриминговых артефактах (EOF/close → однократный CAS-запрос);
> **Ф3** (spike-gated) — `stallTimeout` в policy. Non-goals: request-side
> трейлеры, трейлеры JDK-движка, бесконечный reconnect. Код не менялся —
> только ADR + память.

> **Сессия #159 (Finding #158 закрыт — движки подключаются сами):**
> `AbstractHttpClient.createConnection()` целиком делегирует
> `HttpEngineResolver.resolve(moduleInfo)`: явный движок → SPI-провайдеры по
> приоритету → JDK-fallback. Дублирующая сборка JDK-клиента из
> `createConnection()` удалена — единственный источник правды
> `JdkHttpEngine.create(moduleInfo)`, который теперь сам honor-ит
> `isVirtualThreads()` (собственный managed executor, закрывается в
> `engine.close()`). Клиентский managed-executor ADR-0026 остался за
> `AbstractHttpClient` (custom → виртуальные потоки; закрывается в `close()`) —
> `ExecutorConfigTest` не тронут. **TDD red-first:** e2e-гейт
> `SpiEngineAutoDetectionTest` (3 теста) + `TestSpiEngineProvider` в тестовом
> classpath (`META-INF/services/ru.jrestly.engine.HttpEngineProvider`, доступен
> только под статическим флагом — остальные тесты не затронуты): до фикса
> «default client picks up the SPI engine» красный, после зелёный; предохранители
> (fallback на JDK без провайдера; явный движок побеждает) зелёные до и после.
> Бенчмарки: с обоими субмодулями на classpath дефолтный клиент молча
> становился бы движковым — JDK-транспорт закреплён явным `JdkHttpEngine.create`
> в `JRestlyBenchmarkClient`/`DispatchOverheadBenchmark`, примечание о честности
> добавлено в `BENCHMARKS.md`; README-секция Performance уточнена
> (ServiceLoader-подхват + явный override). ADR-0058: Finding #158 → Resolution
> #159, header «Complete, Finding закрыт». **1112/1112** (core 1051 + okhttp 23
> + apache5 23 + bench 15), javadoc-гейты зелёные, 0 warnings.

> **Сессия #158 (ADR-0058 — публичная бенчмарк-документация + скрытый дефект
> интеграции):** оформлен публичный `BENCHMARKS.md` (методология, матрица
> 10 клиентов, честные ограничения, воспроизведение; свежий прогон JMH
> #158 подтвердил картину #147). **Главная находка — интеграционный дефект
> последней мили ADR-0058:** `AbstractHttpClient.createConnection()` **не
> вызывает** `HttpEngineResolver` (при `null`-движке строит JDK-клиент
> напрямую), поэтому ServiceLoader-автообнаружение `engine-okhttp` /
> `engine-apache5` end-to-end не работает — только явный
> `builder.httpEngine(...)`. Доказано поведенчески: с обоими субмодулями на
> classpath «дефолтный» клиент бенчмарка показывает цифры JDK-транспорта
> (1666 µs), а не быстрых движков (~400–540 µs); и по коду — у
> `HttpEngineResolver.resolve` нулевой call-site в main. Плюс рассинхрон
> javadoc (`ModuleInfo.getHttpEngine()` обещает резолвер). Фикс и
> красный e2e-тест — первая задача #159; ADR-0058 переведён в статус
> Accepted с открытым Finding #158.

> **Сессия #157 (ADR-0058 — фаза 2b закрыта):** создан субмодуль
> `engine-apache5` (провайдер 50, ServiceLoader, build.gradle с javadoc-гейтом);
> `Apache5HttpEngine` реализует полный контракт паритета (timeout, streaming
> через `executeOpen`, progress upload/download, trailers в буферном режиме,
> свой executor для `sendAsync` — не общий ForkJoinPool, TLS/cookies/proxy/
> authenticator из ModuleInfo). Пассивный транспорт с первой попытки:
> `disableContentCompression()` (урок #156 применён заранее). Грабли: 5.4.1
> пометил deprecated SSL/cookie/timeout API — миграция на
> `DefaultClientTlsStrategy`, `ConnectionConfig`, Instant-куки; `setProxy(HttpHost)`
> без замены → локальный `@SuppressWarnings`. benchmarks на
> `project(':engine-apache5')`, прототип удалён. **1109/1109** (core 1048 +
> engine-okhttp 23 + engine-apache5 23 + bench 15), 0 warnings, полный
> `./gradlew test` зелёный. Осталась публичная бенчмарк-документация.

> **Сессия #149 (ADR-0058 — фаза 1, ядро):** добавлен `default int priority()`
> в `HttpEngineProvider` (бинарно-совместимое расширение, дефолт 0) и
> стабильная сортировка в `HttpEngineResolver.resolveFromSpi` (убывающий
> приоритет; равенство сохраняет порядок `ServiceLoader` — `List.sort`
> гарантированно стабильный). TDD red-first: 2 красных ассерта в
> `HttpEngineResolverTest` (приоритет побеждает порядок итерации; legacy
> провайдер с дефолтным 0 проигрывает явному 1) + 3 регрессионных
> предохранителя (стабильность при равенстве, пропуск недоступного
> высокоприоритетного, fallthrough null-движка). **1063/1063** (ядро 1048
> + bench 15), 0 warnings. Фаза 1 закрыта; дальше — фаза 2 (субмодули).

> **Сессия #148 (ADR-0058 — концепт):** оформлен ADR
> `docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md`
> (Status: Proposed). Двухфазное исполнение: (1) ядро — `priority()` в
> `HttpEngineProvider` + стабильная сортировка в `HttpEngineResolver` (TDD,
> red-first в `HttpEngineResolverTest`); (2) субмодули `engine-okhttp` /
> `engine-apache5` — перенос прототипов из benchmarks, доведение до контракта
> паритета (timeout/stream/progress/trailers/close), ServiceLoader-регистрация,
> WireMock-тесты. benchmarks — на `project(':engine-*')`.

> Эпик Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055, `docs/0.10.0/`) закрыт (сессия #135):
> - В `MethodMetadata` предвычислены и закэшированы: `staticCookieHeader` (строка формата `k=v; k2=v2`), флаг `hasCookieParams`, флаги `hasExplicitContentTypeInStaticHeaders`, `isStaticConditional` и `hasTrace`.
> - В `HttpTemplateBuilder.resolveCookies()` добавлен fast-path: при отсутствии динамических cookie-параметров возвращается `methodMetadata.staticCookieHeader()` напрямую без аллокаций `LinkedHashMap` (0 heap allocations).
> - В `HttpTemplateBuilder.createHeaders()` добавлен fast-path для статических запросов: при отсутствии динамических заголовков, куки и auth список `staticHeaders` возвращается напрямую без создания промежуточных копий `ArrayList`.
> - В `HttpTemplateBuilder` аудит auth-заголовков переведен на `List.of()` и плоский цикл, устранив Stream API из `authHeaderNames`.
> - Для методов без query параметров `createRequestParams()` пропускается, устанавливая неизменяемый синглтон `List.of()`.
> - В `HttpTemplate.createUri()` и `HttpTemplateBuilder.createUrlEncodedBody()` сборка параметров переведена с Java Stream API на прямые плоские циклы с `StringBuilder`.
> - В `HttpTemplate.processResponse()` аллокация `Duration.ofNanos(System.nanoTime() - startNanos)` сделана строго ленивой (создается только при активном перехвате `ResponseHeaders`).
> - В `HttpTemplate.exchange()` массив `attemptsHolder` аллоцируется строго лениво (только при активных метриках или circuit breaker).
> - `HttpEngineOptions` предвычисляется один раз в `HttpTemplateBuilder` и кэшируется в `HttpTemplate`, устраняя повторные аллокации на каждый send/hop.
> - В `AbstractHttpClient.isLoginInBackgroundNeeded()` проверка `isAuthorized()` вынесена на первое место, устраняя избыточные вызовы Reflection API на авторизованных запросах.
> - В `HttpTemplateBuilder.resolveCompression()` задействован синглтон `DEFAULT_NO_COMPRESSION`.
> - Замеры JMH с `-prof gc`: аллокации на `jrestly_getUser` снизились с **49898 B/op до 49378 B/op**, на `jrestly_ping_zeroDepRaw` снизились с **48030 B/op до 47392 B/op**.
> - Достигнута отметка **1035/1035 тестов** в 134 классах (+9 новых тестов), 0 warnings под `-Werror`, POM строго zero-dep.
> Следующий ход — свободный, выбор из активной зоны.

> **Сессия #139 (аудит на скрытые проблемы, вопрос Антона):**
> - Найден и исправлен **реальный скрытый недетерминизм**: `MethodMetadata.parseStaticCookies()`
>   возвращал `Map.copyOf(LinkedHashMap)`, чей порядок итерации соляризуется на каждый запуск JVM
>   → заголовок `Cookie:` менял порядок параметров между рестартами (проявилось как падение
>   `MethodMetadataTest`/`ZeroGarbagePipelineTest`, пропущенное в #135 из-за удачного солта JVM).
>   Фикс: `Collections.unmodifiableMap(LinkedHashMap)` — порядок декларации сохранён.
> - Исправлена WHATWG-совместимость SSE: ведущий UTF-8 BOM первой строки теперь срезается
>   (раньше первое поле молча терялось) — `ServerSentEventParser` + тест.
> - **1036/1036 тестов** (+1), 0 warnings, javadoc 0 warnings.
> Остальной срез аудита (утечки потоков, ThreadLocal, проглоченные исключения, reactive publishers,
> retry-переполнения) — чисто.

> **Сессия #140 (аудит, срез «charset-обработка ответов»):**
> - Найден и исправлен **разлад charset-обработки между стриминговым и буферным путями**:
>   стриминг (`Stream<String>`, SSE) давно уважал `charset` из `Content-Type`, а буферные
>   пути (`String`, `Optional<String>`, `@OnError`, `UnexpectedStatusException.getRawBody()`,
>   `consumePayload`, `extractResponseBodyString`) жёстко декодировали UTF-8 →
>   один и тот же эндпоинт давал корректный текст в стриме и модзибейк в `String`
>   (реальные API с `windows-1251`/`ISO-8859-1`).
>   Фикс по TDD (RFC 9110 §8.3.2): `payloadCharset = resolveCharset(responseHeaders)` один раз
>   на ответ, все текстовые материализации — через него; fallback UTF-8 без падений.
>   Оформлен `docs/0.10.0/ADR-0056-response-charset-resolution-for-string-payloads.md`.
>   +6 E2E-тестов (`ResponseCharsetWireMockTest`), **1042/1042**, 0 warnings.
> **Сессия #141 (аудит, стриминг декомпрессии и upload progress):**
> - Найден и исправлен **баг стриминговой декомпрессии в `HttpTemplate.handleDecompression`**:
>   при наличии кастомного `ContentCompressor` (SPI ADR-0041) и стримингового ответа (`InputStream`,
>   `Stream<String>`, `Stream<ServerSentEvent>`) `HttpTemplate` безусловно оборачивал стрим в
>   `new InflaterInputStream`, полностью игнорируя кастомный компрессор и приводя к `ZipException`.
>   Фикс: добавлен `default InputStream decompress(InputStream stream)` в `ContentCompressor`,
>   реализован в `GzipContentCompressor` и `DeflateContentCompressor`, поддержан в `HttpTemplate`.
> - Найдена и исправлена **асимметрия прогресса загрузки пустых тел в `ProgressBodyPublisher`**:
>   JDK `HttpClient` оптимизирует запросы нулевой длины (`Content-Length: 0`) и не создает подписку на
>   Flow Publisher. Теперь `ProgressBodyPublisher` и `JdkHttpEngine` гарантируют отправку `(0, 0)`
>   события прогресса ровно один раз через thread-safe CAS guard.
> - Оформлен `docs/0.10.0/ADR-0057-streaming-decompression-spi-and-zero-length-upload-progress.md`.
> - +5 тестов (`RequestCompressionWireMockTest`, `ContentCompressorTest`, `ProgressBodyPublisherTest`,
>   `UploadProgressWireMockTest`), всего **1047/1047 тестов**, 0 warnings под `-Werror`, zero-dep core.
> 
> **Сессия #142 (аудит, SSE-парсинг против WHATWG dispatch algorithm):**
> - Найдено и исправлено 4 отклонения в `ServerSentEventParser` (HTML §9.2.5):
>   (1) блоки без `data:` (только `id:`/`event:`/`retry:`) порождали фантомные события
>   с `data == null` — реальные серверы шлют так control/keep-alive блоки (`retry: 3000\n\n`,
>   `event: ping\n\n`), и `.map(e -> codec.deserialize(e.data(), ...))` падал NPE;
>   по WHATWG пустой data-буфер отменяет dispatch и сбрасывает тип события;
>   (2) last-event-ID теперь stream-level состояние: персистится между событиями
>   (empty `id:` сбрасывает) — `[DONE]`-событие LLM-стрима больше не «забывает» последний id;
>   (3) `id`, содержащий U+0000, игнорируется; (4) `retry` принимает только ASCII-цифры
>   (`+5`/`-7` раньше молча проходили через `Long.parseLong`) и персистится как
>   эффективное время переподключения.
> - TDD: 5 новых кейсов в `ServerSentEventTest` (red-first подтверждён на старом парсере),
>   обновлены last-event-ID ожидания в `ServerSentEventTest`, `ServerSentEventsAndLineStreamingTest`,
>   `HttpTemplateLineAndEventStreamTest`.
> - `ADR-0016` дополнен секцией Amendment; Javadoc `ServerSentEvent`/`ServerSentEventParser` уточнён.
> - **1052/1052 тестов** (+5), 0 warnings, javadoc чист.
> 
> **Сессия #143 (старт кандидата №3 — сравнительный бенчмаркинг):**
> - Доукомплектована матрица сравнения: raw-OkHttp бейзлайн `OkHttpBenchmarkClient`
>   и варианты движков jRestly в однопоточном `HttpNetworkBenchmark`
>   (`jrestlyOkHttp_*`, `jrestlyApache5_*`, `jrestlyHttp11_*`) — паритет с
>   `ConcurrentLoadNetworkBenchmark`; OkHttp задекларирован явно 4.12.0
>   (был транзитивный 3.14.9 от Retrofit).
> - **🎯 Главная находка: сетевые бенчмарки измеряли 42-мс TCP-артефакт, а не библиотеки.**
>   `com.sun.net.httpserver` не ставит `TCP_NODELAY`, ответ пишется в два приёма →
>   на keep-alive соединении вторая запись ждёт delayed ACK ~40 мс; все десять
>   клиентов показывали плоские 44–48 мс/op. Зонды вне JMH: keep-alive сырым
>   сокетом = 43.7 мс/op; тот же сервер с `TCP_NODELAY=true` = 1.85 мс/op (×23).
> - **Публичные цифры нельзя снимать до починки стенда.** Следующий шаг — zero-dep
>   raw-TCP сервер с `setTcpNoDelay(true)` вместо `HttpServer`, тест на keep-alive
>   латентность, затем перезапуск матрицы.
> - **1052/1052 тестов** (ядро не менялось, `:benchmarks:test` зелёный).
> 
> Следующий свободный ADR — **0059**.

> **Сессия #155 (ADR-0058 — фаза 2a WIP):** рабочее дерево от прерванных
> сессий #150–#154 (коммита не было!) содержало субмодуль `engine-okhttp`
> (движок, провайдер 50, ServiceLoader, build.gradle, 21 тест паритета),
> миграцию `benchmarks` на `project(':engine-okhttp')` и `settings.gradle`.
> Найден и починен **дедлок `PublisherInputStream`** (одноразовый
> `END_OF_STREAM`: буферный `read` съедал маркер, следующий `read` вечно
> поллил пустую очередь) — именно он вечно вешал `./gradlew test` и убивал
> прошлые сессии до коммита; плюс латентный баг «ошибка публикатора →
> `continue` без выхода». **Зафиксировано коммитом.** Осталось на #156:
> 4 падения паритета `:engine-okhttp:test` (нулевой download-event,
> `total = -1` в двух прогресс-тестах — ведущая гипотеза: прозрачный gzip
> OkHttp vs своя декомпрессия ядра ADR-0021; multipart text-part), затем
> `engine-apache5`, полный прогон `./gradlew test` и публичная
> бенчмарк-документация.

> **Сессия #156 (ADR-0058 — фаза 2a закрыта):** 4 падения `:engine-okhttp:test`
> разобраны до конца, субмодуль полностью зелёный (**23/23**). Корни — два, и оба
> не там, где ждали: (1) прогресс (`total == -1`, нулевой event) — OkHttp
> `BridgeInterceptor` инжектит `Accept-Encoding: gzip` и прозрачно распаковывает
> ответ, обнуляя `Content-Length` и скрывая `Content-Encoding` (ломался и
> `total` ADR-0033, и `isDecompressionEnabled()==false` ADR-0021);
> (2) multipart — сам тест ждал text-часть без канонической строки
> `Content-Type: text/plain; charset=UTF-8` (сверено с core-эталоном
> `MultipartTest.expectedLegacyBody`). Плюс стенд-находка: WireMock 3.12 (Jetty)
> без явного `Content-Length` в стабе всегда отвечает chunked (проверено curl
> через standalone) — core-тесты прогресса декларируют CL явно, parity-тесты
> нет. Фикс — **пассивный транспорт**: `NoTransparentGzipInterceptor` (application:
> gzip + tag-маркер против Bridge) + `StripSuppressedAcceptEncodingInterceptor`
> (network: снимает перед проводом) → точный паритет с `JdkHttpEngine` (заголовка
> нет вовсе); явный `Accept-Encoding` вызывающего проходит как есть, ответ —
> сжатым, декомпрессия остаётся за `ContentCompressor` SPI ядра. Red-first
> подтверждён зондом и красным тестом `noAcceptEncodingInjectedByTransport`.
> ADR-0058 дополнен секцией Amendment. **1086/1086** (core 1048 + engine 23 +
> bench 15), 0 warnings; заодно впервые зелёным стал javadoc-гейт субмодуля
> (незаимпортированный `JdkHttpEngine` в `{@link}` + implicit default
> constructor провайдера). Дальше — `engine-apache5` (фаза 2b) и публичные
> бенчмарки.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals):
  Наведение лоска и лаконичного javadoc в тестовых классах.
  **Батч №1 (#185):** `resilience` (3) + `auth` (4) = 7 классов.
  **Батч №2 (#201):** `cookie` (2) + `codec` (2) + `compression` (3) +
  `tracing` (3) + `interceptor` (4) = 14 классов.
  **Батч №3 (#202):** `ru.jrestly.http` — все 18 классов пакета; пакет закрыт.
  **Батч №4 (#203):** корневой `ru.jrestly` — 18 классов (глаголы, редиректы,
  статусы, таймауты, ядро-фикстуры).
  **Батч №5 (#204):** корневой `ru.jrestly` — 19 классов (кодеки/JSON,
  валидация, прогресс, стриминг, сжатие/трассировка, прокси/auth).
  **Батч №6 (#205):** корневой `ru.jrestly` — 25 классов (добран до нуля) +
  8 внекорневых тест-классов (`engine` 3, `json` 1, субмодули `codec-jackson-xml`
  2, `benchmarks` 2) = **33 класса**.
  **✅ Кандидат №1 закрыт:** во всём дереве без class-level javadoc остались
  только **15** вспомогательных типов `ru.jrestly.fixtures` (не тест-классы —
  осознанный non-goal; при желании — отдельный мелкий батч «fixtures-javadoc»).

- **Кандидат №2 — HTTP Trailers & Advanced Streaming ergonomics**:
  Расширенные сценарии работы с трейлерами и чанками в стриминге данных (LLM / SSE).
  **Завершён: ADR-0059** (`docs/0.10.0/ADR-0059-sse-stream-resilience-and-streaming-trailer-completion.md`).
  Фазы:
  - **Ф1 — сделана (#161)**: `ServerSentEvent.reconnecting` + `ReconnectPolicy`/`ReconnectInfo`
    (EventSource-семантика: `Last-Event-ID` replay, серверный `retry:`/`Jitter`, `stopOnDone`,
    bounded attempts, `onReconnect`);
  - **Ф2 — сделана (#163)**: ленивые трейлеры на стриминговом пути (`WireResponse.trailersSupplier`
    + `ResponseHeaders.populateTrailers` + терминальный `TrailerCompletingInputStream` + engine parity);
  - **Ф3 — сделана (#175)**: `stallTimeout` (idle-stall guard), спайк пройден на всех трёх
    транспортах → keep.
  Следующий ход — свободный, выбор из активной зоны.

- **Кандидат №3 — OkHttpEngine / ApacheHttpEngine external modules** (ADR-0058):
  Вынос внешних коннекторов в отдельные субмодули для максимальной скорости под нагрузкой.
  *(Развилка Антона, 2026-10-15: добить бенчмаркинг с OkHttp/Apache, убедиться что jRestly
  на вершине возможной производительности, сравнить с другими библиотеками и оформить
  публичную документацию по результатам — после текущих задач.)*
  **В работе (сессии #143, #145, #147).** Стенд починен (сессия #145): `BenchmarkHttpServer`
  переписан на zero-dep raw-TCP HTTP/1.1 (`ServerSocket` + виртуальные потоки,
  `setTcpNoDelay(true)`, ответ одним `write`, keep-alive) — артефакт Nagle+delayed-ACK
  (~42 мс/запрос) устранён. Регрессионный тест `BenchmarkHttpServerTest` (6 кейсов,
  латентностный гейт min < 25 мс) краснеет на старом сервере (`43.9 ms`) и зелёный на новом.
  **Сессия #147: полный прогон матрицы (10 клиентов × getUser/ping) + разбор диспропорции.**
  Честные цифры (JMH, 1 fork, 2 wi + 3 iter, µs/op; variance высокая — машина загружена):
  getUser: apache 341, retrofit 362, okHttp 428, **jrestly(JDK) 1524**, jrestly(OkHttp) 450,
  jrestly(Apache5) 478, jrestly(HTTP/1.1) 1636, raw JDK 1364, feign 2223, spring 2591;
  ping: okHttp 254, retrofit 312, jrestly(Apache5) 331, jrestly(OkHttp) 342, apache 363,
  raw JDK 1191, jrestly(HTTP/1.1) 1221, **jrestly(JDK) 1359**, feign 1803, spring 2144.
  `DispatchOverheadBenchmark`: диспетчеризация jRestly in-memory = **5–9 µs** (vs 1.3 µs raw Jackson)
  → декларативный слой не источник разрыва. **Вывод: диспропорция jrestly vs okHttp — это
  сам транспорт JDK HttpClient** (raw JDK 1364 vs okHttp 428 µs — те же 4× у «голого» клиента);
  jRestly поверх OkHttp-движка (450 µs) — на уровне raw OkHttp (428 µs, +5%).
  Гипотеза h2c-upgrade не подтвердилась: пинning HTTP/1.1 не ускорил (1636 vs 1524, шум).
  Следующий шаг: ADR-0058 (внешние субмодули OkHttpEngine/ApacheHttpEngine) + публичная
  документация по бенчмаркам; до неё — опционально повторный прогон с `-i 5 -f 2`
  для снижения variance и зонд 160 µs overhead декларативного слоя на сетевом пути.
  **Сессия #148: ADR-0058 оформлен** (`docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md`):
  два публикуемых субмодуля `jrestly-engine-okhttp` / `jrestly-engine-apache5`
  (зависимость `api` на свой транспорт, ядро zero-dep), автообнаружение через
  ServiceLoader (`JdkHttpEngine` — не провайдер, остаётся fallback'ом),
  `default int priority()` в `HttpEngineProvider` (стабильная сортировка в
  `HttpEngineResolver`), контракт паритета по полям `HttpEngineOptions`
  (timeout/stream/progress/trailers/close + маппинг `ModuleInfo`: SSL, cookies,
  proxy, authenticator, virtual threads), адаптер выкачивания JDK
  `BodyPublisher` → `InputStream` (единый для стриминга и multipart).
  benchmarks переключаются на `project(':engine-*')`, копии прототипов удаляются.
  **Сессия #149: фаза 1 (ядро) закрыта** — `priority()` + стабильная сортировка,
  1063/1063. **Сессия #156: фаза 2a закрыта** — `engine-okhttp` полностью зелёный
  (23/23), пассивный транспорт (без прозрачного gzip). **Сессия #157: фаза 2b
  закрыта** — `engine-apache5` зелёный (23/23), пассивный транспорт через
  `disableContentCompression()`; оба субмодуля в строю. **Сессия #158: публичная
  бенчмарк-документация оформлена** (`BENCHMARKS.md`), ADR-0058 → Accepted, но
  всплыл Finding #158 (резолвер не подключён). **Сессия #159: Finding закрыт** —
  `createConnection()` делегирует `HttpEngineResolver.resolve`; e2e-гейт
  `SpiEngineAutoDetectionTest`; JDK-строка бенчей закреплена явно.
  **ADR-0058 полностью завершён.**

- **Кандидат №4 — XML & pluggable payload formats (ADR-0060)** *(Антон, 2026-10-16:
  «надо не забыть про поддержку xml… хорошо бы вообще поддержать кастомный формат.
  Если пользователю нужно, он его реализует и продолжат пользоваться нашей библиотекой»).*
  **Концепт оформлен в #177** —
  `docs/0.10.0/ADR-0060-pluggable-payload-formats-and-media-type-aware-codec-spi.md`
  (Proposed). Пробел подтверждён по коду + зондом: формат payload захардкожен
  под JSON. `RequestType` — enum из трёх значений; DTO-тело идёт через
  JSON-кодек только в ветке `APPLICATION_JSON`, при этом `@ContentType` лишь
  подменяет заголовок → `@ContentType("application/xml")` + DTO шлёт **JSON под
  XML-заголовком** (зонд #177). Response тоже декодируется только JSON-кодеком
  (5 call-site'ов в `HttpTemplate`), `Content-Type` ответа игнорируется. SPI
  назван/сформован JSON-only (`JsonCodec`/`JsonCodecResolver`/`prettyPrint`).
  Решение ADR-0060: `ru.jrestly.codec.PayloadCodec` (media-type ↔ значения) +
  `CodecRegistry` (`resolveOrDefault` → JSON-by-default для обратной
  совместимости), `JsonCodecAdapter` мостит нетронутый `JsonCodec`,
  fail-fast на запросе при незарегистрированном media-типе; XML-адаптер как
  внешний субмодуль, zero-dep ядро. Non-goals: парсер XML/XSD в ядре, полный
  content-negotiation, изменение raw-путей. Фазы: Ф1 SPI ядра / Ф2 XML-адаптер
  / Ф3 ServiceLoader-автоподбор.
  **Ф1 — сделана (#178):** SPI ядра + селекция запрос/ответ + fail-fast;
  +11 тестов, **1164/1164**, javadoc зелёный, 0 warnings. **Ф2 — сделана (#179):**
  субмодуль `jrestly-codec-jackson-xml` (`JacksonXmlCodec` над `XmlMapper`,
  claim `application/xml`/`text/xml`/`*+xml`), +14 тестов, **1178/1178**,
  javadoc зелёный, 0 warnings; **ADR-0060 → Accepted**. **Ф3 — сделана (#180):**
  SPI `PayloadCodecProvider` + `PayloadCodecResolver` (ServiceLoader,
  `priority()`), `JacksonXmlCodecProvider` в субмодуле — XML подключается без
  вызовов, +9 тестов, **1187/1187**. **Кандидат №4 закрыт полностью.**

- **Кандидат №5 — декомпозиция `HttpTemplate` (ADR-0066, `docs/0.11.0/`)** *(открыт
  сессией #270 по вопросу Антона об организации внутренней структуры)*:
  god-класс `HttpTemplate` (2825 строк / 48 полей / 63 метода) распиливается
  инкрементально на package-private коллабораторы в `ru.jrestly.http`, по одной
  когезивной ответственности на сессию, без изменения публичного API и швов.
  - **Шаг №1 — сделана (#270):** `RetryAfter` (парсер `Retry-After`, три даты
    RFC 9110) извлечён; `package-info.java` для всех 15 пакетов.
  - **Шаг №2 — сделана (#271):** политика редиректов вынесена в package-private
    коллаборатор `Redirects` (`nextHop`, `dropAuthHeaders`, `logExhausted`,
    `location`, `isCrossOrigin`, `effectivePort`); `RedirectsTest` (8 тестов)
    покрывает коллаборатор на 100% ветвей. Публичный API не изменён.
  - **Шаг №3 — сделана (#274):** resolver Problem Details
    (`resolveProblemDetail`/`isProblemJsonContentType`/`isLikelyProblemDetail`/
    `instantiateThrowable`) вынесен в package-private `ProblemDetailResolver`
    (чистый `static`-хелпер: кодек/media-тип передаются аргументами). Завершение
    WIP #272–#273 + находка: вынос `responseCodec(...)` из охранного `try`
    резолвера ронял bare-template на unexpected-status (`NPE: jsonCodec`) —
    вылечено best-effort `responseCodecOrNull`. `HttpTemplate` 128 → 122 missed.
    Публичный API не изменён.
  - **Шаг №4 — сделана (#275):** dispatch кэша (`handleCacheResponse`,
    `toStreamWireResponse`) вынесен в package-private `CacheDispatch`
    (чистый static-хелпер: store/`streamBody`/запрос/ответ — аргументы);
    шов переехал в свой сьют `CacheDispatchTest` (12 тестов, 0 missed ветвей).
    Публичный API не изменён.
  - **Шаг №5a — сделана (#276):** примитивы декодирования тела ответа
    (`resolveCharset`/`parseCharsetFromContentType`/`isStreamType`/
    `consumePayload`/`extractText`) вынесены в package-private `ResponseBody`
    (чистый static-хелпер); шов переехал в свой сьют `ResponseBodyTest`
    (8 тестов, 0 missed ветвей). `HttpTemplate` 122 → 115 missed.
  - **Шаг №5b — сделана (#277):** стриминговые артефакты
    (`lineStream`/`eventStream`/`wrapForTrailers`/`isMissingResource`/
    `streamingPayloadLine`/`closeQuietly`) вынесены в `ResponseBody`; швы
    переехали в тот же сьют (20 тестов, **68/68 ветвей, 0 missed**);
    TDD-находка №38 — NPE при `Content-Length` без значения (юнит-матрица).
    `HttpTemplate` 115 → **108** missed. Публичный API не изменён.
  - **Шаг №5c — сделана (#281):** буферное декодирование вынесено в
    package-private `ResponseBody` (`bufferedOptional`/`bufferedRaw` — две
    зеркальные ноги optional/raw; `contentTypeOf` переехал из шаблона);
    **кодек передаётся лениво** (`Supplier<PayloadCodec>`) — на raw-биндингах
    не резолвится вовсе (bare-template выживает), на DTO-ноге — дважды по
    числу использований, как раньше. Сьют `ResponseBodyTest` 20 → 33 теста,
    **118/118 ветвей, 0 missed**. `HttpTemplate` 108 → **95** missed.
    Публичный API не изменён.
  - **Шаг №6 — сделана (#283):** уведомления метрик (`notify*`, 17
    call-site'ов) вынесены в package-private `MetricsNotifier`
    (чистый static-хелпер: `(metricsListener, logger)` аргументами на каждом
    call-site → mutable-сеттеры не ломают коллаборатора); шов ADR-0036 #193
    переехал в renamed-сейт `MetricsNotifierBytesTest` (8 тестов); новый
    `MetricsNotifierTest` (22 теста) — матрица ветвей четырёх точек
    диспетчеризации; 39/40 ветвей (1 документированная недостижимая
    defensive). `HttpTemplate` 95 → **82** missed. Публичный API не изменён.
    **План ADR-0066 закрыт полностью** (ADR → Status «план закрыт»,
    Amendment #283); осталось — дистилляция в `ROADMAP.md`.

- **Кандидат №6 — лаконичность публичного API** *(Антон, 2026-10-18 14:37,
  «Браво! …» — после завершения ADR-0066)*: рассмотреть публичный API на
  предмет многословности («вроде даже у Ретрофита он менее многословный?»).
  Обратная совместимость не требуется («публичных обязательств я на себе
  еще не брал!») — анти-комбайн не нарушать, смотреть на объём деклараций
  контроллеров/билдера против Retrofit/OpenFeign/(Spring HTTP Interfaces).
  **Исследование сделано (#285): ADR-0067** (`docs/0.11.0/`,
  Status Proposed) — V1 `path = ` в глаголах / V2 декоративные
  `name = ` в примерах (fallback уже работал, пробел в документации) /
  V3 `${id}` vs `{id}`; предложения P1 (value — рекомендую) / P2
  (безымянные биндинги в примерах — рекомендую, бесплатно) / P3 (`{id}`,
  решение Антона) / P4 (статические query в пути, микропроверка) / P5
  (form/multipart не трогать). **Антон одобрил (#292, mailbox 2026-10-18):
  «зеленый свет… лаконичность перевешивает»** — P1/P2/P4 + P3=`{id}`.
  **P1 — исполнен (#292):** `path()` → `value()` в пяти глаголах + все
  call-site'ы (99 файлов), гейт 1,637/1,637, ADR-0067 Amendment #292.
    **P2 — исполнен (#293):** безымянные биндинги в README/примерах/фикстурах
  (32 файла, 93 строки), гейт 1,637/1,637, ADR-0067 Amendment #293; находка
  прогона: fallback ADR-0017 неполон для Map-префикса
  (`rawRequestParamName` в `HttpTemplateBuilder`) — хвост в беклог.
  **P3 — исполнен (#294):** плейсхолдер `${id}` → `{id}` (маска/экстрактор/
  replace/тесты/README/javadoc; +1 тест, гейт 1,638/1,638), ADR-0067
  Amendment #294. **P4 — исполнен (#295):** микропроверка статических query
  в пути (e2e `RequestParameterFlexibilityTest` + баг-фикс двойного `?` при
  сшивании `@TargetUrl` с query и query пути; гейт 1,644/1,644), ADR-0067
  Amendment #295. Хвост Map-prefix — решён (#296, «задокументировано by
  design», ADR-0017 Amendment #296). **Кандидат №6 закрыт полностью**
  (P1–P4; P5 осознанно не трогаем).
- **Кандидат №7 — бенчмарк на rss/atom-движке** *(Антон, 2026-10-18 14:37 —
  после ADR-0066)*: реализовать движок парсинга rss/atom и сравнить с
  конкурентами в реальном сценарии: ops/sec, CPU, mem-usage; белые пути
  оптимизации. Требует (как минимум) нового синтетического «протокола» —
  обсудить объём с Антоном до старта.
- **Кандидат №8 — ревизия старого синтетического бенчмарка** *(Антон,
  2026-10-18 14:37 — после ADR-0066)*: после всех рефакторингов и погони
  за покрытием — перепроверить «типовой метод» на старом стенде:
  не убежали ли мы от самого главного (нет ли регрессии dispath-оверхеда
  и аллокаций после Zero-Garbage/ADR-0055/декомпозиции `HttpTemplate`).
  **✅ Ревизия проведена (#287):** dispatch 6.63 µs/op (коридор 5–9), аллокации
  29,136 / 26,609 B/op (ниже базы ADR-0055 49,378 / 47,392), группы сетевой
  матрицы intact — секция «Revision check (run #287)» в `BENCHMARKS.md`.

**Активная зона:** кандидат №5 (ADR-0066) **закрыт и дистиллирован** (#284) —
все шесть шагов сделаны (№1–№4, №5a/5b/5c, №6; see выше; гейт 1,636/1,636),
эпик перенесён в `ROADMAP.md` (паттерн #206). **Кандидат №6 (ADR-0067)
закрыт полностью:** P1 (#292), P2 (#293), P3 (#294), **P4 (#295)** —
микропроверка статических query в пути (зелёная e2e + баг-фикс двойного `?`
при сшивании `@TargetUrl` c query и query пути); хвост Map-prefix fallback —
**решён (#296**, «задокументировано by design», ADR-0017 Amendment #296,
wire-формат не менялся). **Кандидат №1 (test-javadoc) закрыт с хвостом
(#297):** class-level javadoc у всех 17 типов `ru.jrestly.fixtures` — в
дереве не осталось классов без class-level javadoc. **Хвост Path/File-тел
метрик — закрыт (#299):** WIP прерванной #298 принят и верифицирован —
engine-reported byte accounting (`WireRequest.knownBodySize`,
`HttpTemplateBuilder.fileSizeBytes`, `MetricsNotifier.requestBytes`),
ADR-0036 Amendment #298, гейт **1,656/1,656**. **Свободный аудит `HttpTemplateBuilder`-кластера — выполнен (#301, #302):** двойной расчёт URL на
ambient-пути TracePropagator закрыт (ADR-0042 Amendment #301, гейт **1,659/1,659**);
dead `default`-ветки в switch `CollectionFormat` устранены, Cookie merging покрыт,
Path knownBodySize проверен, достигнуто 100% METHOD/CLASS coverage (сессия #302, гейт **1,660/1,660**).
**Свободный аудит ResponseHeaders & CircuitBreakers — выполнен (#303):** устранены dead-checks
в `ResponseHeaders` (version, firstTrailer, declaredTrailers, firstValue, parseCookies, cookieMap),
устранена dead else-ветка в `CircuitBreakers.evaluateHalfOpenTransition()`, покрыт
`CircuitBreakerOpenException(null)`, гейт **1,661/1,661**, BRANCH достигла **89.98%**.
Следующий ход: хвост byte[]-скаляра в headers/params (решить wire-формат) или аудит engine/tracing/codec.
**Кандидат №8 проверен (#287): регрессии dispatch/аллокаций нет** (см.
пометку выше). №7 (rss/atom) — разблокируется ответом Антона
(вопрос A/B задан в mailbox #284/#285). Highest ADR **0067**, next free **0068**.
Хвосты-кандидаты
из аудит-нити (не эпики): валидация `@ExpectStatus`/`@FollowRedirects`
(инварианты нигде не объявлены — фича, не дыра; #207, трогать лишь при
тихом исходе). **Path/File-тела метрик — закрыты в #299** (WIP прерванной
#298: engine-reported byte accounting, ADR-0036 Amendment #298, гейт
1,656/1,656).
**Семантика `Duration.ZERO`-таймаута закрыта в #213**: `0` формально означает
«без таймаута» на всех движках (ADR-0027 Amendment #213), `@ExpectStatus`/
`@FollowRedirects` остаются решением #207 «фича, не дыра». **`@MultipartFormFile.partName`
закрыт в #209** (был в этом списке) — семья name-bearing required-атрибутов
теперь валидируется eagerly полностью. **#210 открыл новую аудит-ось
«парные декларативные конструкции»** — путь глагола ⇄ `@PathVariable` теперь
сверяются eagerly (ADR-0025 Amendment #210), а **в #211 закрыта форма самой
половины**: `@Get/Post/... path()` проверяется на URI-легальность той же
проверкой, что реально скормит фреймворк (`URI.create` с маскированными
`${...}`; ADR-0025 Amendment #211) — пробел/CRLF/`{id}`/битый `%` больше не
доживают до отправки. **#212 добавил третий виток той же оси — «декларация ⇄
требование окружения сборки»**: `name()`-fallback параметрных биндингов
(`@RequestParam`/`@RequestHeader`/`@Cookie`/`@PathVariable`) требует флага
`-parameters`, иначе молча уезжает как `arg0` (ADR-0017 Amendment #212).
Кандидаты по той же оси: `@ExpectStatus(statuses = {})`/`@FollowRedirects(count < 0)`
(пустой/отрицательный декларативный вход — решение #207 «фича, не дыра»);
проверить, нет ли других деклараций, чья корректность зависит от флагов
компиляции/окружения. **#214 — четвёртый виток оси, но с другой грани: пара
была проверена лишь с одной стороны** (`MULTIPART_FORM_DATA` без параметров
резался, а `@MultipartFormFile` без `MULTIPART_FORM_DATA` — тихо выбрасывался;
ADR-0024 Amendment #214). Приём: у парной проверки сверять **обе** стороны —
асимметричный guard так же опасен, как его отсутствие. **#215 замкнул ту же
пару с другой половины:** payload-биндинг `@RequestBody` читается только на
`APPLICATION_JSON`-пути, и на form-urlencoded/multipart-методе аргумент тихо
выбрасывался — теперь требует `requestType = APPLICATION_JSON` (ADR-0035
Amendment #215). Семья «payload-аннотация ⇄ requestType» закрыта симметрично
(`@RequestBody` → JSON, `@MultipartFormFile` → MULTIPART). Кандидаты по той же
оси / нити: `@ExpectStatus(statuses = {})`/`@FollowRedirects(count < 0)`
(пустой/отрицательный декларативный вход — решение #207 «фича, не дыра»,
трогать лишь при тихом исходе); составной guard среди не-hub-биндингов —
поправка #216: пара `@MultipartFormFile` + `@RequestParam` **не** double-binding,
а тихий drop (закрыта, ADR-0024 Amendment #216); оставшиеся не-hub-пары
(`@PathVariable`/`@RequestHeader` × `@MultipartFormFile`/`@RequestParam`) реально
double-bind'ят (оба биндинга срабатывают) — не трогали; другие декларации,
зависящие от окружения сборки (#212).

---

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
  лучше — в этом файле и вообще на проекте»). Практика: mailbox не растёт
  бесконечно (применять правило «решённый обмен удаляем» в каждой сессии,
  где он применим); устаревшие «Пометки из mailbox» в DRAFT сверять с
  реальностью; мелкие расхождения (версии, статусы) чинить сразу, не копить;
  ROADMAP — верифицируемые утверждения (счётчики тестов, версии, хэши,
  ссылки) сверять с реальностью.
- **После закрытия эпика DRAFT не пустеет** (сессия #39): кандидаты
  поднимаются в активную зону — следующий ход выбирается из них.
- **Гейт без отрицательного теста — не гейт** (сессия #58).
- **Факты — по коду/дереву/jar, не по имени/памяти** (сессии #47–#57, #64).

Пометки из mailbox (актуальные на 2026-10-17):
- **Автономия + 3 направления (Антон, 2026-10-14 09:10, живая нить):**
  (1) инварианты — zero-dep, прозрачные методы (ADR-0004),
  анти-комбайн (ADR-0003); (2) ниша — идеальный декларативный
  HTTP-клиент для современной Java; (3) эргономика для современных
  API.
- **Векторы зрелости библиотеки (Антон, 2026-10-14 23:25, живая нить):**
  все направления зрелости последовательно реализуются: SSL/mTLS (ADR-0020),
  прозрачный GZIP/Deflate (ADR-0021), доступ к метаданным заголовков (ADR-0022),
  условные запросы & Optional (ADR-0023), гибкость form-data/multipart (ADR-0024),
  шаблоны URI и массивы параметров (ADR-0025), виртуальные потоки (ADR-0026),
  декларативные таймауты (ADR-0027), декларативные интерцепторы (ADR-0029),
  отслеживание прогресса загрузки (ADR-0030), куки и управление сессиями (ADR-0032),
  отслеживание прогресса скачивания и прямой возврат byte[] (ADR-0033),
  форматирование query коллекций (ADR-0034), стриминг тела запроса (ADR-0035),
  SPI метрик и телеметрии (ADR-0036), настройка прокси и аутентификатора (ADR-0037),
  согласование протокола HTTP/2 и трейлеры (ADR-0038), персистентность cookie store (ADR-0039),
  продвинутые стратегии джиттера и ограничения Retry-After (ADR-0040),
  сжатие тела запроса и Content-Encoding SPI (ADR-0041),
  трассировка запросов и проброс Correlation ID / W3C TraceContext (ADR-0042),
  Circuit Breaker и ограничение частоты запросов Rate Limiter SPI (ADR-0043),
  Interceptor @Order Priority (ADR-0044).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
