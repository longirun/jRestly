# Last Session: #213 (2026-10-17)

## Что сделано

**Free-аудит/проектное решение: `0`-таймаут был «принимаемым, но невыразимым»
на JDK-движке — 23-я находка серии, закрытие non-goal #200.**

1. *Ход:* свободный выбор из контекста #212 (активная зона пуста, highest ADR
   **0060**). Взял оставшийся хвост #200: `connectTimeout(0)` /
   `socketTimeout(0)` валидны по ADR-0027 §3 («non-negative»), но JDK-движок
   отвергает `Duration.ZERO` — `HttpClient.Builder.connectTimeout` **на
   создании клиента**, `HttpRequest.Builder.timeout` **на первом запросе** —
   голым `IllegalArgumentException: Invalid duration: PT0S`. OkHttp/Apache5 при
   этом читают `0` как «без таймаута» → одна конфигурация ведёт себя
   по-разному, на дефолтном движке падает поздно и непонятно.
2. *Класс находки:* тот же «движко-/платформозависимый» класс, что #184/#199/
   #200, но грань **новая**: не пробел валидации входа, а **недостающая
   семантика легального значения**. Легальный вход, чья семантика задана только
   реализацией транспорта.
3. *Решение (автономия Антона):* **`0` = «без таймаута» на всех движках.**
   Backward-compatible чтение (OkHttp/Apache и так так читали); отказ от нуля
   сломал бы существующих пользователей. Формально закреплено в ADR-0027.
4. *Fix:*
   - `HttpTemplate.effectiveSocketTimeout(Duration methodTimeout, ModuleInfo)`
     — единый источник правды для дедлайна запроса: `@Timeout` всегда
     побеждает; иначе `getSocketTimeout() == 0` → `null` в `HttpEngineOptions`
     (движки не ставят свой дедлайн; OkHttp/Apache5 и так гейтили `> 0`).
     Вызывается из `HttpTemplateBuilder` и из fallback-веток
     `HttpTemplate.send`/`sendAsync`.
   - `JdkHttpEngine.create` применяет `connectTimeout` только при `> 0`
     (дефолт JDK безграничный).
   - Негативные значения по-прежнему отбиваются билдерами `SimpleModuleInfo`/
     `JRestlyClient` (#200); `@Timeout` остаётся строго положительным.
   - Javadoc: `ModuleInfo`, `SimpleModuleInfo.Builder` (int+Duration пары),
     `JRestlyClient.Builder`, `HttpEngineOptions.timeout` — `0` = no timeout.
5. *TDD red-first:* 2 e2e-теста в `TimeoutWireMockTest` (новый интерфейс
   `ZeroTimeoutApi` + хелпер `stubZeroTimeoutEndpoint` с задержкой 100 мс):
   `zeroConnectTimeoutDisablesConnectTimeout` (создание клиента + вызов) и
   `zeroSocketTimeoutDisablesRequestTimeout` (запрос с `socketTimeout(0)`).
   Оба красные на до-фикс коде ровно по `Invalid duration: PT0S`, зелёные после.
6. *Документы:* ADR-0027 → `## Amendment #213`; `DRAFT.md` (блок-запись #213 +
   хвост активной зоны); дневник `.agent/days/2026-10-17.md` (сессия #213 +
   итог дня обновлён до 1241); mailbox (короткое уведомление); `last_session.md`.
7. *Верификация:* `./gradlew test javadoc` — BUILD SUCCESSFUL; счётчик из
   `build/test-results/**/TEST-*.xml` = ровно **1241/1241** (core 1161 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
   0 failures/errors, javadoc-гейт (`-Werror`) чист.

## Технические заметки

- JDK-проверка (зонд): `HttpClient.Builder.connectTimeout(Duration.ZERO)` →
  `Invalid duration: PT0S`; `HttpRequest.Builder.timeout(Duration.ZERO)` → то же.
  Оба требуют строго положительное значение.
- `engine-okhttp` принимает 0 в `connectTimeout/readTimeout/writeTimeout`
  (OkHttp `checkDuration` пропускает 0 = без таймаута);
  `engine-apache5` гейтит `if (socketTimeout > 0)` — оба согласны с новой
  семантикой без правок.
- `HttpTemplateBuilder` создаёт `HttpEngineOptions` **всегда** (не-null) с
  `effectiveTimeout`; `HttpTemplate.send/sendAsync` fallback на
  `effectiveSocketTimeout` — только для тестов, конструирующих шаблон напрямую.
- Прогон одного класса: `./gradlew :test --tests 'ru.jrestly.TimeoutWireMockTest'`
  (обязательно с `:` — иначе фильтр улетает в `:benchmarks:test`).
- Счётчик тестов: python по `**/build/test-results/test/TEST-*.xml`.
  `TimeoutWireMockTest.xml` → «tests»=8 (было 6).
- `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.

## Контекст для следующей сессии (#214)

1. **Ветка** `agent-loop`; HEAD — коммит этой сессии (родитель — #212).
2. **Активная зона пуста.** Highest ADR **0060**, next free **0061**.
   Варианты хода:
   (a) **Free-аудит по подклассу #213 «легальное значение без семантики»**:
      поискать конвенции `0`/`-1`/пустых коллекций, чья семантика задана
      только конкретным транспортом/реализацией (кандидаты: `-1`-конвенции
      метрик/прогресса, пустые `@ExpectStatus`/`@FollowRedirects` — решение
      #207 «фича», пересматривать только при реально тихом исходе).
   (b) **Продолжить ось #212 «декларация ⇄ окружение сборки»** — другие
      флаги/runtime-условия, от которых зависит корректность декларации.
   (c) **Free-аудит остатков:** Path/File-тела метрик (-1 вместо размера;
      engine-reported byte accounting).
   (d) **Концепт ADR-0061+** — HTTP-кэш (RFC 9111) ждёт решения Антона
      (вопрос в mailbox от #208, ответа пока нет) — не трогать без его слова.
   (e) **fixtures-javadoc** — 15 вспомогательных типов `ru.jrestly.fixtures`.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 213. Часовой пояс контейнера +04 (календарный день
   2026-10-17).
