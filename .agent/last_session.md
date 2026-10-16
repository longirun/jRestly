# Last Session: #161 (2026-10-16)

## Что сделано

**ADR-0059, Ф1 реализована: `ServerSentEvent.reconnecting` (SSE EventSource-
семантика на клиенте).** TDD red-first: `ServerSentEventReconnectTest`
(18 юнитов) до API (32 compile-errors = red), затем реализация; e2e —
`SseReconnectWireMockTest`.

1. **Публичный API** (`ru.jrestly.http`):
   - `ReconnectPolicy`: `maxAttempts` (default 3; считает реконнекты *после*
     первой связи, 0 = off — осознанное отличие от `RetryPolicy`),
     `initialBackoff` 3000 мс (WHATWG fallback), `maxBackoff` 30 с,
     `backoffMultiplier` 1.0 (нет роста), `jitter` `NONE` (ровно серверный
     `retry:`), `stopOnDone` true, `onReconnect(Consumer<ReconnectInfo>)`,
     `defaults()`; package-private `nextDelayMillis(attempt, serverRetry, prev, random)`.
   - `ReconnectInfo(attempt, lastEventId, delay, cause)` — cause null при EOF.
   - `ServerSentEvent.reconnecting(Function<String, Stream<ServerSentEvent>>,
     ReconnectPolicy)` — opener получает nullable `Last-Event-ID`.
2. **Реализация** — package-private `ServerSentEventReconnector` (ленивый
   итератор): реконнект на `UncheckedIOException`/чистом EOF/падении
   opener'а; реплей последнего `id`; задержка = серверный `retry:` (из
   событий, персистится парсером #142) → fallback, `min(maxBackoff, ...)`,
   `multiplier^(n-1)`, jitter последним слоем; `Thread.sleep` на потоке
   потребителя; `close()` отменяет реконнект; `[DONE]`+stopOnDone —
   терминал; прерывание → `UncheckedIOException(InterruptedIOException)` +
   флаг; не-IO `RuntimeException` пробрасываются.
3. ADR-0059: Amendment #161 (решения, отклонения от концепта, e2e-грабли);
   DRAFT: блок #161 + кандидат №2 → «Ф1 сделана, следующий шаг Ф2».

## Гейты

- **1131/1131** (core 1070 + engine-okhttp 23 + engine-apache5 23 +
  benchmarks 15), полный `./gradlew test` зелёный; три javadoc-гейта
  (root + оба субмодуля) зелёные; 0 warnings (red-first подтверждён).

## Контекст для следующей сессии (#162)

1. **Ф2 ADR-0059 — ленивые трейлеры на стриминговом пути (TDD red-first):**
   - `WireResponse` +7-й компонент `Supplier<List<Header>> trailersSupplier`
     (нормализация `null → List::of`, явные конструкторы арностей 3/4/5/6 —
     прецедент ADR-0038; `equals/hashCode` supplier НЕ учитывают);
   - `ResponseHeaders.populateTrailers(...)` — идемпотентный, потокобезопасный
     (volatile/CAS), работает на объекте, который пользователь уже держит;
   - терминальный хук в `HttpTemplate` на стриминговых артефактах
     (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>` + async):
     EOF/close → однократный запрос supplier (CAS);
   - движки: OkHttp (`response.trailers()` после EOF) и Apache5
     (`entity.getTrailers()`, best-effort) заполняют supplier в стриминге;
     JDK — всегда пусто (платформенное ограничение, javap из #160).
2. **Стенд Ф2:** WireMock/Jetty трейлеров не отдаёт — raw-TCP zero-dep
   сервер (образец `BenchmarkHttpServer`), умеющий chunked + финальные
   трейлеры; юниты на `populateTrailers`/арности `WireResponse`; parity-тесты
   субмодулей (OkHttp/Apache5 видят, JDK — пусто).
3. Не забыть: `declaredTrailers()` (разбор заголовка `Trailer`) уже есть;
   при добавлении конструктора `WireResponse` проверить все call-site
   (`grep 'new WireResponse'` в core + оба engine + benchmarks + тесты).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   внешний `timeout`; ветка agent-loop; root-тесты с фильтром:
   `./gradlew :test --tests "..."`; `-Werror` ловит `-Xlint:try`
   (явный close() внутри try-with-resources — не делать).
