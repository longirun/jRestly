# ADR-0066: Internal Code Structure — Package Taxonomy and Incremental Decomposition of the Request Pipeline

## Status

Accepted

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
