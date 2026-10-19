# Last Session: #300 (2026-10-19)

## Что сделано

**Сессия #300: свободный аудит кластера `HttpTemplate`/`Redirects` —
найден и закрыт «тихий баг» в failure-ноге sync 401-refresh
(ADR-0036 Amendment #300; гейт 1,658/1,658).**

1. *Аудит (4 кандидата, три закрыты как by design):*
   - `isExpectedStatus`: при `expectStatuses != null` allowances
     `conditional`/`optional` не применяются — **by design**: ADR-0023
     Decision 1 явно даёт `@ExpectStatus` «strict precedence»; пустой список
     отрезан eager-валидацией (ADR-0004 Amendment #235).
   - Sync pre-wire `rateLimiter.acquire()`/`circuitBreaker.acquirePermission()`
     (бросает) vs async `tryAcquire(timeout)`/`tryAcquirePermission()`
     (boolean) — стилистическая асимметрия: дефолты resilience-интерфейсов
     реализованы через те же try-варианты, семантика идентична.
   - Async 401-refresh `attemptsRef`: CFE-порядок верен — `whenComplete`
     (accumulate) регистрируется на `secondPhase` раньше downstream-стадий,
     CFE гонит dependents по порядку регистрации. `attemptAsync` делает
     `attemptsRef.set(attempt)` (set, не increment) — фазовый счёт, cb1
     возвращает суммарный.
2. *Найденный баг:* sync 401-refresh — аккумуляция счёта первой фазы стояла
   ПОСЛЕ `executeWithRetries` (только success-нога). Если refresh-фаза падала
   на проводе (IOException после исчерпания retry / InterruptedException),
   аккумуляция пропускалась и `finally` читал фазовый счёт второй фазы:
   вызов 401 → refresh → connection reset репортил `attempts() == 1`,
   `isRetried() == false`, ломался инвариант Amendment #196 («последнее число
   `onAttemptComplete` = `attempts()» — второй callback номер 2, attempts()
   сказал 1). Async twin уже суммировал (whenComplete на failure тоже
   срабатывает).
3. *TDD:* red-first `ClientMetricsWireMockTest.
   refresh401WireFailureStillSumsBothPhases` (sync, WireMock 401 →
   `Fault.CONNECTION_RESET_BY_PEER` на fresh-токене) — упал ровно на
   `attempts()`: expected 2, was 1. Плюс twin-parity
   `refresh401WireFailureStillSumsBothPhasesAsync` (зелёный сразу — зафиксирован).
4. *Фикс:* `try/finally` вокруг второй `executeWithRetries` в
   `HttpTemplate.exchange()` — аккумуляция на завершении (success OR failure),
   точные `whenComplete`-семантики async-двойника; success-нога
   value-идентична старой; pre-wire/short-circuit/cache-hit не тронуты.
   Комментарий в коде пометил #194/#196/#300.
5. *Документация:* ADR-0036 — `## Amendment #300`.
6. *Гейт:* **1,658/1,658** green across all 5 modules (+2, 0 failures/errors).
   Javadoc в main не тронут (только `//`-комментарий).

## Контекст для следующей сессии (#301)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #300 (см. `git log -1`).
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД (автономный, бэклог):** свободный аудит `HttpTemplateBuilder`-кластера
   (1464 строки — в #300 не читался; `HttpTemplate` в #300 прочитан по
   dispatch/retry/redirect-контуру: exchange L176–327, exchangeAsync L349–543,
   executeWithRetries L777–858, attemptAsync/redirects/retry L890–1107,
   isExpectedStatus L1398–1412, Redirects.java целиком — чист) ИЛИ хвост
   валидации `@ExpectStatus`/`@FollowRedirects` (#207 «фича, не дыра» —
   трогать лишь при тихом исходе). Кандидат №7 (rss/atom) всё ещё
   заблокирован на A/B-слове Антона (parse-only vs fetch-and-parse; ядро vs
   codec-субмодуль — вопрос висит с #284).
4. **Хвосты (не эпики):** `@ExpectStatus`/`@FollowRedirects` (фича, не дыра,
   #207). Path/File-тела метрик — закрыты в #299.
5. **Грабли/паттерны (важно!):**
   - 401-refresh: sync-аккумуляция теперь в `try/finally` вокруг второй
     `executeWithRetries`; async — в `whenComplete` на `secondPhase`. Не
     «распаковывать» обратно (Amendment #300).
   - `knownBodySize` в `WireRequest` — 9-й компонент; 8-аргументный
     конструктор — совместимый, держит. Withers payload-aware.
   - Supplier-rendered тела осознанно остаются -1 (Amendment #193) — не
     «чинить» в попытке узнать размер.
   - Map-префикс читает `rawRequestParamName()` — **осознанно** (ADR-0017
     Amendment #296). Не «чинить» без явного решения о wire-изменении.
   - Плейсхолдеры `{id}`: маска `\{([^}]*)}` в `ControllerValidator`;
     незакрытая `{id` ловится гейтом `URI.create` (#211). Traceparent-`${…}` в
     `Trace.java`/`TraceContext.java` — НЕ трогать.
   - Статическая query в пути: merge-порядок `путь ?…` → `&` → query target →
     `&` → динамика (фикс #295, `createUrl`). Двойной `?` `URI.create` не
     ловит — проверять только e2e (WireMock `urlEqualTo`).
   - Implicit `value` — только один абстрактный метод (грабля #292).
   - `@MultipartFormFile.partName()` — required (нет default'), грабля #209.
   - Исторические ADR со OLD-снапшотами не переписывать (добавлять Amendment —
     ок, паттерн #295/#296/#298/#300).
   - Sчётчики тестов — питоном по `TEST-*.xml` всех модулей (`bc` нет).
     Фильтр тестов `:test --tests 'X'` (ведущий `:` обязателен).
     **Javadoc-гейт отдельный** (`./gradlew javadoc`) — в `test` не входит;
     если трогал javadoc в `src/main` — прогонять явно.
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен.
   - XML-отчёты атрибутируют упавшие тесты на предшествующие self-closing
     testcase'ы (наблюдение #294).
   - Из извлечённого коллаборатора — свой сейт до 0 missed (урок #271) и
     private no-arg-ctor (грабля #283).
   - Часовой пояс контейнера +04 (логи log4j — UTC); счётчик сессий: **300**
     (не докручивать повторно — он уже докручен в этом коммите).
