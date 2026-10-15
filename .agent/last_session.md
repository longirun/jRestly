# Last Session: #134 (2026-10-15)

## Что сделано

1. **Реализован эпик Zero-Copy Raw-Byte Codec Pipeline and Stream-Free Transport Headers (ADR-0054):**
   - Создан `docs/0.10.0/ADR-0054-zero-copy-raw-byte-json-pipeline-and-stream-free-transport-headers.md` (статус **Accepted**).
   - В `JsonCodec` добавлены методы `deserializeBytes(byte[] payload, Type type)` и `serializeToBytes(Object value)`.
   - В `JacksonCodec` реализован потоковый парсинг напрямую из `byte[]` через `mapper.readValue(payload, javaType)` и сериализация `mapper.writeValueAsBytes(value)`.
   - В `MoshiCodec` реализована прямая работа с Okio `Buffer.write(payload)` и `Buffer.readByteArray()`.
   - В `NoOpJsonCodec` реализована безопасная поддержка сырых байтов.
   - В `HttpTemplate.processResponse()` материализация `String payload` сделана ленивой. Для DTO и `byte[]` парсинг идет напрямую из `byte[]` без создания промежуточных строк `new String(bytes, UTF_8)`.
   - В `JdkHttpEngine.toHeaders()` Stream API заменен на прямой плоский цикл, возвращающий `List.copyOf(list)` (1 аллокация).
   - В `HttpTemplateBuilder.createHeaders()` добавлен fast-path для пустых auth-заголовков.
   - Замеры JMH `-prof gc`:
     * Batch запросы `jrestly_getUsersBatch`: аллокации сократились с **94.1 KB/op до 87.2 KB/op** (срезано **~7 КБ мусора с каждой операции**!).
     * Одиночные запросы `jrestly_getUser`: аллокации сократились с **51.0 KB/op до 50.1 KB/op**.
     * Многопоточный бенчмарк (16 потоков): паузы GC сократились с **12.0 ms до 7.0 ms**!
   - Добавлены тесты в `JacksonCodecDefaultsTest`, `MoshiCodecTest`, `NoOpJsonCodecTest` (+4 теста, всего **1026/1026 тестов** в 133 классах).
2. **Обновлены артефакты памяти и проекта:**
   - `ROADMAP.md`: зафиксирован ADR-0054, следующий свободный — ADR-0055.
   - `DRAFT.md`: эпик закрыт, активная зона обновлена.
   - `.agent/days/2026-10-15.md`: добавлен лог сессии #134.
   - `.agent/mailbox.md`: подготовлен отчет Антону.
   - Достигнута отметка **1026/1026 тестов** в 133 классах.

## Гейты

- `./gradlew test` → **1026/1026** тестов зелёные (133 класса, 0 failures, 0 errors, +4 теста).
- `compileJava` и `compileTestJava` → 0 warnings под постоянными флагами `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под постоянным флагом `-Xdoclint:all -Werror`.
- Zero-dep core строго подтвержден: ровно 0 `<dependency>` в POM публикации Maven.

## Контекст для следующей сессии (#135)

### Активная зона DRAFT:
- **Кандидат №1:** test-javadoc (наведение лоска в тестовых классах).
- **Кандидат №2:** HTTP Trailers & Advanced Streaming ergonomics.
- **Кандидат №3:** OkHttpEngine / ApacheHttpEngine external modules.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах регистрировать и закрывать через `.close()` в `@AfterEach`.
- **`HttpTemplate` package-private**: снаружи пакета `ru.jrestly.http` класс недоступен; запуск конвейера всегда идет через `HttpTemplateBuilder.execute()`.
- **Path variables в jRestly**: в URL синтаксис `${param}`, в аннотации `@PathVariable(name = "param")` (без `${}`).
- **Аннотации HTTP-методов**: `@Get(path = "...")`, `@Post(path = "...")`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно 1026 в 133 классах).
- **Запуск JMH бенчмарков:** `./gradlew :benchmarks:jmh -PjmhArgs="..."`.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать (релизы запаузены per maintainer agreement).
- **Next free ADR number:** ADR-0055.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1026/1026** (133 класса).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
