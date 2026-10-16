# ADR-0060: Pluggable Payload Formats & Media-Type-Aware Codec SPI (XML and Beyond)

> 2026-10-16 · Accepted · Status: Ф1–Ф2 реализованы (Ф3 опциональна)
> Next free ADR: 0061
> Эпик-кандидат №4 из активной зоны DRAFT («XML & pluggable payload formats»).
> Сессия #177: аудит зоны + концепт. Реализация — последующие сессии по фазам
> (TDD, red-first).
> Вводная от Антона (mailbox, 2026-10-16): «надо не забыть про поддержку xml.
> Как говорится, не JSON'ом единым! Хорошо бы вообще поддержать кастомный
> формат. Если пользователю нужно, он его реализует и продолжит пользоваться
> нашей библиотекой».

## Context

### Что уже есть

- Единственный формат payload — JSON, и он зашит **структурно**, а не только
  по умолчанию:
  - `RequestType` — enum ровно из трёх значений: `APPLICATION_JSON`,
    `APPLICATION_FORM_URLENCODED`, `MULTIPART_FORM_DATA`
    (`ru.jrestly.http.RequestType`). Выбор типа тела задаётся только
    атрибутом `requestType` глагольной аннотации.
  - DTO-тело (`@RequestBody` объект) сериализуется **только** в ветке
    `RequestType.APPLICATION_JSON` (`HttpTemplateBuilder.build()`, проверка
    на строке ~289); `createJsonBodyPayload(...)` всегда вызывает
    `moduleInfo.getJsonCodec()` (строка ~811).
  - Ответ типизированно десериализуется всегда JSON-кодеком, **не глядя на
    `Content-Type` ответа**: `HttpTemplate` — 5 call-site'ов
    (`deserializeBytes` ~802/837, `deserialize` ~1123/1135, `@OnError` ~2383).
  - Мультипарт-часть с `Content-Type: ...json` тоже жёстко сериализуется
    JSON-кодеком (`createPartFromValue`, проверка `contains("json")` ~982).
- SPI и его имя — JSON-only: `ru.jrestly.json.JsonCodec`
  (`serialize`, `deserialize`, `prettyPrint`, `serializeToBytes`,
  `deserializeBytes`), `JsonCodecResolver` (проба строго Jackson → Gson →
  Moshi), `NoOpJsonCodec`, `JRestlyClient.Builder.jsonCodec(...)`,
  `ModuleInfo.getJsonCodec()`.
- При этом **метаданные медиа-типа уже свободны**: `@ContentType` / `@Accept`
  принимают произвольную строку (`MediaType`), заголовки отправляются
  корректно, structured suffix (`application/vnd.company.v1+json`) проходит
  как есть (`AcceptContentTypeWireMockTest`).

### Пробел (подтверждено по коду и зондом)

1. **`@ContentType` обещает больше, чем умеет.** Javadoc `@ContentType`
   рекламирует `application/xml` как пример, но кодек остаётся JSON-овым.
   Зонд (сессия #177, DTO `record Payload(String name, int age)` +
   `@ContentType("application/xml")`):
   ```
   Content-Type: application/xml
   body:         {"name":"Anton","age":42}
   ```
   То есть DTO **не выбрасывается** — он сериализуется в JSON и уходит на
   провод **под чужим Content-Type**. Для строгого XML-сервера это либо
   400, либо тихая порча данных; для читателя кода — обещание, которое
   нарушено молча. Сериализовать DTO в XML сегодня **нельзя вообще**.
2. **Ответ не консультируется с `Content-Type`.** Сервер, отдающий
   `application/xml`, не сможет связать типизированный возврат: ядро
   попытается распарсить XML как JSON и упадёт исключением кодека.
3. **Кастомный формат некуда подключить.** Пользователь с протобуфом, CBOR,
   MessagePack или домашним текстовым форматом не может вставить свой кодек
   — точка расширения названа и сформована под JSON (`prettyPrint`,
   резолвер Jackson/Gson/Moshi). Это ровно то, о чём просил Антон:
   «пользователь его реализует и продолжит пользоваться нашей библиотекой».

### Инварианты

- **Zero-dep ядро:** SPI живёт в ядре, но ни один парсер (XML/CBOR/…)
  в `implementation` ядра не попадает — только `compileOnly`/внешние
  адаптеры (прецедент `JsonCodecResolver` + ADR-0045).
- **Прозрачные методы (ADR-0004):** селекция формата не меняет возврат
  `T`/`Stream<T>`/`Optional<T>`/`CompletableFuture<T>`; никакого `Response<T>`.
- **Не комбайн (ADR-0003):** ядро не тащит XML-парсер, XSD, валидацию,
  полный content-negotiation по q-value.
- **Бинарная совместимость:** `JsonCodec` и `jsonCodec(...)` остаются
  рабочими как есть; существующие JSON-пользователи не замечают изменений.

## Decision

Ввести **медиа-тип-осознанный кодек-SPI** рядом с `JsonCodec`, не трогая
последний.

### SPI (пакет `ru.jrestly.codec`)

```java
public interface PayloadCodec {
    boolean supports(String mediaType);                 // "application/xml", "*+xml", …
    <T> T deserialize(byte[] payload, Type type, String mediaType);
    byte[] serialize(Object value, String mediaType);
    default String prettyPrint(byte[] payload, String mediaType, Charset cs) { … }
}
```

- Побайтовый контракт (`byte[]`), чтобы сохранить zero-copy конвейер
  ADR-0054; `String`-удобства — только `default`-методы.
- `supports` принимает нормализованный media-тип (basename без параметров,
  lower-case) и обязан вернуть `true` для structured suffix, если кодек
  базового формата (например `*+json` для JSON-адаптера, RFC 6839).
- `CodecRegistry` (`ru.jrestly.codec`) — упорядоченный неизменяемый список:
  `resolve(mediaType)` → первый подходящий кодек; `resolveOrDefault(mediaType)`
  → подходящий или **JSON-кодек по умолчанию**.
- `JsonCodecAdapter implements PayloadCodec` — мост, оборачивающий любой
  существующий `JsonCodec`; claim'ит `application/json`, `text/json`,
  `*+json`. Именно он сохраняет весь нынешний SPI живым.
- `NoOpPayloadCodec` — аналог `NoOpJsonCodec` для zero-dep standalone
  (raw-типы не через кодек, как сейчас).

### Селекция

- **Запрос.** Эффективный media-тип = явный заголовок `Content-Type` →
  `@ContentType` → `requestType.mime()`. `CodecRegistry.resolve(...)`
  выбирает кодек; при отсутствии подходящего и **bindable DTO** — **fail-fast**
  с внятным сообщением («no codec registered for Content-Type
  application/xml; register a PayloadCodec or send a raw body type»).
  Raw-типы (`String`, `byte[]`, `InputStream`, `Stream`, `Path`, `File`,
  `Supplier`, `Flow.Publisher`, `HttpRequest.BodyPublisher`) по-прежнему
  минуют кодек.
- **Ответ.** Media-тип = `Content-Type` ответа (charset — через
  `resolveCharset`, ADR-0056) → `CodecRegistry.resolve(...)`; при
  отсутствии/неизвестном — **JSON-кодек по умолчанию** (строгая обратная
  совместимость: сервер без `Content-Type` или с `text/plain`, но JSON-телом
  продолжает работать через JSON-адаптер). Raw-возвраты не декодируются.
- `@Accept` остаётся декларацией заголовка; в fallback-выборе ответа может
  использоваться первый допустимый media-тип (необязательный шаг фазы 2).
- `prettyPrint` в логах идёт через `resolveOrDefault` — формат лога
  совпадает с форматом тела.
- Мультипарт-части (ADR-0024) перестают угадывать формат по `contains("json")`
  и используют `CodecRegistry` по media-типу части.

### Совместимость и конфигурация

- `JsonCodec`, `JsonCodecResolver`, `NoOpJsonCodec`, `jsonCodec(...)` не
  меняются. `jsonCodec(...)` внутри регистрирует `JsonCodecAdapter` в
  реестре — поведение JSON-only клиента **байт-в-байт** прежнее.
- Новые билдер-точки: `JRestlyClient.Builder.codec(PayloadCodec)` (добавляет
  в реестр, порядок регистрации = приоритет) и, при необходимости,
  `codecs(CodecRegistry)` для полной замены.
- `ModuleInfo` получает `default CodecRegistry getCodecRegistry()` (строит
  реестр из `getJsonCodec()` — бинарно-совместимое расширение интерфейса);
  `SimpleModuleInfo.Builder` — поле `codecRegistry`.
- Резолв кодека — один раз в `HttpTemplateBuilder.build()`, результат
  кэшируется в `HttpTemplate` (горячий путь не аллоцирует на селекции).

### Адаптеры (внешние, opt-in)

- **XML** как первый официальный адаптер: субмодуль
  `jrestly-codec-jackson-xml` (Jackson `XmlMapper`; `api`-зависимость
  только у субмодуля, ядро — 0 зависимостей) либо пользовательский
  `PayloadCodec` на JAXB. Решает ровно inline-пример из javadoc
  `@ContentType`.
- Любой другой формат (protobuf, CBOR, MessagePack, свои) — просто
  реализация `PayloadCodec` пользователем, без изменений в ядре
  (прямой ответ на запрос Антона).
- Регистрация: явная (`builder.codec(...)`) в фазе 1; ServiceLoader-автоподхват
  — фаза 3, по прецеденту движков ADR-0058 (`priority()` + стабильная
  сортировка).

### Non-goals

- XML/JSON/любой парсер, XSD, схема-валидация, JAXB-кодогенерация **в ядре**
  (ADR-0003: ядро компактно и zero-dep).
- Полный content-negotiation движок (RFC 9110 q-value, `Vary`) —
  ограничиваемся первым подходящим кодеком по media-типу.
- Изменение raw-путей (`String`/`byte[]`/`InputStream`/`Stream`) и
  стримингового SSE-пути — они остаются кодек-нейтральными.
- Ломать `JsonCodec`/`jsonCodec(...)` или name-swap на `PayloadCodec`.
- Автосериализация неизвестного media-типа «наугад» — вместо этого fail-fast.

## Consequences

- **Положительные.** `@ContentType("application/xml")` перестаёт врать на
  проводе; XML (и любой кастомный формат) становится реально доступен;
  появляется честная точка расширения для не-JSON форматов; JSON-поведение
  не меняется; ошибки конфигурации формата становятся громкими, а не тихими.
- **Риски/цена.**
  - Селекция добавляет шаг на **сборке шаблона** (не на вызов) — обязателен
    кэш кодека в `HttpTemplate`, иначе регрессия zero-garbage ADR-0055.
  - Коллизии claim'ов (два кодека на `application/xml`) — решает порядок
    регистрации; задокументировать.
  - Обратная совместимость ответа требует «JSON-by-default» при неизвестном
    `Content-Type` — иначе ломаются серверы без заголовка. Явный claim
    кодека всегда важнее дефолта.
  - Charset для не-JSON форматов (`application/xml; charset=…`) должен
    уважать ADR-0056, а не хардкодить UTF-8.

## Test strategy

- **Юнит, red-first:** `CodecRegistry` (точное совпадение media-типа,
  `*+json`/`*+xml` suffix, порядок при коллизии, `resolveOrDefault` →
  JSON, отсутствие обработчика → fail-fast на запросе).
- **E2E WireMock:**
  - запрос: DTO + `@ContentType("application/xml")` + зарегистрированный
    XML-кодек → на проводе XML (сегодня — красный: JSON под XML-заголовком);
  - ответ: сервер отдаёт `application/xml`, типизированный возврат + XML-кодек
    → связывается (сегодня — красный: падение JSON-кодека);
  - JSON-предохранители: клиент только с `jsonCodec(...)` — весь текущий
    набор зелёный без правок.
- **Zero-dep:** тестовый кастомный `PayloadCodec` (без внешних библиотек)
  доказывает, что SPI пригоден без изменений ядра и без XML-зависимостей.

## Phase plan

1. **Фаза 1 — SPI ядра.** `PayloadCodec`, `CodecRegistry`, `JsonCodecAdapter`,
   `NoOpPayloadCodec`, билдер-точки, селекция запрос/ответ, fail-fast,
   кэш в `HttpTemplate`. JSON-поведение байт-в-байт; затронутые call-site'ы
   (`HttpTemplate`, `HttpTemplateBuilder`, `ModuleInfo`, `SimpleModuleInfo`).
2. **Фаза 2 — XML-адаптер.** Субмодуль `jrestly-codec-jackson-xml` +
   parity/e2e-тесты; при необходимости — `@Accept`-fallback для выбора
   ответного кодека.
3. **Фаза 3 (опционально).** ServiceLoader-автоподбор кодеков
   (`priority()` по прецеденту ADR-0058) и каталог structured-suffix.

## Amendment (#178, 2026-10-16) — Фаза 1 реализована

- **Ф1 закрыта.** Пакет `ru.jrestly.codec` создан: `PayloadCodec`,
  `CodecRegistry` (`resolve`/`resolveOrDefault`/`of`/builder, нормализация
  media-типа), `JsonCodecAdapter`. Плумбинг: `ModuleInfo.getCodecRegistry()`
  (default), `SimpleModuleInfo` (`codec(...)`/`codecRegistry(...)`),
  `JRestlyClient.Builder.codec(...)`/`codecs(...)`.
- **Селекция** запроса и ответа выполнена, как решено выше: request —
  fail-fast на незарегистрированном media-типе при bindable DTO; response —
  `resolveOrDefault` → JSON-дефолт; multipart — по media-типу части.
- **Отклонение:** `NoOpPayloadCodec` **не** заведён — его роль полностью
  покрывает `JsonCodecAdapter(NoOpJsonCodec)` (zero-dep standalone: тот же
  fail-fast на complex DTO, raw-пути кодек-нейтральны). Меньше мёртвого
  публичного API.
- **Реализационная деталь:** `HttpTemplate.setJsonCodec(...)` обнуляет
  производный `codecRegistry` — так тесты, собравшие шаблон билдером и затем
  подменившие кодек, не удерживают реестр от старого кодека. Билдер ставит
  явный реестр *после* кодека, поэтому пользовательский registry не теряется.
- **Гейт:** +11 тестов (`CodecRegistryTest`, e2e `PayloadCodecWireMockTest`),
  **1164/1164** (core 1099 + engine-okhttp 25 + engine-apache5 25 + benchmarks 15),
  javadoc под `-Werror` зелёный. Впереди Ф2 — субмодуль `jrestly-codec-jackson-xml`.

## Amendment (#179, 2026-10-16) — Фаза 2 реализована (XML-адаптер)

- **Ф2 закрыта.** Новый субмодуль `jrestly-codec-jackson-xml`
  (`codec-jackson-xml/`), `Automatic-Module-Name: ru.jrestly.codec.jacksonxml`.
  Единственный публичный тип — `ru.jrestly.codec.jacksonxml.JacksonXmlCodec`
  (`implements PayloadCodec`): оборачивает Jackson `XmlMapper`; конструктор от
  явного mapper'а либо `defaults()` (disable `FAIL_ON_UNKNOWN_PROPERTIES`,
  `WRITE_XML_DECLARATION=false`, `findAndRegisterModules()` — jsr310 подхватывается
  из classpath через service-file, ядро не тянет). `supports(...)` claim'ит
  `application/xml`, `text/xml`, `*+xml` (RFC 6839). Побайтовый контракт
  сохранён; `null`/пустой payload → `null`, `null`-значение → пустой массив;
  `prettyPrint` — indented XML, для битого ввода — текст как есть.
- **Зависимости:** `api project(':')` + `api jackson-dataformat-xml:2.18.2`
  (транзитивно databind/core/annotations + woodstox/stax2), `compileOnly`
  jsr310. Ядро остаётся строго zero-dep (ADR-0003/0009).
- **Чего НЕ делали (осознанно):** `@Accept`-fallback для выбора ответного
  кодека — не понадобился: серверный `Content-Type` в норме есть, а при
  отсутствии работает JSON-дефолт (обратная совместимость). Оставляем на
  будущее как необязательный шаг.
- **Грабли/детали:** `XmlMapper` наследует `ObjectMapper`, поэтому весь
  контракт `PayloadCodec` лёг 1:1 (`writeValueAsBytes`/`readValue(byte[],
  JavaType)`/`writerWithDefaultPrettyPrinter`). Для детерминированного корня
  e2e-тесты аннотируют DTO `@JacksonXmlRootElement(localName="person")`;
  Jackson XML игнорирует разные имена корня при чтении POJO, так что
  десериализация не зависит от корня.
- **Тесты (+14):** `JacksonXmlCodecTest` (10: claim'ы XML/`+xml`/не-`json`,
  сериализация реального XML без `{`, десериализация, null/empty → null,
  null → empty, игнор неизвестных элементов, prettyPrint+fallback, явный
  mapper, NPE, регистрация jsr310) и e2e `JacksonXmlWireMockTest` (4: DTO под
  `@ContentType(application/xml)` уходит **реальным XML** (в т.ч. `verify`),
  типизированный ответ по `Content-Type: application/xml` связывается,
  JSON-дефолт байт-в-байт цел, без XML-кодека — fail-fast вместо JSON под
  XML-заголовком).
- **Гейт:** **1178/1178** (core 1099 + engine-okhttp 25 + engine-apache5 25 +
  codec-jackson-xml 14 + benchmarks 15); `:codec-jackson-xml:javadoc` под
  `-Werror` зелёный, 0 warnings. **Статус ADR → Accepted.**
- **Ф3 (опциональна):** ServiceLoader-автоподбор кодеков по прецеденту
  движков ADR-0058 (`priority()` + стабильная сортировка) и каталог
  structured-suffix. Не блокирует: явная регистрация `builder.codec(...)`
  уже полностью закрывает запрос Антона «реализовал свой формат — и
  пользуешься».
