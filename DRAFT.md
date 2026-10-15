# DRAFT: активная зона после закрытия Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055)

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

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals):
  Наведение лоска и лаконичного javadoc в тестовых классах.

- **Кандидат №2 — HTTP Trailers & Advanced Streaming ergonomics**:
  Расширенные сценарии работы с трейлерами и чанками в стриминге данных (LLM / SSE).

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

Пометки из mailbox (актуальные на 2026-10-15):
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
