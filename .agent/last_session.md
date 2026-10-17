# Last Session: #198 (2026-10-17)

## Что сделано

**Аудит «ADR против кода», 17-й срез — свет ADR-0035 (стриминг тела запроса)
целиком: ЧИСТЫЙ срез (третий за серию после #192/#197), пять нормативных
стыков закреплены предохранителями.**

1. *Сверка ADR-0035 §1–§4 по коду — без рассинхрон:*
   - §1 типы: все восемь форм тела идут через `createJsonBodyPayload` —
     `Flow.Publisher<ByteBuffer>`/`<String>` (мост `adaptToByteBufferPublisher`),
     `BodyPublisher`, `InputStream`, `Supplier<InputStream>`, `Supplier<BodyPublisher>`,
     `Supplier<Flow.Publisher<ByteBuffer>>`, `Path`, `File`.
   - §2 Content-Type: явный `@ContentType`/`@RequestHeader("Content-Type")` чтится;
     стриминговые типы при неявном → `application/octet-stream`; `String`/`byte[]`/POJO
     → `requestType.mime()`.
   - §3 модель/билдер: `WireRequest.bodyPublisherSupplier` + 7-арг backward-compat
     конструктор + `withBodyPublisher`/`Builder`; supplier переживает `@Retry` и
     307/308 (`HttpTemplate.nextHop` его сохраняет); `logRequest` пишет
     `<streaming body>` не материализуя поток.
   - §4 валидация: `ControllerValidator` режет множественный `@RequestBody`,
     комбинации с `@RequestParam`/`@PathVariable`/`@RequestHeader`/`@Cookie`/
     `@MultipartFormFile` и `@Get`-тело (`RequestBodyValidationTest`).
2. *Непокрытые нормативные стыки → +5 предохранителей (зелёные на текущем
   коде, чувствительность структурная):*
   - `RequestBodyStreamingWireMockTest` (+4):
     `testUploadSupplierBodyPublisherAcrossRetry` (503→200, supplier 2×),
     `testUploadSupplierFlowPublisher` (свежий publisher),
     `testRedirect307ReplaysStreamingSupplierBody` (переигровка на 2-м хопе),
     `testUploadFileDefaultsToOctetStream` (Content-Length + octet-stream).
   - `HttpTemplateLoggingTest` (+1): `streamingBodyLogsPlaceholderWithoutConsumingSupplier`
     (`<streaming body>`, supplier не вызывается).
3. *Дрейфы формулировок ADR (задокументированы, кода не тронул):* §2 ссылается на
   `@Post(contentType=…)`/`@Put(contentType=…)`, хотя атрибут — `requestType`
   (сырой media-type задаёт `@ContentType`); §3 «content length when available»
   внутренне противоречив — длину не узнать, не подняв supplier, что §3 запрещает.
4. *Non-goal:* `null` стриминговый аргумент → Content-Type = `requestType.mime()`
   (`application/json`), а не octet-stream (объявленный тип параметра для
   null-случая не читается). Тело пустое, оба типа защитимы — оставлено.
5. *Документы:* ADR-0035 → `## Amendment #198` (проверен и закрыт); DRAFT.md
   (запись #198); `.agent/days/2026-10-17.md` (сессия #198); mailbox (сообщение
   #198, гигиена: удалён #195).
6. *Гейты:* `./gradlew test javadoc` — **1229/1229** (core 1149 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
   0 failures, javadoc зелёный под `-Werror`, 0 warnings.

## Технические заметки

- `./gradlew :test --tests 'X'` (с `:`) — фильтр; полный `test` затем
  пересобирает корневой `:test` (фильтр — часть инпутов), субмодули up-to-date.
  Полноту проверять по счётчику test-results XML.
- ADR-0035 — код **чистый**; новые тесты — предохранители, не red-first.
- Прочее, не тронутое: progress × retry (progress сбрасывается на каждой
  попытке — per-attempt, осознанно), `Supplier<String>`/`Supplier<byte[]>`/
  `Supplier<Path>` (код поддерживает сверх ADR — суперсет), raw `Flow.Publisher`
  без supplier не пересепляется (документированное ограничение §Negative).

## Контекст для следующей сессии (#199)

1. **Нить «ADR против кода» по принятым ADR'ам, похоже, пройдена** (#184–#198:
   ~13 «тихих» находок + 3 чистых среза). Дальнейшие варианты света:
   (a) **free-аудит мутабельности/жизненного цикла:** `ModuleInfo`/
   `SimpleModuleInfo` (потоковая безопасность геттеров, `Map.copyOf` уже есть),
   `ServerSentEventReconnector` (watchdog-закрытие, брошенный не-закрытый
   стрим — утечка виртуального потока сторожевого таймера);
   (b) **test-javadoc батч №2** (кандидат №1): `cookie` 2, `codec` 4,
   `compression` 3, `tracing` 3, `interceptor` 4 — многосессионная работа;
   (c) новые лёгкие ADR (ADR-0045+), если свет аудита закончится.
2. **Ветка** `agent-loop`; HEAD до этого коммита — #197 (`af2d280`).
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 198. Часовой пояс контейнера +04 (может «откатываться»
   между запусками — писать реальный календарный день, 2026-10-17).
