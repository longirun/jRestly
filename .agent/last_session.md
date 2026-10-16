# Last Session: #159 (2026-10-16)

## Что сделано

**Finding #158 закрыт: ServiceLoader-автообнаружение внешних движков работает end-to-end.**

1. **TDD red-first e2e-гейт:** `SpiEngineAutoDetectionTest` (3 теста) +
   `TestSpiEngineProvider` в тестовом classpath
   (`src/test/resources/META-INF/services/ru.jrestly.engine.HttpEngineProvider`).
   Провайдер доступен только при взведённом статическом флаге
   (по умолчанию false — другие тесты не затронуты). До фикса красный ровно
   целевой кейс; предохранители (JDK-fallback без провайдера; явный движок
   побеждает) зелёные до и после.
2. **Фикс:** `AbstractHttpClient.createConnection()` целиком делегирует
   `HttpEngineResolver.resolve(moduleInfo)` (явный → SPI по приоритету → JDK).
   Дублирующая сборка JDK-клиента из клиента удалена. Единственный источник
   правды — `JdkHttpEngine.create(moduleInfo)`.
3. **ADR-0026 сохранён:** клиентский managed-executor (custom → virtual threads)
   создаётся в `AbstractHttpClient` (для стадий пайплайна) и закрывается в
   `close()`; `JdkHttpEngine.create` дополнительно сам honor-ит
   `isVirtualThreads()` (собственный managed executor, закрывается в
   `engine.close()`). `ExecutorConfigTest` не менялся.
4. **Бенчмарки честные:** дефолтный клиент с обоими субмодулями на classpath
   после фикса уходил бы на OkHttp/Apache5 → JDK-транспорт закреплён явным
   `JdkHttpEngine.create` в `JRestlyBenchmarkClient(baseUrl)` и
   `DispatchOverheadBenchmark`; примечание в `BENCHMARKS.md`; README уточнён.
5. ADR-0058: Finding #158 → Resolution #159, header «Complete, Finding закрыт».

## Гейты

- **1112/1112** (core 1051 + engine-okhttp 23 + engine-apache5 23 + benchmarks 15),
  полный `./gradlew test` зелёный; javadoc-гейты (root + оба субмодуля) зелёные;
  0 warnings. Red-first подтверждён прогоном до фикса.

## Контекст для следующей сессии (#160)

1. **ADR-0058 полностью завершён** (фазы 1/2a/2b, публичные цифры, Finding).
   Следующий свободный ADR — **0059**.
2. **Активная зона (DRAFT.md):** кандидат №1 test-javadoc (лоск в тестах),
   кандидат №2 HTTP Trailers & Advanced Streaming ergonomics
   (LLM/SSE), кандидат №3 закрыт. Можно взять любой свободный ход или новый ADR.
3. **Опциональный хвост по бенчмаркам:** повторный прогон `-f 2 -i 5` для
   снижения variance (машина шумная); зонд overhead декларативного слоя на
   сетевом пути (~160 µs гипотеза из #147) — по желанию, не обязателен.
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   внешний `timeout`; ветка agent-loop. WireMock без явного Content-Length в
   стабе всегда отвечает chunked (урок #156). Запуск root-тестов с фильтром:
   `./gradlew :test --tests "..."` (без `:` фильтр падает на `:benchmarks:test`).
