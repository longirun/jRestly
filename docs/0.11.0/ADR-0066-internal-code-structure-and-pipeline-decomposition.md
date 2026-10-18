# ADR-0066: Internal Code Structure — Package Taxonomy and Incremental Decomposition of the Request Pipeline

## Status

Accepted — план закрыт: все шесть шагов декомпозиции `HttpTemplate` выполнены
(№1 RetryAfter #270, №2 Redirects #271, №3 ProblemDetailResolver #274,
№4 CacheDispatch #275, №5 ResponseBody 5a/5b/5c #276/#277/#281,
№6 MetricsNotifier #283). Дистиллирован в `ROADMAP.md` (сессия #284).

## Context

Антон (mailbox, 2026-10-18) задал прямой вопрос: «у нас всё хорошо с организацией
внутренней структуры кода?». Это ADR — ответ на вопрос: полный аудит структуры
`src/main/java` (146 файлов, ~23 900 строк) и решение о дальнейшем направлении.

### Что уже хорошо (аудит подтвердил)

1. **Таксономия пакетов осмысленна и единообразна.** Каждая сквозная
   возможность живёт в отдельном пакете по своей оси ответственности:
   `annotation` (декларативный контракт), `interceptor`, `metrics`, `tracing`,
   `resilience`, `compression`, `cache`, `codec`/`json`, `cookie`, `engine`,
   `auth`, `problem`. Пакет `http` — внутренний конвейер, корневой `ru.jrestly`
   — публичный фасад и конфигурация. Пересечений ответственности не найдено:
   ни один тип не выполняет обязанности соседнего пакета.
2. **SPI-границы честные.** Внешние движки, кодеки и форматы подключаются
   через `ServiceLoader`-провайдеры в субмодулях; ядро остаётся zero-dep
   (ADR-0001), а интеграции — `compileOnly`/субмодули.
3. **Направление зависимостей предсказуемо.** `ru.jrestly.http` — хаб: он
   потребляет публичные контракты (`annotation`, `interceptor`, `resilience`,
   `compression`, `tracing`, `metrics`, `codec`, `json`) и не протекает
   наружу (ADR-0048). Пакеты-возможности зависят от публичной модели `http`
   (`interceptor → http`, `metrics → http`, `resilience → http`,
   `tracing → http`, `problem → http`, `cache → http`) — это нормальная
   слоистость «возможность → модель».

### Что является реальным долгом

1. **God-класс `HttpTemplate`.** 2825 строк, 48 полей, 63 метода. В одном
   классе живут: сборка/исполнение диспетчеризации, sync/async-контуры,
   редиректы, retry + разбор `Retry-After` (три даты RFC 9110), стриминг,
   charset, декомпрессия, dispatch кэша, Problem Details, инстанциация
   исключений, метрики и логирование. Это единственный существенный дефект
   структуры; все остальные классы ядра — обозримого размера (следующий по
   величине `HttpTemplateBuilder` — 1433 строки, но с одной ответственностью).
2. **Кольцо пакетов `ru.jrestly` ⇄ `ru.jrestly.http`.** `http` импортирует
   `AuthProvider`/`ModuleInfo` из корня, а корень — `HttpTemplateBuilder`/
   `MethodMetadata`/`RetryPolicy` из `http`. Кольцо осознанное и терпимое
   (типы лежат в одном логическом слое, компиляция и понимание не страдают),
   но его наличие стоит зафиксировать явно, чтобы не заводить новых связей.
3. **Пакеты не документированы.** Ни одного `package-info.java` — назначение
   пакета выводилось только по составу файлов.

## Decision

1. **Документировать каждый пакет** через `package-info.java` (сделано в этой
   сессии): краткое описание ответственности, ссылки на ключевые публичные
   типы и на релевантные ADR. Это делает таксономию обнаружимой для
   мейнтейнера и для `javadoc`.
2. **Декомпозировать `HttpTemplate` инкрементально**, по одной когезивной
   ответственности на сессию, в package-private коллабораторы внутри
   `ru.jrestly.http`. Публичный API и существующие package-private швы при
   этом не меняются. Порядок извлечения (от наиболее чистого к наиболее
   связанному с состоянием):
   1. `RetryAfter` — парсинг `Retry-After` (все три даты RFC 9110) — **сделано
      в этой сессии**;
   2. политика редиректов (`nextHop`, `dropAuthHeaders`, `isCrossOrigin`,
      `effectivePort`, `redirectLocation`, `logExhaustedRedirects`) — **сделано
      в сессии #271**;
   3. resolver Problem Details (`resolveProblemDetail`,
      `isProblemJsonContentType`, `isLikelyProblemDetail`,
      `instantiateThrowable`);
   4. dispatch кэша (`handleCacheResponse`, `toStreamWireResponse`);
   5. декодирование тела ответа (charset/streaming/`consumePayload`);
   6. уведомления метрик (`notify*`).
3. **Non-goals.** Не дробить пакеты преждевременно; не вводить DI-контейнер,
   фасады-обёртки или слои абстракции сверх когезии; не перемещать публичные
   SPI-типы между пакетами (это сломало бы импорты пользователей); не трогать
   `HttpTemplateBuilder`/`MethodMetadata` — у них одна ответственность.

## Consequences

- **Плюсы:** «божественный» класс уменьшается по одной ответственности за
  шаг; каждый извлечённый коллаборатор — тривиально юнит-тестируем без сети
  (пример: `RetryAfterTest`); структура пакетов становится самодокументируемой;
  публичный API не меняется ни на байт.
- **Минусы / Замечания:** извлечение обнажает приватные `static`-методы
  `HttpTemplate`, которые тянут за собой общие константы/поля — для каждого
  кандидата нужна проверка, что он не завязан на изменяемое состояние шаблона
  (`RetryAfter` чист: только аргумент, никаких полей).
- **Инварианты:** zero-dep ядро, ADR-0001; инкапсуляция `HttpTemplate`,
  ADR-0048; `-Werror`-гейты компиляции и `javadoc` остаются зелёными.

## Amendment #271

Шаг №2 плана выполнен: политика редиректов вынесена из `HttpTemplate` в
package-private коллаборатор `ru.jrestly.http.Redirects`.

- **Извлечено:** `nextHop`, `dropAuthHeaders`, `logExhaustedRedirects`,
  `redirectLocation` (→ `Redirects.location`), `isCrossOrigin`, `effectivePort`.
  Логика перенесена байт-в-байт; поведение обоих контуров (sync
  `executeWithRedirects` и async `executeAsyncWithRedirects`) не изменилось.
- **Коллаборатор — не статическая утилита, а value-объект конфигурации:**
  три неизменяемых настройки (`followRedirectsNumber`, `authHeaderNames`,
  `logger`) передаются конструктором; `HttpTemplate` строит его лениво
  (`redirects()`), только когда `@FollowRedirects` задан, — **zero-redirect
  hot-path не аллоцирует** (ADR-0055). Async-рекурсия вынесена в отдельный
  `executeAsyncHop`, чтобы коллаборатор и `null`-бюджетный fast-path не
  смешивались.
- **Package-private швы переехали, а не продублированы:** `isCrossOrigin` /
  `effectivePort` теперь на `Redirects` (тонких делегатов на `HttpTemplate`
  не оставлено — тесты обновлены: `HttpTemplateCrossOriginTest` →
  `RedirectsCrossOriginTest`, ссылки на `Redirects`). `HttpTemplate` не
  потерял ни одного публичного типа и не приобрёл dead-code.
- **Юнит-матрица:** новый `RedirectsTest` (8 тестов, без сети) — stop-paths
  (нет бюджета, не-редирект, нет `Location`), downgrade GET vs. preserve
  307/308, cross-origin drop ambient-auth и pass-through при `null`-именах,
  `Location`-lookup с `null`-именованным заголовком, все комбинации
  `logExhausted`. `Redirects` покрыт ветвями на 100% (0 missed).
- **Эффект:** `HttpTemplate` 129 → **128** missed по ветвям; строк/полей/методов
  в god-классе стало на ~90 меньше; JaCoCo не просел — наоборот, BRANCH
  88.77% → **88.80%**, INSTRUCTION 97.66% → **97.67%**, COMPLEXITY 86.71% →
  **86.76%** (METHOD/CLASS — 100%).

## Amendment #274

Шаг №3 плана выполнен: resolver Problem Details вынесен из `HttpTemplate` в
package-private `ru.jrestly.http.ProblemDetailResolver`.

- **Извлечено:** `resolveProblemDetail` (→ `ProblemDetailResolver.resolve`),
  `isProblemJsonContentType`, `isLikelyProblemDetail`, `instantiateThrowable`
  (из `private static` → package-private `static`). Логика байт-в-байт.
- **Коллаборатор — чистый `static`-хелпер** (как `RetryAfter`): он не завязан
  на состояние шаблона — выбранный кодек и его media-тип передаются
  аргументами, поэтому конструктор/поля не нужны. Call-site
  (`createUnexpectedStatusException`) передаёт `contentTypeOf(...)` и
  `responseCodecOrNull(...)`.
- **Находка при завершении WIP (WIP #272–#273 оборвалась до коммита):** перенос
  вытащил `responseCodec(responseHeaders)` из `try` resolver'а **наружу**, и на
  bare-template (без `codecRegistry`/`jsonCodec`) разрешение кодека стало ронять
  `createUnexpectedStatusException` голым `NullPointerException: jsonCodec`
  вместо прежнего тихого пролёта к встроенному парсеру. Поймали e2e-гварды
  `HttpTemplateOptionalStreamTest` / `HttpTemplateStreamWireTest` (4 «сетевых»
  падения — на самом деле чистый unit-путь unexpected-status). Фикс — новый
  private `responseCodecOrNull` (best-effort lookup, `catch (Throwable) → null`),
  возвращающий исходное поведение: ошибка разрешения кодека не прерывает
  Problem Details resolution. Плюс unit-тест «null codec → built-in parser».
  Вывод: при выносе метода проверять **границы его `try`** — вынос из
  охранного блока меняет контракт, даже когда тело метода перенесено дословно.
- **Юнит-матрица:** `HttpTemplateProblemDetailResolutionTest` (30 тестов)
  переведён на прямые вызовы `ProblemDetailResolver` для предикатов/резолвера;
  `handleOnError`-часть по-прежнему гоняется через `HttpTemplate`. `resolve`
  покрыт по обеим сторонам `if (responseCodec != null)` (новый тест с
  `codec == null`). Остальные 5 missed ветвей — известные недостижимые
  defensive-проверки (`instantiateThrowable`: `ProblemDetailException.isAssignableFrom`
  и fallback-конструкторы).
- **Эффект:** `HttpTemplate` 128 → **122** missed по ветвям (новый класс несёт
  5 структурно недостижимых); METHOD 1436 → **1437**, CLASS 145 → **146**
  (100% сохранены); LIVE-гейт 1,578/1,578, javadoc чист под `-Werror`. JaCoCo:
  INSTRUCTION 97.67%, LINE 97.38%, BRANCH 88.78%, COMPLEXITY 86.73%.

## Amendment #275

Шаг №4 плана выполнен: dispatch кэша вынесен из `HttpTemplate` в package-private
`ru.jrestly.http.CacheDispatch`.

- **Извлечено:** `handleCacheResponse` (→ `CacheDispatch.handle`) и
  `toStreamWireResponse` (→ `CacheDispatch.toStreamWireResponse`). Логика
  перенесена байт-в-байт; поведение обоих контуров (sync-ветка `exchange` и
  async-ветка `thenApply` над `authGuardedFuture`) не изменилось.
- **Коллаборатор — чистый `static`-хелпер** (как `RetryAfter`/`ProblemDetailResolver`):
  он не владеет состоянием шаблона — store (`HttpCache`), `streamBody`-флаг и
  сам запрос/ответ передаются аргументами. Это снимает вопрос мутабельного поля
  `httpCache` (`setHttpCache`): коллаборатор читает переданный store, а не поле.
- **Швы переехали, а не продублированы:** `HttpTemplateCacheDispatchTest` →
  `CacheDispatchTest` (12 тестов, включая новый прямой тест
  `toStreamWireResponse`); тонких делегатов на `HttpTemplate` не оставлено.
- **Юнит-матрица:** stop-path при `cache == null`/`null`-аргументах (zero-garbage
  fast path), 304 → синтезированный 200 и проход сырого 304 при вытесненной
  записи, стриминг-компакция (re-wrap в `ByteArrayInputStream` и отсутствие
  re-wrap при `null`-теле), `put` для 200/301/404, no-op для прочих статусов,
  invalidation для POST/PUT/PATCH/DELETE на 200..399 и no-op за пределами
  диапазона. `CacheDispatch` — 0 missed ветвей.
- **Эффект:** CLASS 146 → **147** (100% сохранены); LIVE-гейт **1,579/1,579**,
  javadoc чист под `-Werror`. JaCoCo: INSTRUCTION 30,703/31,436 (97.67%),
  LINE 6,677/6,857 (97.37%), BRANCH 3,950/4,447 (**88.82%** — новый рекорд),
  COMPLEXITY 3,205/3,693 (86.79% — новый рекорд), METHOD 1437/1437.

## Amendment #276

Шаг №5 плана начат и разбит на под-шаги; выполнен первый (5a) — примитивы
декодирования тела ответа вынесены из `HttpTemplate` в package-private
`ru.jrestly.http.ResponseBody`.

- **Почему под-шаг.** Полный шаг №5 (весь `processResponse`) — самый связанный с
  состоянием шага плана: приватный хаб читает сразу `onErrorParser`,
  `isExpectedStatus`/`createUnexpectedStatusException`, `handleOnError`,
  `returnType`, `optional`, `logger`, `responseHeadersHolder`, `responseCodec`.
  Вместо рискованного одномоментного выноса 200-строчного метода сначала
  вынесены его **чистые примитивы** (не читают состояние шаблона), а
  `processResponse` остаётся и постепенно худеет на следующих под-шагах.
- **Извлечено (байт-в-байт):** `resolveCharset` → `ResponseBody.resolveCharset`
  (RFC 9110 §8.3.2, ADR-0056), `parseCharsetFromContentType` (private),
  `isStreamType` → `ResponseBody.isStreamType` (ADR-0016), `consumePayload` →
  `ResponseBody.consumePayload` (ADR-0015 Aspect 3), `extractResponseBodyString`
  → `ResponseBody.extractText`.
- **Коллаборатор — чистый `static`-хелпер** (как `RetryAfter`/`ProblemDetailResolver`/
  `CacheDispatch`): ни один метод не читает поля шаблона, все входы — аргументы,
  конструктор не нужен. Вызов `isStreamType` из `MethodMetadata` (проверка
  стримингового возврата, ADR-0016) перенаправлен на `ResponseBody`.
- **Швы переехали, а не продублированы:** charset-матрица (12 ассертов) ушла из
  `HttpTemplateLineAndEventStreamTest` в собственный безсетевой `ResponseBodyTest`
  (8 тестов); добавлены прямые матрицы `isStreamType` (включая не-
  `ParameterizedType`, чужой raw-тип и неверную арность), `consumePayload`
  (декодирование + закрытие стрима + пустой → `null`) и `extractText`
  (`null`-ответ/тело → `""`, honor charset). Грабли ветвей: недостижимые на
  happy-path ветви `parseCharsetFromContentType` (`eq == 0`/`eq == -1`, оборванная
  кавычка `charset="utf-8`, одиночная `charset="`) закрыты отдельным тестом
  искажённых параметров.
- **Юнит-матрица:** `ResponseBody` — **0 missed ветвей** (40/40).
- **Эффект:** `HttpTemplate` 122 → **115** missed по ветвям; CLASS 147 → **148**
  (100% сохранены); LIVE-гейт **1,586/1,586**, javadoc чист под `-Werror`.
  JaCoCo: INSTRUCTION 30,703/31,436 (97.67%), LINE 6,677/6,857 (97.37%),
  BRANCH 3,957/4,447 (**88.98%** — новый рекорд), COMPLEXITY 3,212/3,693
  (**86.98%** — новый рекорд), METHOD 1437/1437.
- **Дальше:** под-шаг 5b — оставшаяся часть `processResponse` (стриминговые
  артефакты line/SSE + `optional`-обёртки), затем ветка буферного декодирования.

## Amendment #283

Шаг №6 плана — последний — выполнен: уведомления метрик (`notify*`) вынесены
из `HttpTemplate` в package-private `ru.jrestly.http.MetricsNotifier`.
**План ADR-0066 закрыт полностью.**

- **Извлечено (байт-в-байт):** `notifyCallStart` → `MetricsNotifier.callStart`,
  `notifyAttemptComplete` → `attemptComplete`, `notifyRetry` → `retry`,
  `notifyCallComplete` → `callComplete` (17 call-site'ов двух контуров:
  sync `exchange`/`executeWithRetries` и async `exchangeAsync`/`attemptAsync`),
  плюс байт-учётный шов ADR-0036 Amendment #193: `computeRequestBytes` →
  `MetricsNotifier.requestBytes`, `computeResponseBytes` → `responseBytes`.
- **Коллаборатор — чистый `static`-хелпер** (паттерн `CacheDispatch`/
  `ResponseBody`): все 17 call-site'ов передают `(metricsListener, logger)`
  аргументами — коллаборатор не читает поля шаблона, поэтому оба mutabельных
  сеттера (`setMetricsListener`/`setLogger`) не создают проблемы «устаревшего
  производного кэша». Грабли: пустой no-arg **package-private** конструктор
  не режется встроенным JaCoCo-фильтром (он выкидывает только **private**
  пустые no-arg-конструкторы — у прецедентов `CacheDispatch`/`ResponseBody`
  именно `private`) — единственный недостижимый метод убран превентивно.
- **Шов переехал, а не продублирован:** `HttpTemplateMetricsBytesTest`
  переименован в `MetricsNotifierBytesTest` (6 → 8 тестов: добавлены
  не-`Content-Length` заголовок в списке — false-сторона `equalsIgnoreCase`
  — и отрицательный `Content-Length` — false-сторона `val >= 0`). Новый
  безсетевой сьют `MetricsNotifierTest` (22 теста) — матрица ветвей всех
  четырёх точек диспетчеризации: no-listener no-op, `null`-context skip,
  pass-through аргументов (включая попытку/задержку анти-клампа отрицательного
  delay), listener-сбой проглатывается с логгером и без него, контракт
  `SimpleCallMetrics` (число передач 0–N, байт-учёт, Optionals — ADR-0036
  Amendments #195/#259).
- **Остаток:** 1 missed ветвь `responseBytes` (`headers() == null`) —
  структурно недостижимая defensive (record `WireResponse` нормализует
  headers к non-null); guard оставлен и задокументирован (паттерн
  unreachable-ветвей `InMemoryHttpCache`, ADR-0063).
- **Юнит-матрица:** `MetricsNotifier` — 39/40 ветвей (0 missed методов,
  0 missed классов; единственная missed — документированная defensive).
- **Эффект:** `HttpTemplate` 95 → **82** missed по ветвям; CLASS **149/149**,
  METHOD **1444/1444** (100% сохранены); LIVE-гейт **1,636/1,636**, javadoc
  чист под `-Werror`. JaCoCo: INSTRUCTION 30,799/31,455 (**97.91%** — рекорд),
  LINE 6,696/6,856 (**97.62%** — рекорд), BRANCH 3,977/4,437 (**89.63%** —
  рекорд), COMPLEXITY 3,242/3,695 (**87.74%** — рекорд). Публичный API не
  изменён.
- **Дальше:** дистилляция закрытого эпика в `ROADMAP.md`; после — беклог
  Антона №6–№8 (по его формулировке — «после завершения текущих задач и
  текущего беклога» — оба условия теперь выполнены; по №7 уточнить
  объём до старта).
