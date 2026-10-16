# ADR-0059: SSE Stream Resilience & Streaming Trailer Completion

> 2026-10-16 · Proposed · Status: Concept · Next free ADR: 0060
> Эпик-кандидат №2 из активной зоны DRAFT («HTTP Trailers & Advanced Streaming
> ergonomics»). Сессия #160: аудит зоны + концепт. Реализация — последующие
> сессии по фазам (TDD, red-first).

## Context

1. **Что уже есть.** Стриминговый слой — самая зрелая часть эргономики:
   `InputStream` (ADR-0015), `Stream<String>` и `Stream<ServerSentEvent>`
   (ADR-0016, чарсет #140, WHATWG-семантика #142), POST+SSE для LLM
   (`ServerSentEventsAndLineStreamingTest`), async-варианты
   (`CompletableFuture<Stream<...>>`), прогресс скачивания (ADR-0033),
   стриминговая декомпрессия (ADR-0057). Трейлеры смоделированы в
   `WireResponse`/`ResponseHeaders` (ADR-0038): `trailers()`,
   `firstTrailer()`, `trailerValues()`, `declaredTrailers()` (разбор
   заголовка `Trailer`).
2. **Пробел A — у установленного стрима нет жизненного цикла.** Спека
   WHATWG EventSource требует переподключения: браузер обязан при обрыве
   соединения повторить запрос, отправив `Last-Event-ID` и выждав
   серверный `retry:`. У нас `RetryPolicy` (ADR-0040) работает до выдачи
   тела запроса и стрим не покрывает; `Last-Event-ID` не упоминается в
   `src/main` вообще (grep). Обрыв посреди стрима прилетает потребителю как
   `UncheckedIOException` из итератора `BufferedReader.lines()` и стрим
   умирает — восстановление целиком на пользователе, при том что сам
   парсер уже трекает `id`/`retry` как потоковое состояние (модель
   `ServerSentEvent` их персистит). Для LLM-интеграций это ежедневный
   сценарий: «стрим оборвался на середине ответа, продолжи с места».
3. **Пробел B — нет защиты от зависшего стрима (idle-stall).** У JDK
   `HttpRequest.timeout()` ограничивает только ожидание заголовков
   ответа, не чтение тела; у OkHttp нет per-call read-timeout на
   InputStream. Зависшее SSE-соединение LLM-провайдера держит
   потребительский поток вечно — на виртуальных потоках это дешёвая
   утечка, но всё же утечка и висящий ответ. Библиотека должна уметь
   «если N секунд нет событий — считать соединение мёртвым».
4. **Пробел C — трейлеры в стриминговом режиме теряются.** Движки строят
   `WireResponse` в момент получения заголовков, до вычитывания тела:
   `JdkHttpEngine.toWireResponse` всегда `List.of()` (строки 243–247);
   `OkHttpEngine.toStreamingWireResponse` — `List.of()` (репорт только в
   буферном пути, строки ~449–451); `Apache5HttpEngine` — аналогично
   (буферный `entity.getTrailers()`, строки ~419–426). Тела финальных
   трейлеров (`Server-Timing`, дайджесты/чек-суммы, gRPC-Web-статусы)
   доступны движку только после EOF, где ядро их уже не спрашивает.
   Публичный JDK-API трейлеров не отдаёт вообще (проверено `javap`:
   у `HttpResponse`/`BodySubscriber`/`ResponseInfo` нет trailer-методов) —
   это платформенная граница, для JDK-движка поведение остаётся «пусто».
5. **Инварианты.** Zero-dep ядро; прозрачные методы без `Response<T>`
   (ADR-0004) — переподключение и поздние трейлеры должны быть устроены
   без обёрток вокруг результата; `WireResponse` — публичный контракт
   движков (ADR-0052/0058), расширяем его по прецеденту ADR-0038
   (явные совместимые конструкторы старых арностей); история `retry`/
   джиттера переиспользует `Jitter` (ADR-0040).

## Decision

### Фаза 1 — `ServerSentEvent.reconnecting(...)`: EventSource-семантика на клиенте

1. Публичная точка входа (zero-dep, `ru.jrestly.http`):

   ```java
   Stream<ServerSentEvent> events = ServerSentEvent.reconnecting(
           lastEventId -> api.streamChat(body, lastEventId),   // opener
           ReconnectPolicy.builder().maxAttempts(5).build());
   ```

   Канонический `opener` принимает `String lastEventId` (nullable на первом
   открытии) и возвращает ленивый `Stream<ServerSentEvent>`; пользователь
   прокидывает его в nullable-параметр метода
   `@RequestHeader("Last-Event-ID") String lastEventId` — декларативно,
   без магии (null-аргумент уже опускает заголовок).
2. Семантика, совместимая с WHATWG EventSource, но ограниченная:
   - переподключение на `UncheckedIOException`/`IOException` посреди
     стрима и на **чистый EOF** (поведение EventSource для «сервер закрыл
     соединение»);
   - задержка = последний серверный `retry:` (персистится в событиях),
     fallback — 3000 мс (дефолт WHATWG); опционально экспоненциальный
     рост с `Jitter` (ADR-0040) и `maxDelay` — поля `ReconnectPolicy`;
   - `stopOnDone` (default `true`) — после события `isDone()` ([DONE])
     переподключения нет (LLM-дружественное расширение поверх спеки);
   - `maxAttempts` (default 3) — предохранитель от бесконечного цикла;
     исчерпание бросает последнюю причину как `UncheckedIOException`
     (тот же тип, что пользователь видит сегодня) — тихой обрезки нет;
   - `close()` пользователем (или `[DONE]`+close) прекращает стрим
     окончательно; флаг закрытия проверяется перед переподключением;
   - `onReconnect(Consumer<ReconnectInfo>)` — хук для метрик/логов,
     `ReconnectInfo(attempt, lastEventId, delay, cause)`.
3. Реализация: ленивый итератор (по образцу `ServerSentEventParser`) —
   переподключения происходят на потоке потребителя, ожидание —
   `Thread.sleep` (виртуальные потоки делают его бесплатным); никаких
   скрытых исполнителей в ядре. Async-пайплайны покрываются бесплатно:
   `reconnecting(id -> api.streamChatAsync(body, id).join())` — потребление
   `CompletableFuture<Stream<...>>` уже синхронно после одного join.
4. Честность контракта: сервер, не поддерживающий `Last-Event-ID`, может
   продублировать события после переподключения — документируем (это
   свойство протокола, не библиотеки).

### Фаза 2 — Завершение трейлеров на стриминговом пути

1. `WireResponse` получает седьмой компонент
   `Supplier<List<Header>> trailersSupplier` («ленивые трейлеры»), с
   нормализацией `null → List::of` и явными конструкторами старых
   арностей 3/4/5/6 (прецедент ADR-0038). `equals`/`hashCode` трейлерную
   функцию не учитывают: это транспортный хук, не значение (два ответа,
   отличающиеся только способом добычи трейлеров, равны).
2. Движки заполняют supplier только там, где это физически возможно:
   - `OkHttpEngine` (стриминг): ленивое чтение `response.trailers()`
     после EOF;
   - `Apache5HttpEngine` (стриминг): best-effort `entity.getTrailers()`;
   - `JdkHttpEngine`: всегда пусто (платформенное ограничение JDK,
     см. Context 4); буферные пути обоих субмодулей уже работают.
3. Ядро (`HttpTemplate`) оборачивает возвращаемые стриминговые артефакты
   (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>` и их async
   варианты) терминальным хуком: EOF/`close()` → однократный (CAS) запрос
   supplier → `ResponseHeaders.populateTrailers(...)` (потокобезопасное
   дополнение к существующим volatile-полям; `trailers()`/`firstTrailer()`/
   `trailerValues()` продолжают работать на том же объекте, который
   пользователь уже держит — метод-параметр или `capture(...)`).
4. Семантика: at-most-once, best-effort, пусто при отсутствии; имена
   объявленных трейлеров доступны сразу из заголовка `Trailer`
   (`declaredTrailers()`), значения — после исчерпания стрима.
   `populateTrailers` идемпотентен (повторный вызов из `close()` после
   EOF ничего не портит).

### Фаза 3 (spike-gated) — idle-stall guard

`ReconnectPolicy.stallTimeout(Duration)` (default — выключен): если между
событиями нет данных дольше таймаута, текущий стрим принудительно
закрывается и запускается обычный путь переподключения (фаза 1). Механизм
(сторожевой виртуальный поток + close) требует спайка на обоих движках:
гарантирован ли разлочивающий `IOException` при close из другого потока
для JDK- и OkHttp-стримов. Если спайк покажет ненадёжность — фаза
отменяется, а в Javadoc остаётся честная рекомендация внешнего таймаута
потребителя.

### Non-goals

- Request-side трейлеры (запросные trailers не поддерживает ни JDK, ни
  OkHttp; у HC5 — частично) — возможный отдельный ADR при реальном спросе.
- Трейлеры JDK-движка (нет публичного API платформы).
- Автоматическое бесконечное переподключение по умолчанию.
- Обобщение переподключения на произвольные `Stream<String>` — фаза 1
  строго про SSE (модель `ServerSentEvent` несёт весь нужный state).

## Consequences

- **+** Переподключение SSE перестаёт быть прикладным бойлерплейтом: одна
  функция + policy; `Last-Event-ID`/`retry` используются по назначению.
- **+** Поздние трейлеры доступны там, где транспорт их даёт, без обёрток
  вокруг результата (ADR-0004) — тот же `ResponseHeaders`-объект.
- **−** Новый публичный API (`reconnecting`, `ReconnectPolicy`,
  `ReconnectInfo`, `populateTrailers`) и расширение `WireResponse` —
  обновление субмодулей-движков в lockstep.
- **−** Гарантии переподключения ограничены протоколом: дубликаты при
  неподдерживающем `Last-Event-ID` сервере; stall guard может быть снят
  спайком.
- Zero-dep, прозрачность методов и модульность не затронуты.

## Test strategy

- **Фаза 1 (unit, red-first):** фейковые `opener`-функции (разрыв до
  событий / после N событий / чистый EOF / `[DONE]` / отказ opener'а):
  последовательность аргументов `lastEventId`, учёт серверного `retry:`
  и `maxAttempts`, запрет переподключения после close, проброс последней
  ошибки, вызовы `onReconnect`. **E2E (WireMock):** первый стаб отдаёт SSE
  с `retry: 50` и событиями `id: 1..2`, затем обрыв (FAULT); второй стаб
  ждёт `Last-Event-ID: 2` и отдаёт `[DONE]`; проверяем склейку событий и
  факт заголовка (`findAll`).
- **Фаза 2:** raw-TCP тестовый сервер (образец `BenchmarkHttpServer`,
  zero-dep), умеющий писать chunked-тело с финальными трейлерами —
  WireMock/Jetty трейлеры не отдают; юниты на `populateTrailers` и на
  WireResponse-совместимость арностей; parity-тесты обоих субмодулей
  (OkHttp/Apache5 видят трейлеры в стриминге, JDK — пусто).
- **Фаза 3:** только после спайка; WireMock delayed-body как стенд.

## Phase plan

1. **Ф1:** `ReconnectPolicy` + `ServerSentEvent.reconnecting` + unit/e2e
   (сессия ~#161).
2. **Ф2:** `WireResponse.trailersSupplier` + `populateTrailers` + терминальные
   хуки + engines parity (сессии ~#162–#163).
3. **Ф3:** spike stall guard → решение keep/drop (сессия ~#164).
