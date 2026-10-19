# Last Session: #329 (2026-10-20)

## Что сделано

**Сессия #329: Кандидат №12 Фаза 2 — субмодуль `codec-protobuf` (официальный Protocol Buffers-адаптер на protobuf-java), ADR-0074 Accepted, гейт 1,804/1,804 (+19). ОБЕ фазы Кандидата №12 закрыты.**

1. *Приём состояния:* HEAD `2c46c34` (#328, ADR-0073), чистое дерево (только счётчик 329).
2. *ADR-0074* (`docs/0.12.0/ADR-0074-codec-protobuf-submodule.md`, Accepted):
   - **Зависимость `com.google.protobuf:protobuf-java:3.25.5`** (полный рантайм; контракт через `com.google.protobuf.Message`; `protobuf-javalite` — non-goal Ф1).
   - **Media-type family:** `application/x-protobuf` (de-facto) + `application/protobuf` (IANA) + `*+protobuf` (RFC 6839); `application/grpc+proto` осознанно не претендуем (gRPC-фрейминг — отдельный компонент).
   - **Резолвинг парсера (главный дизайн-разрез Ф2):** цель = `Class<? extends Message>`; порядок: (1) публичный статический `PARSER` (protoc-инвариант), (2) статический `getDefaultInstance()` + `getParserForType()`, (3) fail-fast IAE; кэш `ConcurrentHashMap<Class, Parser<?>>`.
   - **Serialize:** `Message`/`MessageLite` → `toByteArray()`, non-message → fail-fast (нет property-рефлексии — формат schema-bound). **prettyPrint:** бинарный → hex, 16 байт/строка.
   - **SPI:** `ProtobufCodecProvider` priority 50, автообнаружение, opt-out через JSON-only реестр. **Ядро:** чистое добавление `MediaType.APPLICATION_PROTOBUF` / `APPLICATION_X_PROTOBUF`.
3. *Код:* новый публикуемый субмодуль `codec-protobuf` (`ru.jrestly:jrestly-codec-protobuf`, 0.11.0): `build.gradle` (Java 21, `-Werror`, javadoc `-Werror`, maven-publish, Automatic-Module-Name `ru.jrestly.codec.protobuf`), `ProtobufCodec` (no-arg ctor + `defaults()`, `supports`, `deserialize`/`serialize`/`prettyPrint`, `parserFor` package-private для cache-теста), `ProtobufCodecProvider`, `package-info.java`, `META-INF/services`-регистрация. `settings.gradle`: `include 'codec-protobuf'`.
4. *Тесты (+19):* `ProtobufCodecTest` (14: family-claim/анти-перехват, round-trip `Timestamp` и `Struct`/`Value`-мапы, null/empty-контракты, corrupt → `UncheckedIOException`, fail-fast non-Message/non-Class/serialize-non-message, кэш парсеров (PARSER-ветка через синтетический `FakeGeneratedMessage` + well-known через статическую фабрику), hand-written-стубы `StubBase`/`StubWithFactory`/`StubNoFactory` с полным `Message`-поверхностью, prettyPrint hex) + `ProtobufWireMockTest` (5: e2e `@ContentType(APPLICATION_X_PROTOBUF)` байт-в-байт через `binaryEqualTo`, `@Accept(APPLICATION_PROTOBUF)`-биндинг в `Timestamp`, JSON-путь не тронут, SPI-автообнаружение, JSON-only fail-fast).
5. *Грабли (важные):* (a) **well-known типы protobuf-java имеют ПРИВАТНЫЙ `PARSER`** (не protoc-генерация) — их не видно через `getField`, спасает ветка статического `getDefaultInstance()`; зафиксировано в ADR §3; (b) `Struct.getFields()` deprecated → `getFieldsOrThrow`; (c) test-classpath protobuf-модуля без JSON-движка → явный `testImplementation jackson-databind:2.18.2`; (d) `Parser<?>` ≠ subtype `Parser<? extends Message>`.
6. *Гейты:* **1,804 / 1,804** green во всех 8 модулях (root 1642 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + **codec-protobuf 19** + benchmarks 37). Javadoc чист под `-Werror`.

## Контекст для следующей сессии (#330)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0074** (`docs/0.12.0/ADR-0074-codec-protobuf-submodule.md`), next free **0075**.
3. **Кандидаты на сессию #330:**
   - **Кандидат №14 — аллокации HTTP-заголовков / W3C Tracing:** устранение промежуточных `ArrayList`/`HashMap` при парсинге и мердже статических и динамических заголовков (`ResponseHeaders`, `HttpTemplateBuilder.createHeaders`, trace-контекст) — TDD-шаг с JMH-доказательством при желании.
   - **Дистилляция Кандидата №12 в `ROADMAP.md`:** эпик закрыт целиком (Ф1 = ADR-0073 `codec-yaml`, Ф2 = ADR-0074 `codec-protobuf`) — оформить в выполненные эпики + обновить статус цикла 0.12.0.
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
   - `bc` в песочнице нет — суммы считать awk'ом или руками.
5. Метрики — гейт **1,804/1,804** (root baseline 1,642); метрики JaCoCo ядра с #327 (BRANCH 90.65%) — в этом прогоне не пересчитывались (код ядра: +2 константы `MediaType`).
