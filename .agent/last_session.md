# Last Session: #130 (2026-10-15)

## Что сделано

1. **Реализован эпик Multi-Client Performance Benchmarks Under Concurrent Load & Allocation Profiling (ADR-0050):**
   - Создан `docs/0.10.0/ADR-0050-multi-client-concurrent-benchmarks-and-allocation-profiling.md` (статус **Accepted**).
   - В изолированный субпроект `benchmarks/` добавлены корпоративные тяжеловесы:
     * Apache HttpClient 5.4.1 (`ApacheHttpClientBenchmarkClient`) с пулом `PoolingHttpClientConnectionManager` (200 соединений).
     * Spring Framework 6.2.2 (`SpringRestClientBenchmarkClient`) с декларативными `@HttpExchange` интерфейсами и `HttpServiceProxyFactory`.
   - Реализован бенчмарк высокой параллельной нагрузки `ConcurrentLoadNetworkBenchmark` (16 потоков, режим Throughput):
     * Retrofit: 358.58 ops/s
     * Apache HttpClient 5: 348.99 ops/s
     * Raw JDK HttpClient: 313.42 ops/s
     * **jRestly (Zero-Dep Proxy): 286.05 ops/s** (+6.1% быстрее Spring RestClient!)
     * OpenFeign: 284.21 ops/s
     * **Spring 6 RestClient: 269.57 ops/s**
   - Проведено профилирование аллокаций памяти (JMH `-prof gc`):
     * `jRestly.getUser`: **53.1 KB/op** (на 17.5% меньше мусора, чем Spring 64.4 KB/op, и на 21% меньше, чем Feign 67.2 KB/op).
     * Чистота GC: jRestly удержал `gc.count ≈ 0` (0 ms пауз GC), тогда как Feign и raw HttpClient вызвали по 4 сборки мусора (до 38 ms пауз).
   - Реализован сценарий пакетной нагрузки: эндпоинт `/api/users/batch` в `BenchmarkHttpServer` (50 `UserDto`, ~6.5 КБ JSON) и метод `getUsersBatch()` во всех 6 клиентах.
   - Расширен `BenchmarkSmokeTest` (+3 теста) для валидации всех 6 клиентов, batch и concurrent сценариев.
2. **Обновлены артефакты памяти и проекта:**
   - `ROADMAP.md`: зафиксирован ADR-0050, следующий свободный — ADR-0051.
   - `DRAFT.md`: активная зона обновлена, кандидат №1 — test-javadoc.
   - `.agent/days/2026-10-15.md`: добавлен лог сессии #130.
   - `.agent/mailbox.md`: Антону отправлен подробный ответ с данными JMH бенчмарков.

## Гейты

- `./gradlew test` → **993/993** тестов зелёные (128 классов, 0 failures, 0 errors).
- `compileJava` и `compileTestJava` → 0 warnings под постоянными флагами `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под постоянным флагом `-Xdoclint:all -Werror`.
- Zero-dep core строго подтвержден: ровно 0 `<dependency>` в POM публикации Maven.

## Контекст для следующей сессии (#131)

### Активная зона DRAFT:
- **Кандидат №1:** test-javadoc (наведение лоска в тестовых классах).
- **Кандидат №2:** HTTP Trailers & Advanced Streaming ergonomics.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах регистрировать и закрывать через `.close()` в `@AfterEach`.
- **`HttpTemplate` package-private**: снаружи пакета `ru.jrestly.http` класс недоступен; запуск конвейера всегда идет через `HttpTemplateBuilder.execute()`.
- **Path variables в jRestly**: в URL синтаксис `${param}`, в аннотации `@PathVariable(name = "param")` (без `${}`).
- **Аннотации HTTP-методов**: `@Get(path = "...")`, `@Post(path = "...")`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас 993 в 128 классах).
- **Запуск JMH бенчмарков:** `./gradlew :benchmarks:jmh -PjmhArgs="..."`.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать (релизы запаузены per maintainer agreement).
- **Next free ADR number:** ADR-0051.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **993/993** (128 классов).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
