# Last Session: #135 (2026-10-15)

## Что сделано

1. **Реализован эпик Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055):**
   - Создан `docs/0.10.0/ADR-0055-zero-garbage-dispatch-pipeline-and-hot-path-allocation-pruning.md` (статус **Accepted**).
   - В `MethodMetadata` предвычислены и закэшированы: `staticCookieHeader` (`formatCookieHeader(staticCookies)`), `hasCookieParams`, `hasExplicitContentTypeInStaticHeaders`, `isStaticConditional` и `hasTrace`.
   - В `HttpTemplateBuilder.resolveCookies()` добавлен fast-path: при отсутствии cookie-параметров возвращается `methodMetadata.staticCookieHeader()` напрямую без создания `LinkedHashMap` (0 heap allocations). При наличии параметров форматирование выполняется через `StringBuilder` без Stream API.
   - В `HttpTemplateBuilder.createHeaders()` добавлен fast-path: если заголовки не имеют динамических элементов, куки, трассировки и явного `Content-Type`, `staticHeaders` возвращаются напрямую без создания промежуточных `ArrayList`.
   - В `HttpTemplateBuilder.getAuthHeaderNames()` аудит переведен на `List.of()` и плоский цикл, устранив Stream API.
   - Для методов без query параметров `createRequestParams()` пропускается, устанавливая неизменяемый синглтон `List.of()`.
   - В `HttpTemplateBuilder.resolveCompression()` задействован статический синглтон `DEFAULT_NO_COMPRESSION`.
   - В `HttpTemplate.createUri()` сборка query параметров переписана с Java Stream API на прямой `StringBuilder` с $O(1)$ итерацией.
   - В `HttpTemplateBuilder.createUrlEncodedBody()` устранен `parameters.clone()` и Stream API, заменен на прямой `StringBuilder`.
   - В `isConditionalRequest()` проверка переведена на прямой цикл с case-insensitive проверкой без аллокации строк `toLowerCase()`, а при отсутствии параметров возвращается предвычисленный `isStaticConditional()`.
   - В `HttpTemplate.processResponse()` аллокация `Duration.ofNanos(...)` отложена и выполняется строго при наличии `responseHeadersHolder` или активного `ResponseHeaders.CURRENT_CAPTOR`.
   - В `HttpTemplate.exchange()` массив `attemptsHolder` аллоцируется строго лениво (только при активных метриках или circuit breaker).
   - `HttpEngineOptions` предвычисляется один раз в `HttpTemplateBuilder` и сохраняется в `HttpTemplate`, устраняя повторные аллокации на каждый hop и retry.
   - В `AbstractHttpClient.isLoginInBackgroundNeeded()` проверка `moduleInfo.getAuthProvider().isAuthorized()` вынесена на первое место, устраняя избыточные вызовы Reflection API к аннотациям на авторизованных запросах.
   - Замеры JMH `-prof gc`:
     * `jrestly_getUser`: аллокации снизились с **49898 B/op до 49378 B/op** (-520 B/call).
     * `jrestly_ping_zeroDepRaw`: аллокации снизились с **48030 B/op до 47392 B/op** (-638 B/call).
   - Добавлены тесты в `MethodMetadataTest` (+3 теста) и `ZeroGarbagePipelineTest` (+6 тестов).
   - Достигнута отметка **1035/1035 тестов** в 134 классах.
2. **Обновлены артефакты памяти и проекта:**
   - `ROADMAP.md`: зафиксирован ADR-0055, следующий свободный — ADR-0056.
   - `DRAFT.md`: эпик закрыт, активная зона обновлена.
   - `.agent/days/2026-10-15.md`: добавлен подробный лог сессии #135.
   - `.agent/mailbox.md`: отправлен отчет Антону.

## Гейты

- `./gradlew test` → **1035/1035** тестов зелёные (134 класса, 0 failures, 0 errors, +9 тестов).
- `compileJava` и `compileTestJava` → 0 warnings под постоянными флагами `-Xlint:all -Werror`.
- `javadoc` → 0 warnings под постоянным флагом `-Xdoclint:all -Werror`.
- Zero-dep core строго подтвержден: ровно 0 `<dependency>` в POM публикации Maven.

## Контекст для следующей сессии (#136)

### Активная зона DRAFT:
- **Кандидат №1:** test-javadoc (наведение лоска в тестовых классах).
- **Кандидат №2:** HTTP Trailers & Advanced Streaming ergonomics (расширенные сценарии работы с трейлерами и чанками в стриминге данных — LLM / SSE).
- **Кандидат №3:** OkHttpEngine / ApacheHttpEngine external modules.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах регистрировать и закрывать через `.close()` в `@AfterEach`.
- **`HttpTemplate` package-private**: снаружи пакета `ru.jrestly.http` класс недоступен; запуск конвейера всегда идет через `HttpTemplateBuilder.execute()`.
- **Path variables в jRestly**: в URL синтаксис `${param}`, в аннотации `@PathVariable(name = "param")` (без `${}`).
- **Аннотации HTTP-методов**: `@Get(path = "...")`, `@Post(path = "...")`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас ровно 1035 в 134 классах).
- **Запуск JMH бенчмарков:** `./gradlew :benchmarks:jmh -PjmhArgs="..."`.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать (релизы запаузены per maintainer agreement).
- **Next free ADR number:** ADR-0056.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1035/1035** (134 класса).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
