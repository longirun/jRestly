# Last Session: #351 (2026-10-20)

## Что сделано

**Сессия #351: декомпозиция `HttpTemplate`: вынесен `RetryExecutor` (ADR-0091). Исторический рубеж >2,000 тестов взят (2,016/2,016)! Missed branches впервые в истории библиотеки < 300 (296)! Новые исторические рекорды: Branch 93.23%, Complexity 92.03%! `HttpTemplate` сокращен на 262 строки и 60 complexity!**

1. *Вход:* HEAD `b0bba26` (#350), чистое дерево (счётчик 351), mailbox пуст с #341.
2. *Декомпозиция `HttpTemplate`:* вынесен весь срез retry execution:
   - `executeWithRetries`: синхронный цикл повторов с классификацией статусов, IOException retryTransport-гейтами, fail-fast InterruptedException, закрытием промежуточных стримов, вычислением backoff и задержкой;
   - `executeAsyncWithRetries`, `attemptAsync`, `scheduleAsyncRetry`: асинхронный пайплайн повторов с неблокирующим планированием через `CompletableFuture.delayedExecutor`, unwrap стадий, zero-delay fast path;
   - `probePayloadDecode`: side-effect-free payload decode probe (ADR-0011) для повторов при ошибках декодирования;
   - `isRetryableStatus`, `isNonIdempotent`: статусная классификация, соблюдение терминального контракта `@OnError` и флага `retryOnErrorStatuses`, список `retryableStatuses`, правила идемпотентности (POST/PATCH);
   - Экспоненциальный backoff с jitter и `Retry-After` заголовком;
   - Per-attempt метрики (`MetricsNotifier.attemptComplete`, `MetricsNotifier.retry`).
3. *Форма:* package-private `final class RetryExecutor` с record `RetryConfig` (соответствие правилу C8 ≤ 7 параметров). Независим от редиректов — принимает чистые функциональные интерфейсы `SyncSender` и `AsyncSender` (C9 green: zero package cycles, loose coupling).
4. *Шов:* в `HttpTemplate` экземпляр строится лениво в `retryExecutor()`. Методы `executeWithRetries`, `executeAsyncWithRetries`, `probePayloadDecode` и `isRetryableStatus` делегируют в `retryExecutor()`, сохраняя 100% обратную совместимость.
5. *TDD red-first:* `RetryExecutorTest` написан до реализации (compile-red), 27 исчерпывающих тестов.
6. *Сьют коллаборатора:* **124/124 branches (100%), 81/81 complexity (100%), 152/152 lines (100%), 885/885 instructions (100%), 0 missed branches (планка C11 ADR-0081)**.
7. *Замеры ADR-0081:*
   - `HttpTemplate` **1,976 → 1,714** строк (−262 строки!) / WMC **364 → 304** (−60 complexity!) / missed branches **63 → 54** (−9 missed);
   - `RetryExecutor` — 339 строк / 78 WMC / **0 missed branches**;
   - Missed branches библиотеки: 308 → **296** (−12 missed branches за сессию — **впервые в истории библиотеки < 300!**);
   - Branch coverage: 92.93% → **93.23%** (4,079/4,375 covered — **новый исторический абсолютный рекорд**);
   - Complexity coverage: 91.69% → **92.03%** (3,420/3,716 covered — **новый исторический рекорд > 92%**);
   - Инструкции: **98.53%** (31,004/31,465); Линии: **98.25%** (6,813/6,934); Классы: **100%** (161/161); Методы: 99.87% (1,481/1,483).
8. *ADR-0091* (`docs/0.13.0/ADR-0091-retry-executor-extraction.md`, Accepted) + навигационная карта ADR-0081 обновлена + `ru.jrestly.http/package-info` и `ROADMAP.md` актуализированы. Highest ADR **0091**, next free **0092**.
9. *Гейт:* полный **2,016/2,016** (root **1,852** = 1,825 + 27; субмодули 164), 0 failures/errors; `:javadoc` чисто во всех 8 модулях под `-Werror`. Рубеж в 2,000 тестов взят!

## Контекст для следующей сессии (#352)

1. **Ветка** `agent-loop`, HEAD — коммит #351.
2. **Приоритет:**
   - Дальнейшая декомпозиция оставшегося красного god-класса `HttpTemplate` (1,714 строк, WMC 304): вынос `RedirectHandler` / `RedirectExecutor` (циклы редиректов), либо `CacheDispatchPipeline` (интеграция кэша в exchange/exchangeAsync).
3. *Окружение:* `GRADLE_USER_HOME=/tmp/opencode/gradle-home`; полный гейт `./gradlew test javadoc` ~1–2 мин. JaCoCo — `build/reports/jacoco/test/jacocoTestReport.xml`; тесты — `**/build/test-results/**/TEST-*.xml`.
4. *Метрики:* гейт **2,016/2,016**; JaCoCo Branch **93.23%** (296 missed — абсолютный минимум < 300) / Complexity **92.03%** (рекорд > 92%); `HttpTemplate` **1,714** строк; next free ADR **0092**.
