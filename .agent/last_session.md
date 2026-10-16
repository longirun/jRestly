# Last Session: #160 (2026-10-16)

## Что сделано

**Кандидат №2: аудит зоны «HTTP Trailers & Advanced Streaming ergonomics» +
концепт ADR-0059 (Proposed).** Код не менялся — концепт-сессия (прецедент #148).

1. **Аудит по коду — три подтверждённых пробела:**
   - у установленного SSE-стрима нет жизненного цикла: `RetryPolicy` не
     покрывает стрим, `Last-Event-ID` не упоминается в `src/main`, обрыв —
     `UncheckedIOException` без восстановления;
   - нет idle-stall guard (JDK `timeout()` не покрывает чтение тела);
   - трейлеры в стриминге теряются: все движки строят `WireResponse` до EOF
     (`JdkHttpEngine` — всегда `List.of()`), JDK-API трейлеров не отдаёт
     вообще (`javap` подтверждён: нет ни у `HttpResponse`, ни у
     `BodySubscriber`, ни у `ResponseInfo`).
2. **Оформлен** `docs/0.10.0/ADR-0059-sse-stream-resilience-and-streaming-trailer-completion.md`
   (Status: Proposed) с трёхфазным планом:
   - **Ф1 (следующая сессия):** `ReconnectPolicy` +
     `ServerSentEvent.reconnecting(opener, policy)` — EventSource-семантика:
     opener получает nullable `lastEventId` (канонически →
     `@RequestHeader("Last-Event-ID")`-параметр), серверный `retry:` → 3000 мс
     WHATWG + `Jitter` (ADR-0040), `stopOnDone` (default true), bounded
     `maxAttempts` (default 3), `onReconnect(Consumer<ReconnectInfo>)`, close
     отменяет reconnect; ленивый итератор, Thread.sleep на потребителе.
   - **Ф2:** `WireResponse` +7-й компонент `Supplier<List<Header>> trailersSupplier`
     (явные конструкторы 3/4/5/6-арностей; `equals/hashCode` supplier не
     учитывают) + `ResponseHeaders.populateTrailers` + терминальные
     EOF/close-хуки на `InputStream`/`Stream<String>`/`Stream<ServerSentEvent>`;
     OkHttp/Apache5 заполняют supplier в стриминге, JDK — пусто.
   - **Ф3 (spike-gated):** `stallTimeout` — сторожевой поток + close;
     сначала спайк на разлочивание read в JDK/OkHttp.
3. DRAFT: кандидат №2 переведён в «В работе» с планом фаз; блок #160 добавлен
   наверх; дневник дня дополнен.

## Гейты

- Код не менялся: гейт не перезапускался; состояние #159 — **1112/1112**
  (core 1051 + engine-okhttp 23 + engine-apache5 23 + benchmarks 15),
  javadoc-гейты зелёные, 0 warnings.

## Контекст для следующей сессии (#161)

1. **Старт Ф1 ADR-0059 по TDD (red-first):**
   - нарисовать `ReconnectPolicy` (builder по образцу `RetryPolicy`,
     переиспользовать `ru.jrestly.http.Jitter` из ADR-0040) и
     `ServerSentEvent.reconnecting(Function<String, Stream<ServerSentEvent>>, ReconnectPolicy)`;
   - юниты на фейковых opener'ах: последовательность `lastEventId`, чистый
     EOF → reconnect (EventSource), `[DONE]` → стоп, `maxAttempts`,
     close() отменяет reconnect, проброс последней ошибки, `onReconnect`;
   - e2e WireMock: первый стаб SSE `retry: 50` + id 1..2 → FAULT, второй стаб
     ждёт `Last-Event-ID: 2` → `[DONE]` (проверка через `findAll`);
   - осторожно: WireMock FAULT роняет соединение (`Connection reset`/двух-
     кратный `GET` матчится первым стабом — использовать scenario).
2. Не забыть: `[DONE]`-хелпер `isDone()` уже есть; retry/id в событиях
   персистятся (#142) — парсер трогать не нужно.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   внешний `timeout`; ветка agent-loop; root-тесты с фильтром:
   `./gradlew :test --tests "..."` (без `:` фильтр падает на `:benchmarks:test`).
