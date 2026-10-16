# ADR-0058: External HTTP Engine Submodules (OkHttp / Apache HttpClient 5)

> 2026-10-16 · Accepted · Status: Complete · Next free ADR: 0059
> Реализация: фаза 1 (ядро, `priority()` + стабильная сортировка, сессия #149),
> субмодули `engine-okhttp` (сессии #150–#155; пассивный транспорт — #156) и
> `engine-apache5` (#157). Публичные цифры и методология — `BENCHMARKS.md` (#158).
> **Finding #158 закрыт в #159:** `AbstractHttpClient.createConnection()` делегирует
> `HttpEngineResolver.resolve(moduleInfo)` — ServiceLoader-автообнаружение end-to-end
> работает; e2e-гейт `SpiEngineAutoDetectionTest`. Финальный гейт: 1112/1112.

## Context

1. ADR-0052 вынес сетевой транспорт за SPI `HttpEngine` (`ru.jrestly.engine`):
   контракт `HttpEngine`/`HttpEngineOptions`, стандартный zero-dep движок
   `JdkHttpEngine` и резолвер `HttpEngineResolver` с стратегией
   «явный `ModuleInfo.getHttpEngine()` → `HttpEngineProvider` через
   `ServiceLoader` → fallback `JdkHttpEngine`». Zero-dep-инвариант ядра
   (0 зависимостей в публикуемом POM) сохранён.
2. С сессии #143 рабочие реализации SPI существуют как **прототипы** в
   непубликуемом модуле `benchmarks`
   (`ru.jrestly.benchmark.engine.OkHttpEngine`, `Apache5HttpEngine`).
   Бенчмаркинг (сессия #147, честный raw-TCP стенд с `TCP_NODELAY`,
   JMH, µs/op, loopback) показал паритет декларативного слоя с «сырым»
   транспортом:

   | клиент | getUser | ping |
   |---|---|---|
   | Apache HC5 raw | 341 | 363 |
   | OkHttp raw | 428 | 254 |
   | jRestly + OkHttpEngine | 450 | 342 |
   | jRestly + Apache5HttpEngine | 478 | 331 |
   | raw JDK HttpClient | 1364 | 1191 |
   | jRestly + JDK (default) | 1524 | 1359 |

   Вывод: путь к «вершине производительности» — дать пользователю подключить
   быстрый транспорт без своего кода движка. Сегодня для этого надо либо
   копировать движок в свой проект, либо ссылаться на непубликуемый модуль.
3. Прототипы не являются production-ready — разрыв с контрактом
   `HttpEngine`/`HttpEngineOptions`:
   - **OkHttpEngine**: игнорирует `options.timeout()` (нет `callTimeout`),
     `options.version()` (протокол не фиксируется) и
     `options.streamResponse()` (всегда буферизует через
     `response.body().bytes()`); оба прогресс-листенера (ADR-0030/0033)
     не поддерживаются; `sendAsync` закрывает `Response` в колбэке —
     стриминговое тело в async-режиме не прочитать; протокол маппится
     «всё не-HTTP/2 → `HTTP_1_1`».
   - **Apache5HttpEngine**: `sendAsync` — `CompletableFuture.supplyAsync`
     на общем ForkJoinPool (рисок истощения потоков под параллельной
     нагрузкой); `timeout`/`stream`/progress не поддерживаются; протокол
     захардкожен `HTTP_1_1`.
   - **У обоих**: нет регистрации
     `META-INF/services/ru.jrestly.engine.HttpEngineProvider` —
     автообнаружения через `ServiceLoader` нет. А при нескольких
     провайдерах на classpath `HttpEngineResolver` берёт «первый
     доступный» — при произвольном порядке итерации `ServiceLoader`
     результат недетерминирован.
4. В wire-модели ровно один тип из JDK, который внешний движок обязан
   уметь адаптировать: `WireRequest.bodyPublisherSupplier`
   (`Supplier<? extends java.net.http.HttpRequest.BodyPublisher>`) —
   стриминговые тела (ADR-0035) и multipart (ADR-0024) приходят к движку
   как JDK-публикатор и должны «выкачиваться» в `InputStream`.
   Остальные wire-типы (`WireRequest`, `WireResponse`, `Header`) —
   платформо-независимые.

## Decision

1. **Два публикуемых субмодуля** (Gradle; `settings.gradle` +
   `include 'engine-okhttp'`, `include 'engine-apache5'`):
   - `ru.jrestly:jrestly-engine-okhttp` — пакет `ru.jrestly.engine.okhttp`:
     `OkHttpEngine` + `OkHttpEngineProvider`. Зависимость
     `api 'com.squareup.okhttp3:okhttp:4.12.0'` — публичные конструкторы
     принимают `OkHttpClient`, тип части API адаптера; kotlin-stdlib
     транзитивно тянется только на уровне субмодуля, не в ядро.
   - `ru.jrestly:jrestly-engine-apache5` — пакет `ru.jrestly.engine.apache`:
     `Apache5HttpEngine` + `Apache5HttpEngineProvider`. Зависимость
     `api 'org.apache.httpcomponents.client5:httpclient5:5.4.1'`.
   - Ядро (root) остаётся с 0 зависимостей. `benchmarks` переходит на
     `implementation project(':engine-okhttp')` /
     `project(':engine-apache5')`, локальные копии прототипов удаляются —
     один источник правды, стенд становится публикуемым артефактом.
2. **Автообнаружение через SPI**: каждый субмодуль публикует
   `META-INF/services/ru.jrestly.engine.HttpEngineProvider`.
   `isAvailable()` — проверка `Class.forName` ключевого класса транспорта
   (`okhttp3.OkHttpClient` /
   `org.apache.hc.client5.http.impl.classic.CloseableHttpClient`).
   `JdkHttpEngine` провайдером **не является** — остаётся fallback'ом
   резолвера, поэтому поведение ядра у пользователя, не добавившего
   субмодуль, не меняется.
3. **Приоритет провайдеров**: в `HttpEngineProvider` добавляется
   `default int priority() { return 0; }` (бинарно-совместимое расширение
   SPI). `HttpEngineResolver` выбирает доступного провайдера с
   максимальным `priority()` (стабильная сортировка: равенство сохраняет
   порядок `ServiceLoader`). Оба официальных адаптера заявляют
   `priority() = 50`: одновременное присутствие обоих на classpath
   разрешается явным `httpEngine(...)`/`ModuleInfo` (документируется).
4. **Контракт паритета** (чек-лист production-ready для прототипов,
   по полям `HttpEngineOptions`):
   - `timeout()` — эффективный per-request таймаут: OkHttp —
     `Call.callTimeout(...)`; Apache5 — `RequestConfig.setResponseTimeout`.
   - `streamResponse()` — тело как живой `InputStream`
     (`WireResponse.stream`, без `bytes()`-буферизации): OkHttp —
     `response.body().byteStream()` pass-through; Apache5 —
     `entity.getContent()` с учётом жизненного цикла entity.
   - `version()` — best-effort фиксация протокола (OkHttp — протоколы
     connection pool; Apache5 — HTTP/1.1, HTTP/2 только с доп. коннектором,
     задокументировать best-effort).
   - Прогресс-листнеры (ADR-0030/0033) — count-обёртки над потоками
     запроса/ответа; сохраняются финальное событие `(total, total)`
     и семантика нулевой загрузки `(0, 0)`.
   - Trailers (ADR-0038) — пробрасываются, где протокол их несёт.
   - `close()` — освобождение пула/исполнителей с уважением
     `ownsClient` (внешний клиент движком не закрывается).
   - Исключительный контракт без изменений: синхронный `send` бросает
     `IOException`/`InterruptedException`, `sendAsync` — exceptional
     completion; `HttpTemplate` маппит это на семантику транспортного
     сбоя так же, как для `JdkHttpEngine`.
5. **Маппинг `createEngine(ModuleInfo)`** (аналог
   `JdkHttpEngine.create`): `getSslContext()`/`getSslParameters()` → TLS
   транспорта (ADR-0020); `getCookieHandler()` → `CookieJar` (OkHttp) /
   адаптер `CookieStore` (Apache5, ADR-0032/0039); `getProxySelector()` →
   per-request прокси (OkHttp — `ProxySelector.select()` на вызов,
   Apache5 — `RequestConfig.setProxy`); `getAuthenticator()` → challenge-
   интерцептор, best-effort (ADR-0037); `isVirtualThreads()` →
   virtual-thread executor (`newVirtualThreadPerTaskExecutor`) для
   dispatcher/колбэков.
6. **Адаптер body publisher**: каждый движок реализует выкачивание
   `WireRequest.bodyPublisherSupplier` в `InputStream` (подписка на
   `Flow.Publisher<ByteBuffer>`) — один адаптер покрывает и стриминговые
   тела (ADR-0035), и multipart (ADR-0024). При `null`-supplier
   используется сырой `body` (`byte[]`).
7. **Версионирование**: версия субмодулей следует за ядром (0.9.0,
   в разработке 0.10.0), публикация тем же `maven-publish` конвейером
   (sources + javadoc jars). `benchmarks` публикуемым не является.

## Non-Goals

- Зависимости ядра от адаптеров и смена движка по умолчанию:
  `JdkHttpEngine` остаётся дефолтом (zero-dep инвариант, ADR-0052).
- Поддержка OkHttp 3.x и HttpClient 4.x (только 4.12+ / 5.4+).
- Генерализация `WireRequest.bodyPublisherSupplier` в
  JDK-независимый тип: `HttpRequest.BodyPublisher` — тип из `java.base`,
  а не из чужой зависимости; кандидат в отдельный ADR при необходимости.
- «Мaxed-out» настройка транспорта из коробки (HTTP/2 prior-knowledge для
  Apache, кастомные `ConnectionPool` у OkHttp) — настраивается
  пользователем через явный конструктор.

## Consequences

**Плюсы:**
- Production fast-path: одна строка зависимости (`jrestly-engine-okhttp`) —
  `ServiceLoader` подхватывает движок, все возможности jRestly
  (интерцепторы, retry, сжатие, Circuit Breaker, метрики, DTO-маппинг)
  работают поверх быстрого транспорта. Доказательство: jRestly +
  OkHttpEngine = 450 µs — уровень raw OkHttp (428 µs, +5%) при 1524 µs
  на JDK-транспорте.
- Стенд бенчмарков становится публичным артефактом с собственными
  тестами (WireMock), без дублирования кода.
- Ядро не меняется: 1058/1058 тестов, zero-dep POM, обратная
  совместимость (метод SPI `priority()` — `default`).

**Минусы / замечания:**
- Два дополнительных публикуемых артефакта требуют сопровождения
  (апгрейды OkHttp/HC5, регрессии поведения на их стороне).
- OkHttp-адаптер тянет kotlin-stdlib в приложение (уровень субмодуля,
  не ядра).
- Равный приоритет двух официальных адаптеров на classpath разрешается
  документацией, а не кодом.
- Выкачивание body publisher во внешнем движке — дополнительная копия
  против JDK-движка (публикатор передаётся JDK-клиенту напрямую).
  Допустимо: влияние аллокаций меряется JMH на стороне модулей.

**План верификации (следующие сессии, TDD):**
1. `priority()` в ядре — тест в `HttpEngineResolverTest` (red-first).
2. Субмодули `engine-okhttp`/`engine-apache5`: WireMock-тесты паритета
   поведения (заголовки, тело, стриминг, прогресс, таймауты, trailers,
   семантика `close()`).
3. Бенчмарки: `HttpNetworkBenchmark`/`ConcurrentLoadNetworkBenchmark`
   поверх перенесённых классов; гейт паритета — jrestly+OkHttp в пределах
   ±10% от raw OkHttp (базовые 450/428 µs).

## Amendment (сессия #156): пассивный транспорт — запрет прозрачного gzip

Первая имплементация фазы 2а (сессия #155) оставляла OkHttp его
`BridgeInterceptor`-поведение: запрос без `Accept-Encoding` получал
инжектированный `Accept-Encoding: gzip`, а ответ OkHttp **прозрачно
распаковывал** (снимая `Content-Encoding`, обнуляя `Content-Length` →
`contentLength() == -1`). Это ломало контракт паритета сразу в трёх
измерениях:

1. `total` в download-прогрессе (ADR-0033) терял объявленный
   `Content-Length`;
2. `isDecompressionEnabled() == false` (ADR-0021) не соблюдался — движок
   всё равно распаковывал ответ;
3. метаданные `Content-Encoding` не доходили до ядра (двойная
   распаковка не возникала лишь потому, что при явном заголовке от
   декларативного слоя OkHttp отключает свою прозрачность сам).

**Решение**: `OkHttpEngine` приводит любой переданный `OkHttpClient`
(включая внешний) к пассивному транспорту двумя интерцепторами:

- `NoTransparentGzipInterceptor` (application-level): при отсутствии
  `Accept-Encoding` в запросе подставляет `gzip` + tag-маркер — только
  чтобы `BridgeInterceptor` не включил прозрачный gzip;
- `StripSuppressedAcceptEncodingInterceptor` (network-level): снимает
  подставленный заголовок ровно перед проводом, восстанавливая точный
  паритет с `JdkHttpEngine` (заголовка нет вовсе, когда его не задал
  вызывающий).

Явный `Accept-Encoding` вызывающего (в декларативном слое —
`gzip, deflate`, ADR-0021) проходит как есть; ответ приходит **сжатым**,
`Content-Encoding` сохраняется, распаковка остаётся за
`ContentCompressor` SPI ядра. Гейт: `:engine-okhttp:test` 23/23 (в т.ч.
новые тесты «no Accept-Encoding на проводе» и «явный Accept-Encoding
проходит сжатым»), полный прогон 1086/1086.

*Побочная находка*: WireMock 3.12 (Jetty) без явного заголовка отвечает
`Transfer-Encoding: chunked` даже для известных тел — тесты прогресса
обязаны декларировать `Content-Length` в стабе, как это делают
core-тесты `DownloadProgressWireMockTest`.

*Amends ADR-0052: добавлен `HttpEngineProvider.priority()`, оформлены
официальные адаптеры.*

## Amendment (сессия #157): фаза 2b — `engine-apache5`

Субмодуль закрыт по решению: `Apache5HttpEngine` (+ провайдер, priority 50,
ServiceLoader) — полный контракт паритета (timeout, streaming через
`executeOpen`, progress upload/download с `(0,0)` и `(total,total)`, trailers,
выделенный executor для `sendAsync`, `close()` с уважением ownership,
маппинг `ModuleInfo`: TLS/cookies/proxy/authenticator best-effort).
Пассивный транспорт — `disableContentCompression()` (урок фазы 2a применён
заранее): ни `Accept-Encoding`, ни прозрачной распаковки. 23/23 тестов.

Находки под `-Werror` (HttpClient 5.4.1 пометил deprecated массу старого API):
`SSLConnectionSocketFactory`/`setSSLSocketFactory` → `DefaultClientTlsStrategy`/
`setTlsSocketStrategy`; `RequestConfig.setConnectTimeout` → `ConnectionConfig`
на connection manager; Date-куки → Instant-варианты; per-request
`setProxy(HttpHost)` — deprecated **без** замены (локальный
`@SuppressWarnings("deprecation")`).

С закрытием обоих субмодулей фаза 2 ADR-0058 завершена; benchmarks переведены
на `project(':engine-*')`, прототипы удалены.

## Finding (сессия #158): `HttpEngineResolver` не подключён к `AbstractHttpClient`

При подготовке публичной бенчмарк-документации обнаружено (поведенчески и по коду):
дефолтный клиент **не** проходит через `HttpEngineResolver` —
`AbstractHttpClient.createConnection()` при `null`-движке строит JDK-клиент
напрямую. Следствия:

- ServiceLoader-автообнаружение `engine-okhttp`/`engine-apache5` end-to-end
  не работает: одна строка зависимости не подхватывает движок (нарушен п. 2
  Decision); работает только явный `builder.httpEngine(...)`;
- javadoc `ModuleInfo.getHttpEngine()` («resolved via HttpEngineResolver») не
  соответствует коду.

Доказательства: у `HttpEngineResolver.resolve` нет ни одного call-site в
`src/main` (grep); JMH-прогон #158 с обоими субмодулями на classpath показывает
у дефолтного клиента цифры JDK-транспорта (1666 µs), а не быстрых движков
(~400–540 µs).

### Resolution (сессия #159)

- `AbstractHttpClient.createConnection()` целиком делегирует резолверу:
  `HttpEngineResolver.resolve(moduleInfo)` (явный движок → SPI-провайдеры по
  приоритету → JDK-fallback). Дублирующая сборка JDK-клиента из
  `createConnection()` удалена — единственный источник правды
  `JdkHttpEngine.create(moduleInfo)`.
- Executor-семантика ADR-0026 сохранена: клиентский managed-исполнитель
  (custom → виртуальные потоки) резолвится в `AbstractHttpClient` и
  закрывается в `close()`; `JdkHttpEngine.create` дополнительно honor-ит
  `isVirtualThreads()` для транспорта (собственный managed executor,
  закрывается в `engine.close()`).
- Red-first e2e-гейт: `SpiEngineAutoDetectionTest` + провайдер
  `TestSpiEngineProvider` в тестовом classpath
  (`META-INF/services/ru.jrestly.engine.HttpEngineProvider`, доступен только
  при взведённом флаге — остальные тесты не затронуты). До фикса тест красный
  («default client picks up the SPI engine»), два предохранителя (fallback на
  JDK; явный движок побеждает) зелёные до и после.
- Benchmarks: «jrestly default (JDK)» строка больше не может опираться на
  автообнаружение (оба субмодуля на classpath) — JDK-транспорт закреплён
  явным `JdkHttpEngine.create` в `JRestlyBenchmarkClient`/`DispatchOverheadBenchmark`;
  примечание добавлено в `BENCHMARKS.md`.
