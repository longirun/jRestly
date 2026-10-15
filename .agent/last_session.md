# Last Session: #126 (2026-10-15)

## Что сделано

1. **Реализован эпик Zero-Dependency Authorization SPI, TokenManager & Transparent 401 Retry (ADR-0046):**
   - Создан `docs/0.10.0/ADR-0046-zero-dependency-authorization-spi-and-token-manager.md` (статус **Accepted**).
   - Пакет `ru.jrestly.auth`.
   - `Token`: immutable record со сроками жизни (`expiresAt`, `ttl`) и опережающим `isExpired(refreshBuffer)`.
   - `TokenManager` & `SingleFlightTokenManager`: lock-free single-flight кэширование через `AtomicReference<CompletableFuture<Token>>` (защита от cache stampede / thundering herd при параллельных запросах) и `invalidate()`.
   - Интерцепторы авторизации: `BearerAuth` (RFC 6750), `BasicAuth` (RFC 7617, base64), `ApiKeyAuth` (header/query) с приоритетом `@Order(-1000)`. Автоматический обход для методов с `@Authorization`.
   - Прозрачный 401 Unauthorized Retry: при получении 401 токен инвалидируется, запрос повторяется со свежим токеном 1 раз (в синхронном `exchange()` и асинхронном неблокирующем `exchangeAsync()` через `thenCompose`).
   - Fluent builder: `bearerAuth(...)`, `basicAuth(...)`, `apiKeyHeader(...)`, `apiKeyQuery(...)`, `tokenManager(...)`, `retryOnUnauthorized(...)`.
   - Тесты: 24 новых теста (`TokenTest`, `TokenManagerTest`, `AuthInterceptorsTest`, `AuthorizationWireMockTest`).
2. **Обновлены артефакты памяти и проекта:**
   - `ROADMAP.md`: зафиксирован ADR-0046, следующий свободный — ADR-0047.
   - `DRAFT.md`: активная зона обновлена, кандидат №1 — ADR-0047.
   - `.agent/mailbox.md`: Антону отправлен статус о реализации направления №1 (авторизация).

## Гейты

- `./gradlew test compileJava compileTestJava javadoc` → **965/965** (118 классов, +24 теста), 0 warnings под постоянными флагами `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
- Zero-dep core строго подтвержден: ровно 0 `<dependency>` в POM.

## Контекст для следующей сессии (#127)

### Активная зона DRAFT:
- **Кандидат №1:** Interceptor Chain Short-Circuit SPI (ADR-0047) — синтетический ответ/мок/кэш из интерцептора без сетевого вызова.
- **Кандидат №2:** HttpTemplate инкапсуляция package-private.
- **Кандидат №3:** JMH Benchmarks suite.
- **Кандидат №4:** test-javadoc.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **`AbstractHttpClient` намеренно не `AutoCloseable`**: в тестах регистрировать и закрывать через `.close()` в `@AfterEach`.
- **Path variables в jRestly**: в URL синтаксис `${param}`, в аннотации `@PathVariable(name = "param")` (без `${}`).
- **Аннотации HTTP-методов**: `@Get(path = "...")`, `@Post(path = "...")`, `@RequestBody` (не `@Body`).
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас 965).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать (релизы запаузены per maintainer agreement).
- **Next free ADR number:** ADR-0047.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **965/965** (118 классов).
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
