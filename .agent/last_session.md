# Last Session: #177 (2026-10-16)

## Что сделано

1. **Концепт ADR-0060 оформлен** —
   `docs/0.10.0/ADR-0060-pluggable-payload-formats-and-media-type-aware-codec-spi.md`
   (Status: Proposed). Продолжение нити Антона «не JSON'ом единым» (XML +
   кастомные форматы). Эпик-кандидат №4 активной зоны DRAFT.

2. **Факт уточнён зондом (правка к #176).** #176 писал, что
   `@ContentType("application/xml")` «молча роняет DTO» (body == null).
   Реальный прогон (DTO `record Payload(String, int)` + `@ContentType(
   APPLICATION_XML)` над WireMock) показал иное: тело сериализуется JSON-кодеком
   и уходит **под чужим заголовком** —
   `Content-Type: application/xml` / body `{"name":"Anton","age":42}`.
   Причина по коду: `requestType` (ветка сериализации, `HttpTemplateBuilder:289`)
   и `@ContentType` (только подмена заголовка) разведены;
   `createJsonBodyPayload` всегда зовёт `getJsonCodec()` (`:811`).
   Ответный путь тоже слеп к `Content-Type` ответа — 5 call-site'ов
   `jsonCodec.deserialize*` в `HttpTemplate` (`:802/:837/:1123/:1135/:2383`).

3. **Решение ADR-0060 (кратко):**
   - новый SPI `ru.jrestly.codec.PayloadCodec` (media-type ↔ значения,
     побайтовый контракт — сохраняет zero-copy ADR-0054);
   - `CodecRegistry` (`resolve(mediaType)` / `resolveOrDefault` → JSON-by-default
     для строгой обратной совместимости);
   - `JsonCodecAdapter` — мост: оборачивает нетронутый `JsonCodec`, claim'ит
     `application/json` / `text/json` / `*+json` (RFC 6839);
   - селекция по request `Content-Type` (явный заголовок → `@ContentType` →
     `requestType.mime()`) и по response `Content-Type` (charset через ADR-0056);
     при незарегистрированном media-типе на запросе — **fail-fast**, не тихая
     подмена; raw-типы (`String`/`byte[]`/`InputStream`/`Stream`/…) минуют кодек;
   - `jsonCodec(...)` сохраняется и внутри регистрирует адаптер — JSON-only
     пользователи не замечают изменений;
   - резолв кодека кэшируется в `HttpTemplate` (один раз на сборку шаблона —
     не на вызов, чтобы не сломать zero-garbage ADR-0055).
   - **Non-goals:** XML/любой парсер + XSD в ядре (ADR-0003), полный
     content-negotiation (q-value/`Vary`), изменение raw/SSE-путей, ломка
     `JsonCodec`/`jsonCodec(...)`.
   - **Фазы:** Ф1 — SPI ядра; Ф2 — субмодуль `jrestly-codec-jackson-xml` (ядро
     остаётся zero-dep); Ф3 — ServiceLoader-автоподбор по прецеденту ADR-0058.

## Гейты

- Код не менялся (только docs + память). Полный
  `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew test javadoc` зелёный,
  **1153/1153** (core 1088 + engine-okhttp 25 + engine-apache5 25 + benchmarks 15),
  0 warnings. Зондовый `ProbeXmlDtoTest` создавался для проверки факта и удалён
  (в дереве нет).

## Контекст для следующей сессии (#178)

1. **Следующий ход — Ф1 ADR-0060 (SPI ядра), TDD red-first.** Точки касания
   (по коду этой сессии):
   - новый пакет `ru.jrestly.codec`: `PayloadCodec`, `CodecRegistry`,
     `JsonCodecAdapter`, `NoOpPayloadCodec`;
   - `HttpTemplateBuilder.build()` (`:289` ветка `APPLICATION_JSON` + `:811`
     `createJsonBodyPayload` + `:982` `contains("json")` для multipart):
     селекция кодека по эффективному request `Content-Type`;
   - `HttpTemplate` 5 call-site'ов `jsonCodec.deserialize*` — ответная селекция
     по `Content-Type` ответа (fallback JSON), + кэш кодека в поле;
   - `ModuleInfo` (`default getCodecRegistry()`) и `SimpleModuleInfo.Builder`
     (поле + `jsonCodec(...)` → регистрация адаптера);
   - `JRestlyClient.Builder` — новые `codec(...)` / `codecs(...)`.
2. **Red-first кандидаты:** `CodecRegistryTest` (exact match, `*+json`/`*+xml`,
   порядок при коллизии, `resolveOrDefault` → JSON); e2e «DTO + XML content-type
   → XML на проводе» (сегодня красный: JSON под XML-заголовком); e2e «response
   `application/xml` + typed return + XML codec → binds»; JSON-предохранители.
3. **Ветка** `agent-loop`; HEAD после #177 — коммит этой сессии.
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессии: `177`.
