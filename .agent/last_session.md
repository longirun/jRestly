# Last Session: #327 (2026-10-19)

## Что сделано

**Сессия #327: Zero-Allocation URI Composition & RFC 3986 Fast-Paths (ADR-0072, Accepted), single-pass createUri/appendQuery, UriEncoder, cached placeholderToken, гейт 1,747/1,747 (+8), рекорд JaCoCo 90.65%.**

1. *Приём состояния:* HEAD на #326, чистое дерево. Аудит Pattern Matching и Sealed Architecture зафиксирован.
2. *Архитектурный анализ и оформление ADR-0072 (`docs/0.12.0/ADR-0072-zero-allocation-uri-composition-and-fast-paths.md`, Accepted):*
   - **Ликвидация промежуточных аллокаций при композиции URI:**
     * Ранее при сборке запросов с query-параметрами создавались до 10–15 временных строковых объектов (`StringBuilder query`, `query.toString()`, `url.substring(0, hashIndex)`, `url.substring(hashIndex)`, конкатенация `base + separator + query + fragment`), а также выполнялся `URLEncoder.encode(...).replace("+", "%20")` даже для чистых чисел и безопасных ASCII-идентификаторов.
     * `HttpTemplate.createUri` переведен на **single-pass** сборку: один `StringBuilder` нужной емкости, базовый путь и фрагменты вставляются через `CharSequence.append(url, start, end)` без создания подстрок, параметры пишутся напрямую в целевой буфер.
     * `HttpTemplate.appendQuery` оптимизирован: слайсинг без промежуточных `substring`.
   - **Fast-Path для RFC 3986 Unreserved Characters (`UriEncoder`):**
     * Введен package-private класс `ru.jrestly.http.UriEncoder` с быстрым сканированием unreserved символов `[a-zA-Z0-9_.*-]`.
     * Доказан инвариант: для любой unreserved строки `URLEncoder.encode(s, UTF_8).replace("+", "%20") == s`.
     * `UriEncoder.encode(s)` для unreserved строк возвращает саму исходную строку (0 аллокаций!).
     * `UriEncoder.encodeTo(s, sb)` пишет строку напрямую в `StringBuilder` без создания промежуточных строк.
     * Для зарезервированных символов (пробелы, non-ASCII, `/`, `?`, `#`, `&`, `=`, `+`) сохранен безопасный fallback на `URLEncoder.encode` с заменой `+` на `%20`, гарантируя 100% паритет контракта.
   - **Предвычисление `placeholderToken` в метаданных:**
     * `MethodMetadata.ParameterDescriptor` теперь precomputes и кэширует `placeholderToken` (e.g. `"{id}"`) при парсинге метода, устраняя конкатенацию `"{" + name + "}"` на каждом HTTP-вызове в `HttpTemplateBuilder.createUrl`.
     * Удален неиспользуемый приватный метод `encode` в `HttpTemplate`.
3. *Тесты (+8 тестов, гейт 1,747/1,747):*
   - `UriEncoderTest`: 8 исчерпывающих тестов (unreserved matching, reserved rejection, encode null/empty, unreserved identity instance, URLEncoder parity на спецсимволах/пробелах/юникоде, encodeTo, appendQuery инварианты, single-pass createUri со сложными путями, существующими query, fragments и fragment-'?').
   - `MethodMetadataTest`: проверка кэширования `placeholderToken`.
4. *Гейты:* **1,747 / 1,747** green во всех 6 модулях (root 1619 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + benchmarks 37). Javadoc чист под `-Werror`. JaCoCo ядра: BRANCH **90.65%** (4,051/4,469 — новый исторический рекорд!), INSTRUCTION **98.14%**, LINE **97.78%**, COMPLEXITY **88.90%**, METHOD **100% (1458/1458)**, CLASS **100% (150/150)**.

## Контекст для следующей сессии (#328)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0072** (`docs/0.12.0/ADR-0072-zero-allocation-uri-composition-and-fast-paths.md`), next free **0073**.
3. **Кандидаты на сессию #328:**
   - **Кандидат №12 — Экосистема кодеков: исследование субмодуля Proto / YAML (`codec-protobuf` / `codec-yaml`):** легковесный адаптер для Protocol Buffers или YAML на базе `PayloadCodec` SPI.
   - **Кандидат №14 — Дальнейшая оптимизация аллокаций HTTP заголовков и W3C Tracing context:** исследование устранения промежуточных `ArrayList`/`HashMap` при парсинге и мердже статических и динамических заголовков.
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
5. Метрики — гейт **1,747/1,747**; BRANCH **90.65%**, INSTRUCTION **98.14%**, LINE **97.78%**, COMPLEXITY **88.90%**, METHOD **100%**, CLASS **100%**.
