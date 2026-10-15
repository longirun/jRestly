# Last Session: #141 (2026-10-15)

## Что сделано

**Аудит (вопрос Антона №2), срезы `ProgressBodyHandler` и стриминг декомпрессии** — найдено и исправлено 2 скрытых дефекта.

**🎯 Находка №1 — Custom ContentCompressor ломал стриминговые ответы:**
- При регистрации кастомного `ContentCompressor` (SPI ADR-0041, например Brotli/Zstandard/custom SPI) стриминговые ответы (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`) безусловно оборачивались в `new InflaterInputStream(response.stream())` (Deflate) при `!isGzip`, полностью игнорируя кастомный компрессор и приводя к `ZipException`.
- Фикс (ADR-0057): в `ContentCompressor` добавлен `default InputStream decompress(InputStream stream)`, переопределен в `GzipContentCompressor` и `DeflateContentCompressor`, в `HttpTemplate.handleDecompression` добавлен приоритетный вызов `customCompressor.decompress(response.stream())`.
- TDD: E2E тесты в `RequestCompressionWireMockTest` + unit-тесты в `ContentCompressorTest`.

**🎯 Находка №2 — Асимметрия ProgressBodyPublisher на пустых телах:**
- `ProgressBodySubscriber` (download) на пустых телах гарантированно шлёт `(0, 0)`.
- `ProgressBodyPublisher` (upload) на пустых телах не вызывал прогресс. Базовый JDK `HttpClient` (`Http1Request`) не подписывается на Flow Publisher при `contentLength == 0`.
- Фикс (ADR-0057): `ProgressBodyPublisher` дополнен методом `notifyZeroLengthIfPending()` с CAS guard (`AtomicBoolean notifiedZero`), а `JdkHttpEngine` вызывает его перед `send`/`sendAsync`.
- TDD: `ProgressBodyPublisherTest.handlesEmptyBodyWithTotalZero` и WireMock E2E `UploadProgressWireMockTest.emptyUploadBodyInvokesProgressListenerWithZero`.

Оформлен `docs/0.10.0/ADR-0057-streaming-decompression-spi-and-zero-length-upload-progress.md`.

## Гейты

- `./gradlew test` → **1047/1047** (135 классов, 0 failures, 0 errors, +5 тестов).
- `compileJava`/`compileTestJava` → 0 warnings под `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под `-Xdoclint:all -Werror`.
- Zero-dep POM: строго 0 runtime зависимостей.

## Контекст для следующей сессии (#142)

### Следующий шаг:
- Кандидат №3 из активной зоны DRAFT.md: OkHttpEngine / ApacheHttpEngine external modules + сравнительные бенчмарки (развилка Антона №1, ADR-0058).
- Или остаток аудита: edge cases `HttpTemplate`, гонки lazy-init, `WireRequest`/`WireResponse`.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **Фильтрованный прогон — через `:test`**: `./gradlew :test --tests 'X'` не задевает
  `:benchmarks:test` (глобальный `test --tests` валится на «No tests found» в benchmarks).
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск): никогда не использовать
  для order-sensitive данных (заголовки, куки, query).
- **Тесты сравнивают точную строку Cookie-заголовка** — любые изменения в порядке параметров
  сломают `MethodMetadataTest`.
- **JDK HttpClient не подписывается на Flow Publisher при `contentLength == 0`**: для upload progress
  нужен явный вызов `notifyZeroLengthIfPending()`.
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно **1047**).
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать.
- **Next free ADR number:** ADR-0058.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1047/1047** (135 классов).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
