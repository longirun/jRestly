# Last Session: #178 (2026-10-16)

## Что сделано

**ADR-0060, Фаза 1 — SPI ядра реализован** (сессия #177 была только концепт).

1. Новый публичный пакет `ru.jrestly.codec`:
   - `PayloadCodec` — media-type-aware SPI: `supports(normalizedMediaType)`,
     `<T> T deserialize(byte[], Type, String mediaType)`,
     `byte[] serialize(Object, String mediaType)`,
     `default String prettyPrint(byte[], String, Charset)`. Побайтовый контракт
     (zero-copy ADR-0054).
   - `CodecRegistry` — упорядоченный неизменяемый список + дефолт; `resolve`
     (первый подходящий), `resolveOrDefault` (иначе дефолт), `of(JsonCodec)`,
     builder. Нормализация media-типа: срез `;`-параметров, trim, lower-case;
     порядок регистрации = приоритет; дефолт добавляется в конец списка резолва.
   - `JsonCodecAdapter` — мост к нетронутому `JsonCodec`; claim'ит
     `application/json`, `text/json`, `*+json`.
   - (Осознанно **не** заводил `NoOpPayloadCodec` — его роль покрывает
     `JsonCodecAdapter(NoOpJsonCodec)`.)

2. Плумбинг: `ModuleInfo.getCodecRegistry()` (default → `CodecRegistry.of(getJsonCodec())`),
   `SimpleModuleInfo` (поле `codecRegistry`, `Builder.codec(...)`/`codecRegistry(...)`,
   сборка реестра из `jsonCodec` + пользовательских кодеков),
   `JRestlyClient.Builder.codec(PayloadCodec)` / `codecs(CodecRegistry)`.

3. Селекция запроса — `HttpTemplateBuilder.build()` ставит реестр;
   `createJsonBodyPayload` выбирает кодек по эффективному `Content-Type`
   (явный заголовок → `@ContentType` → `requestType.mime()`); нет подходящего и
   body — bindable DTO → **fail-fast** `IllegalStateException` с именем media-типа.
   Raw-типы минуют кодек (как было).

4. Селекция ответа — 5 call-site'ов `HttpTemplate` (вкл. `probePayloadDecode` и
   `handleOnError`; сигнатура `handleOnError` получила `Charset` для re-encode)
   выбирают кодек по `Content-Type` ответа через `resolveOrDefault` → JSON-дефолт
   при неизвестном/отсутствующем типе (обратная совместимость). Multipart-части —
   по media-типу части вместо `contains("json")`.

5. **Грабли:** `setJsonCodec()` теперь обнуляет `codecRegistry` — иначе тесты,
   которые строят шаблон через `HttpTemplateBuilder` и потом руками меняют
   кодек, использовали реестр от старого кодека. Builder ставит реестр *после*
   кодека, поэтому явный registry не теряется.

## Гейты

- `./gradlew test` — **1164/1164** (core 1099 + engine-okhttp 25 +
  engine-apache5 25 + benchmarks 15), всё зелёное.
- `./gradlew :javadoc` — зелёный под `-Werror`, 0 warnings.
- zero-dep ядро сохранено (новые типы зависят только от `java.*` + `ru.jrestly.json`).
- +11 тестов: `ru/jrestly/codec/CodecRegistryTest` (7) +
  `ru/jrestly/PayloadCodecWireMockTest` (4, e2e zero-dep «pipe»-кодек).

## Контекст для следующей сессии (#179)

1. **Следующий ход — Фаза 2 ADR-0060: XML-адаптер.** Субмодуль
   `jrestly-codec-jackson-xml` (Jackson `XmlMapper`; `api`-зависимость только у
   субмодуля, ядро остаётся zero-dep). Реализовать `PayloadCodec` для
   `application/xml` / `text/xml` / `*+xml`; проверить, что
   `@ContentType(MediaType.APPLICATION_XML)` + DTO реально уходит XML, а
   `Accept: application/xml` + типизированный возврат связывается. Плюс parity/
   e2e-тесты субмодуля и запись в `settings.gradle`.
   - Точка входа: `ru.jrestly.codec.PayloadCodec` / `CodecRegistry`,
     регистрация `JRestlyClient.builder().codec(new XmlCodec(...))`.
2. **Опционально:** Фаза 3 — ServiceLoader-автоподбор кодеков (прецедент
   `HttpEngineProvider` + `priority()` ADR-0058).
3. **Обновить ADR-0060:** отметить Ф1 как выполненную (+ Amendment #178 о
   пропуске `NoOpPayloadCodec`); при Ф2 — перевод в Accepted.
4. **Ветка** `agent-loop`; HEAD — коммит #178.
5. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессии: `178`.
