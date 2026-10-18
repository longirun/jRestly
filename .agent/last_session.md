# Last Session: #274 (2026-10-18)

## Что сделано

**Сессия #274: ADR-0066, шаг №3 — resolver Problem Details вынесен из
`HttpTemplate` в package-private `ru.jrestly.http.ProblemDetailResolver`;
завершение WIP прерванных #272–#273 + находка «вынос из `try` меняет контракт».
Гейт 1,578/1,578, METHOD 1437/1437 и CLASS 146/146 — 100%. Публичный API не
изменён.**

1. *Вход:* WIP прерванных сессий #272–#273 (паттерн #155/#186/#254/#261): в
   дереве уже лежал новый `ProblemDetailResolver.java` + правки
   `HttpTemplate.java` и `HttpTemplateProblemDetailResolutionTest.java`, но
   коммита не было. HEAD — `8adfd99` (#271). Счётчик сессий gradle-триггером
   доведён до 274 (`.agent/session_counter.txt` изменён в дереве — коммитить
   вместе).
2. *Рефактор — `ProblemDetailResolver` (чистый `static`-хелпер):*
   - Из `HttpTemplate` извлечены `resolveProblemDetail` (→ `resolve`),
     `isProblemJsonContentType`, `isLikelyProblemDetail`,
     `instantiateThrowable` (`private static` → package-private `static`);
     логика байт-в-байт.
   - В отличие от `Redirects` (#271) — **не value-объект**: не завязан на поля
     шаблона, поэтому конструктор/поля не нужны; выбранный кодек и его
     media-тип передаются аргументами (`resolve(status, rawBody, headers,
     contentType, codec)`).
   - `handleOnError` **остаётся** на `HttpTemplate` (это dispatch, а не
     резолвинг) — тесты `@OnError` по-прежнему гоняются через шаблон.
3. *Находка (главная, при завершении WIP):* перенос вытащил
   `responseCodec(responseHeaders)` **из охранного `try`** резолвера наружу
   (аргумент вычисляется до вызова). На bare-template (без
   `codecRegistry`/`jsonCodec`) `createUnexpectedStatusException` начинал падать
   голым `NullPointerException: jsonCodec` вместо прежнего тихого пролёта к
   встроенному `ProblemDetail.fromJson`. Поймали существующие гварды
   `HttpTemplateOptionalStreamTest` / `HttpTemplateStreamWireTest` (4 падения,
   выглядящие «сетевыми», а на деле unit-путь unexpected-status). Фикс — новый
   private `responseCodecOrNull` (best-effort lookup, `catch (Throwable) → null`).
4. *TDD:* `HttpTemplateProblemDetailResolutionTest` (30 тестов) — предикаты и
   резолвер напрямую на `ProblemDetailResolver`; новый тест
   `nullCodecFallsBackToBuiltInParser` (закрывает обе стороны
   `if (responseCodec != null)`). Оставшиеся 5 missed ветвей резолвера —
   известные недостижимые defensive в `instantiateThrowable`.
5. *Документация:* ADR-0066 → `## Amendment #274` (шаг №3 сделан + находка);
   ROADMAP «Current status» и секция Cycle 0.11.0 обновлены; DRAFT — запись
   сессии + кандидат №5 (шаг №3 сделан, следующий №4).
6. *Верификация:* `./gradlew test javadoc jacocoTestReport` — **1578/1578**
   зелёные across all 5 modules (core 1498 + engine-okhttp 25 + engine-apache5 25
   + codec-jackson-xml 15 + benchmarks 15, 0 failures/errors), javadoc чист под
   `-Werror`. JaCoCo: **METHOD 1437/1437** и **CLASS 146/146 (100%)**,
   **INSTRUCTION 30,702/31,435 (97.67%)**, **LINE 6,677/6,857 (97.38%)**,
   **BRANCH 3,948/4,447 (88.78%)**, **COMPLEXITY 3,203/3,693 (86.73%)**.
   `HttpTemplate`: 128 → **122** missed; `ProblemDetailResolver`: 5 missed
   (недостижимые).

## Контекст для следующей сессии (#275)

1. **Ветка** `agent-loop`; HEAD — коммит этой сессии (#274).
2. **Highest ADR:** **0066** (`docs/0.11.0/`), next free **0067**.
3. **Состояние проекта:** 100.00% Class (146/146), 100.00% Method (1437/1437),
   97.67% Instruction, 97.38% Line, 88.78% Branch, 86.73% Complexity.
   1578/1578 тестов зелёные across all 5 modules. Javadoc чист под `-Werror`.
4. **Активная зона:** открыт **Кандидат №5 — декомпозиция `HttpTemplate`
   (ADR-0066)**. Сделаны шаги №1 (`RetryAfter`, #270), №2 (`Redirects`, #271),
   №3 (`ProblemDetailResolver`, #274). Следующий шаг **№4 — dispatch кэша**
   (`handleCacheResponse`, `toStreamWireResponse`). Параллельные варианты:
   хвосты покрытия (`HttpTemplate` 122 missed, `HttpTemplateBuilder` 120,
   `MethodMetadata` 27, `ZeroDepJsonParser` 24, `JdkHttpEngine` 16,
   `ServerSentEventReconnector` 12).
5. **Шаг №4 (подробно):** `handleCacheResponse` уже package-private шов
   (паттерн #255) — покрыт `HttpTemplateCacheDispatchTest` (11 тестов,
   `RecordingCache`); при переносе в коллаборатор (напр. `CacheDispatch`)
   обновить ссылки в этом сьюте. `handleCacheResponse` завязан на поле
   `httpCache` (ленивый коллаборатор с ним в конструкторе?), `toStreamWireResponse`
   — на кодирование тела. Проверить, не читает ли метод ещё какие-то поля
   шаблона (как `Redirects` читал три) — тогда value-объект, иначе `static`.
6. **Грабли/паттерны (важно!):**
   - **Вынос метода проверять по границам `try`/`catch`, а не только по телу:**
     в этой сессии дословно перенесённый `resolve` изменил контракт, потому что
     `responseCodec(...)` оказался снаружи охранного `try` → `NPE: jsonCodec`
     на bare-template. Всегда сверять, что вычисление аргументов на call-site не
     выбрасывает там, где раньше ошибка проглатывалась.
   - Извлечение коллаборатора сдвигает недостижимые defensive-ветви в новый
     класс (рекорд BRANCH дрожит): нужен прямой unit-сьют на шов (урок #271),
     а не только переиспользование старых тестов.
   - Ленивый коллаборатор (`private X x; private X x()`) безопасен:
     `HttpTemplate` создаётся на каждый вызов.
   - JaCoCo XML (`build/reports/jacoco/test/jacocoTestReport.xml`): счётчики
     класса — `<counter>` внутри `<class>`; **пропущенные ветви по строкам — в
     `<sourcefile>/<line mb= cb=>`**, не в `<class>/<line>`.
   - Считать тесты надёжнее питоном/awk, `bc` в контейнере нет.
   - Счётчик сессий: 274. Часовой пояс контейнера +04.
7. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   фильтр `:test --tests 'X'` (без ведущего `:` Gradle заденет субмодули).
