# Last Session: #125 (2026-10-15)

## Что сделано

1. **Реализован эпик Zero-Dependency Standalone Mode (ADR-0045):**
   - Создан `docs/0.10.0/ADR-0045-zero-dependency-standalone-and-noop-codec.md` (статус **Accepted**).
   - `NoOpJsonCodec` (`ru.jrestly.json`): потокобезопасный singleton `NoOpJsonCodec.instance()`. Best-effort `prettyPrint`, serialize null -> "null", deferred fail-fast `IllegalStateException` при попытке сериализовать/десериализовать объект.
   - `JsonCodec.noop()`: публичный фабричный метод.
   - `JsonCodecResolver`: при отсутствии Jackson/Gson/Moshi возвращает `NoOpJsonCodec.instance()` вместо eager падения при билде клиента.
   - `HttpTemplate.probePayloadDecode`: добавлена поддержка raw `byte[].class`.
   - Тесты: 14 новых тестов (`NoOpJsonCodecTest`, `ZeroDepStandaloneWireMockTest`, обновление `JsonCodecResolverChainTest`).
2. **Обновлены артефакты памяти и проекта:**
   - `ROADMAP.md`: зафиксирован ADR-0045, следующий свободный — ADR-0046.
   - `DRAFT.md`: активная зона обновлена, кандидат №1 — ADR-0046.
   - `.agent/mailbox.md`: Антону отправлен статус о реализации направления №2 (честный zero-dep).

## Гейты

- `./gradlew test compileJava compileTestJava javadoc` → **941/941** (114 классов), 0 warnings под постоянными флагами `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
- Zero-dep core строго подтвержден: ровно 0 `<dependency>` в POM.

## Контекст для следующей сессии (#126)

### Активная зона DRAFT:
- **Кандидат №1:** Zero-Dep Authorization SPI & TokenManager (ADR-0046) — OAuth2 single-flight via CompletableFuture, transparent 401 retry, Bearer/Basic/ApiKey.
- **Кандидат №2:** Interceptor Chain Short-Circuit SPI (ADR-0047).
- **Кандидат №3:** HttpTemplate инкапсуляция package-private.
- **Кандидат №4:** JMH Benchmarks suite.
- **Кандидат №5:** test-javadoc.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах регистрировать и закрывать через `.close()` в `@AfterEach`.
- **Path variables в jRestly**: в URL синтаксис `${param}`, в аннотации `@PathVariable(name = "param")` (без `${}`).
- **Аннотации HTTP-методов**: `@Get(path = "...")`, `@Post(path = "...")`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас 941).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать (релизы запаузены per maintainer agreement).
- **Next free ADR number:** ADR-0046.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **941/941** (114 классов).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
