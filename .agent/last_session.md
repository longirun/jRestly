# Last Session: #139 (2026-10-15)

## Что сделано

**Аудит проекта на скрытые/незапланированные проблемы** (ответ на вопрос Антона из «концептуальной развилки»). Метод: прицельные срезы по рискованным зонам HTTP-клиента. Найдено и исправлено **2 реальных дефекта**:

1. **Скрытый недетерминизм порядка Cookie-заголовка (критично для воспроизводимости):**
   - `MethodMetadata.parseStaticCookies()` строил `LinkedHashMap`, но возвращал `Map.copyOf(cookies)` — порядок итерации immutable-копий соляризуется на каждый запуск JVM.
   - Заголовок `Cookie:` (статический и склеенный при динамических куки) менял порядок параметров между рестартами; тесты `MethodMetadataTest`/`ZeroGarbagePipelineTest` были скрыто flaky (в #135 прошли из-за удачного солта JVM).
   - Фикс: `Collections.unmodifiableMap(LinkedHashMap)` в `parseStaticCookies()`; прочие `Map.copyOf`/`Set.of` в main — только lookup, проверены.
2. **SSE BOM (WHATWG decode step):**
   - Ведущий UTF-8 BOM первой строки не срезался → первое поле (напр. `retry:`) молча терялось.
   - Фикс: срез `\uFEFF` на первой строке в `ServerSentEventParser` + тест `stripLeadingBom`.

Остальные срезы аудита чисты: bare-потоки, проглоченные исключения, ThreadLocal lifecycle (CURRENT_CAPTOR), reactive publisher в multipart, backoff-математика, повторяемость тела при ретраях (`bodyPublisherSupplier`).

## Гейты

- `./gradlew test` → **1036/1036** тестов зелёные (0 failures, 0 errors, +1 тест).
- `compileJava`/`compileTestJava` → 0 warnings под `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под `-Xdoclint:all -Werror`.

## Контекст для следующей сессии (#140)

### Два пути из развилки Антона (mailbox, 2026-10-15):
1. **Продолжить аудит** (вопрос №2): charset-обработка ответов, `ProgressBodyHandler`, edge cases `HttpTemplate` (2415 строк), гонки lazy-init, `WireRequest`/`WireResponse`.
2. **Кандидат №3** (вопрос №1): OkHttpEngine/ApacheHttpEngine субмодули + сравнительные бенчмарки с другими библиотеками → публичная документация. Это большой эпик — начать с ADR-0056.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`./gradlew test --tests 'X'` валит `:benchmarks:test`** («No tests found»): фильтр применяется ко всем test-таскам. Оценивай зелёность по `:test`; финальный гейт — полный прогон без фильтра.
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск): никогда не использовать для order-sensitive данных (заголовки, куки, query).
- **Тесты сравнивают точную строку Cookie-заголовка** — любые изменения в порядке параметров сломают `MethodMetadataTest`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах закрывать через `.close()` в `@AfterEach`.
- **`HttpTemplate` package-private**: конвейер запускается через `HttpTemplateBuilder.execute()`.
- **Path variables**: в URL `${param}`, в аннотации `@PathVariable(name = "param")`.
- **Аннотации**: `@Get(path=...)`, `@Post(path=...)`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно **1036**).
- **Mailbox-гигиена:** решённые обмены удалены в #139; остаются живые нитки (автономия, векторы зрелости, развилка) + мой отчёт.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать.
- **Next free ADR number:** ADR-0056.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1036/1036**.
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
