# DRAFT: активная зона после закрытия Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055)

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
  Следующий ход — свободный (кандидат №1 test-javadoc или аудит).

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
