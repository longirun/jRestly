# Last Session: #328 (2026-10-19)

## Что сделано

**Сессия #328: Кандидат №12 Фаза 1 — субмодуль `codec-yaml` (официальный YAML-адаптер на Jackson `YAMLMapper`), ADR-0073 Accepted, гейт 1,785/1,785 (+15).**

1. *Приём состояния:* HEAD `e1c46e4` (#327, ADR-0072), чистое дерево.
2. *ADR-0073* (`docs/0.12.0/ADR-0073-codec-yaml-submodule.md`, Accepted):
   - **Фаза 1 = YAML, Фаза 2 = Protocol Buffers (отложен в отдельный ADR):** YAML — генерический формат с зарегистрированными media types (RFC 9512), входит в модель «семейство media type»; ProtoBuf — schema-bound (сгенерированные `Message`/`Parser`), ему нужен свой дизайн-проход.
   - **Движок — Jackson `YAMLMapper` (не голый SnakeYAML):** единый биндинг-движок JSON/XML/YAML (одна семья исключений, records из коробки, тот же ленивый jsr310-паттерн). Пин `jackson-dataformat-yaml:2.18.2`.
   - **Media types:** `application/yaml`, `text/yaml`, `*+yaml` (RFC 6839).
   - **SPI:** `YamlCodecProvider` priority 50 (паритет с XML-адаптером), автообнаружение, opt-out через JSON-only `CodecRegistry`.
3. *Код:*
   - Новый публикуемый субмодуль `codec-yaml` (`ru.jrestly:jrestly-codec-yaml`, версия 0.11.0): `build.gradle` (Java 21, `-parameters`, `-Werror`, javadoc `-Werror`, maven-publish, Automatic-Module-Name `ru.jrestly.codec.yaml`), `YamlCodec` (explicit-mapper ctor + `defaults()`: FAIL_ON_UNKNOWN_PROPERTIES off, WRITE_DATES_AS_TIMESTAMPS off, guarded jsr310), `YamlCodecProvider`, `package-info.java`, `META-INF/services/ru.jrestly.codec.PayloadCodecProvider`.
   - `settings.gradle`: `include 'codec-yaml'`.
   - Ядро: чистое добавление констант `MediaType.APPLICATION_YAML` / `TEXT_YAML` (RFC 9512) — API не сломан, Zero-Dep сохранен.
4. *Тесты (+15):* `YamlCodecTest` (10) + `YamlWireMockTest` (5, включая SPI-автообнаружение и fail-fast opt-out).
5. *Грабли:* WireMock 3.12.1 не имеет `equalToYaml` (javap по standalone-jar) — тело сверяется `equalTo` с выходом `serialize` самого кодека; Jackson YAML котирует строковые скаляры (`name: "Anton"`) — ассерты на форматные маркеры.
6. *Калибровка гейта (важная находка):* fresh-прогон root (`:test --rerun-tasks`) показал **1,642** теста в ядре (0F/0E, 167 классов) при записанной в #327 цифре 1,619 — источники ядра с #327 не менялись, значит прошлый пересчет недоучел 23 теста (true baseline ядра = 1,642). Итоговый честный гейт: **1,785 / 1,785** (root 1642 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + benchmarks 37).
7. *Гейты:* **1,785 / 1,785** green во всех 7 модулях. Javadoc чист под `-Werror`.

## Контекст для следующей сессии (#329)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0073** (`docs/0.12.0/ADR-0073-codec-yaml-submodule.md`), next free **0074**.
3. **Кандидаты на сессию #329:**
   - **Кандидат №12 Фаза 2 — Protocol Buffers-адаптер (`codec-protobuf`, ADR-0074):** дизайн-проход по резолвингу `Message`/`Parser` для произвольного `Type` (прототип-инстанс → `getParserForType()`, кэш парсеров; serialize — только `Message`/known-фабрики, иначе fail-fast), вопрос media type: `application/x-protobuf` (legacy) vs `application/protobuf` vs `*+protobuf`; зависимость `com.google.protobuf:protobuf-java`.
   - **Кандидат №14 — аллокации HTTP-заголовков / W3C Tracing:** устранение промежуточных `ArrayList`/`HashMap` при парсинге и мердже статических и динамических заголовков (`ResponseHeaders`, `HttpTemplateBuilder.createHeaders`, trace-контекст).
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
5. Метрики — гейт **1,785/1,785** (root baseline 1,642 — откалиброван в #328); метрики JaCoCo ядра с #327 (BRANCH 90.65%) — в новом прогоне не пересчитывались (код ядра: +2 константы `MediaType`).
