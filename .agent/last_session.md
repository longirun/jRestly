# Last Session: #140 (2026-10-15)

## Что сделано

**Аудит (вопрос Антона №2), срез «charset-обработка ответов»** — найдена и исправлена реальная асимметрия декодирования.

**🎯 Разлад charset между стриминговым и буферным путями:**
- Стриминг (`Stream<String>`, SSE) с ADR-0016 декодирует через `resolveCharset(responseHeaders)`;
  буферные пути декодировали жёстко UTF-8: `String`, `Optional<String>`, `@OnError` payload,
  `UnexpectedStatusException.getRawBody()`, `consumePayload`, `extractResponseBodyString`.
- Следствие: один и тот же эндпоинт → корректный текст в стриме и модзибейк в `String`
  (легаси-API с `text/*; charset=windows-1251` / `ISO-8859-1` / `koi8-r`).
- Фикс (ADR-0056, RFC 9110 §8.3.2): `payloadCharset = resolveCharset(responseHeaders)` — один раз
  в `HttpTemplate.processResponse()`, все текстовые материализации через него; fallback UTF-8.
  JSON/`byte[]`/DTO байтовый пайплайн (ADR-0054) не тронут.
- TDD: `ResponseCharsetWireMockTest` (6 E2E тестов; сначала 4 красных — ровно дефекты).
- Оформлен `docs/0.10.0/ADR-0056-response-charset-resolution-for-string-payloads.md`;
  ROADMAP: новая секция COMPLETE ✅, следующий свободный ADR — **0057**.

## Гейты

- `./gradlew test` → **1042/1042** (135 классов, 0 failures, 0 errors, +6 тестов).
- `compileJava`/`compileTestJava` → 0 warnings под `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под `-Xdoclint:all -Werror`.

## Контекст для следующей сессии (#141)

### Остаток аудита (вопрос №2):
`ProgressBodyHandler` (границы чанков, прогресс при ошибках), edge cases `HttpTemplate`
(2415+ строк), гонки lazy-init, `WireRequest`/`WireResponse`, interaction charset × GZIP
(декомпрессия до/после чтения заголовков).

### Второй путь из развилки Антона (вопрос №1):
Кандидат №3 — OkHttpEngine/ApacheHttpEngine субмодули + сравнительные бенчмарки с другими
библиотеками → публичная документация. Большой эпик, начать с ADR-**0057**.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **Фильтрованный прогон — через `:test`**: `./gradlew :test --tests 'X'` не задевает
  `:benchmarks:test` (глобальный `test --tests` валится на «No tests found» в benchmarks).
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск): никогда не использовать
  для order-sensitive данных (заголовки, куки, query).
- **Тесты сравнивают точную строку Cookie-заголовка** — любые изменения в порядке параметров
  сломают `MethodMetadataTest`.
- **Charset ответа:** теперь весь текст (буферный + стриминговый) декодируется через
  `HttpTemplate.resolveCharset(headers)`; новые тесты — `ResponseCharsetWireMockTest`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах закрывать через `.close()` в `@AfterEach` (есть `BaseWireMockTest`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно **1042**).
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать.
- **Next free ADR number:** ADR-0057.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1042/1042** (135 классов).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
