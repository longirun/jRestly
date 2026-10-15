# ADR-0058: External HTTP Engine Submodules (OkHttp / Apache HttpClient 5)

> 2026-10-16 · Accepted · Status: Proposed · Next free ADR: 0059

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

*Amends ADR-0052: добавлен `HttpEngineProvider.priority()`, оформлены
официальные адаптеры.*
