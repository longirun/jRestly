# Last Session: #193 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», срез ADR-0036 (метрики) × ADR-0035 (стриминг
тела): 12-й срез нити, 11-я «тихая» находка — и впервые на стыке двух
ADR'ов, а не «формулировка ADR vs код».**

1. *Находка:* `HttpTemplate.computeRequestBytes` (путь `notifyCallComplete`,
   активный только при метрик-слушателе) для замера `requestBytes()` вызывал
   `request.bodyPublisherSupplier().get()`. Для ADR-0035 `Supplier`-тел
   (`Supplier<InputStream>` и т.д.) supplier оборачивает **продающее
   пользователя** → лишний вызов на логический вызов (в момент
   call-complete, после реальной отправки), с `catch (Throwable ignored)`.
   Для `Supplier<InputStream>` замер всё равно `-1`
   (`ofInputStream().contentLength()` неизвестен по определению) — двойная
   материализация ради нуля. Нарушены: контракт «supplier вызывается по
   числу отправок» (`testUploadSupplierAcrossRetry` — 2 вызова при 2
   отправлениях) и ADR-0035 §3 (hot-путь не трогает живые стримы;
   `logRequest` пишет `<streaming body>` без supplier'а).
2. *Fix:* supplier-тела → `-1` (unknown) — то же обращение, что у multipart;
   `byte[]`-тела — точный замер; response-учёт не тронут (body length →
   Content-Length → -1). `computeRequestBytes`/`computeResponseBytes` —
   package-private seam. Javadoc `CallMetrics.requestBytes()` уточнён
   (per-send rendered bodies → -1). ADR-0036 → Amendment #193; non-goal:
   Path/File-тела отдают -1 вместо размера файла (размер знает только движок
   на момент отправки) — кандидат на engine-reported byte accounting.
3. *Тесты:* +7 — новый `HttpTemplateMetricsBytesTest` (6 unit-тестов на
   seam: supplier не вызывается, byte[] меряется, body-less -1,
   response: body/Content-Length/null/непарсируемый) + e2e
   `RequestBodyStreamingWireMockTest.testUploadSupplierWithMetricsListener`
   (client с метрик-слушателем, `Supplier<InputStream>`, ровно 1 вызов).
4. *TDD:* compile-red (stash → 8 ошибок «private access») → behavior-red
   (e2e: expected 1 but was 2; unit: expected 0 but was 1 — ровно по
   целевым ассертам) → зелёный.
5. *Гейты:* `./gradlew test javadoc` — **1217/1217** (core 1137 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks
   15), 0 failures, javadoc зелёный под `-Werror`, 0 warnings.
6. *Остальной срез ADR-0036 (§1–§6):* чист — `@FunctionalInterface` + 4
   хука + `composite()` (§1); модель `CallMetrics`/`SimpleCallMetrics`
   (§2, вкл. convenience-хелперы, «at least 1» закреплён clamp'ом и
   компакт-конструктором); `@WithMetrics` TYPE+METHOD, repeatable, merge с
   client-level через composite (§3); `ModuleInfo.getMetricsListener()`
   default null, оба билдера NPE fail-fast + composite chaining (§4);
   `ControllerValidator.validateMetricsClass` — тип/конкретность/no-arg ctor
   (§5, на классе и методе); fault-tolerance — все 4 notify-хука ловят
   Throwable и логят WARNING (§6). Асинхронный путь метрик паритетен с
   синхронным (short-circuit, rate-limit/CB rejection, whenComplete-хвост).

## Технические заметки

- Свет «стык двух ADR'ов» оказался продуктивнее «формулировка vs код» —
  после чистого среза ADR-0034 (#192) это подтверждение прогноза из
  #192: дальше нужны другие фонари.
- Грабли: `Header` живёт в `ru.jrestly.http` (не `ru.jrestly.header`);
  `WireResponse` — 6-аргументный конструктор `(status, headers, body,
  stream, version, trailers)`.
- Мелкие кандидаты (не тронуты): (a) `attempts()` в потоке 401-refresh —
  второй `executeWithRetries` (sync и async) затирает счётчик первого,
  «total attempts executed» не сумма; (b) `onCallStart` срабатывает и при
  short-circuit (запрос на провод не уходил) — против javadoc «before the
  initial request is initiated»; (c) `SimpleCallMetrics` строится вне try в
   `notifyCallComplete` (сейчас не бросает — clamp гарантирует ≥ 1).

## Контекст для следующей сессии (#194)

1. **Свободный ход.** Варианты: (a) аудит «стык ADR'ов» — ADR-0038
   (HTTP/2/трейлеры × ADR-0059 стриминг: согласование, границы движков,
   trailers на HTTP/2) или ADR-0035 целиком (контракт BodyPublisher:
   retry-совместимость, progress × streaming, nullable publisher);
   (b) мелкие кандидаты из заметок выше (a/b/c); (c) кандидат №1, батч №2
   test-javadoc (`cookie` 2, `codec` 4, `compression` 3, `tracing` 3,
   `interceptor` 4); (d) free-аудит (`ModuleInfo`/`SimpleModuleInfo`
   мутабельность, `ServerSentEventReconnector` жизненный цикл,
   `HttpTemplate` async vs sync паритет — вне метрик).
2. **Обновлено:** `HttpTemplate` (seam + fix), `CallMetrics` (javadoc),
   `HttpTemplateMetricsBytesTest` (новый), `RequestBodyStreamingWireMockTest`
   (+1 e2e), `docs/0.10.0/ADR-0036` (Amendment #193), `DRAFT.md` (запись
   #193), `.agent/days/2026-10-17.md` (сессия #193), `.agent/mailbox.md`
   (сообщение #193, гигиена: удалено #190), `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #192 (`e88c416`), перед
   ним #191 (`ab7b6ee`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: `193`. Часовой пояс контейнера может «откатываться»
   между запусками — писать реальный календарный день (2026-10-17).
