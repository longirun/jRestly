# Handover Note: Сессия 23 (концепт ADR-0009 — scope решения «0.x polish»; DRAFT шаг 1 закрыт)

## Что сделано в этой сессии
- **ADR-0009** (`docs/0.7.0/ADR-0009-0x-polish-carry-over-quirks-and-accepted-known-gaps.md`)
  — concept-шаг эпика 0.x polish (DRAFT-шаг 1 `[x]`). Scope-решение по
  четырём кандидатам, каждый **проверен по коду на этой сессии** (не
  скопирован с ROADMAP):
  1. **PUT-лог — ЧИНИМ.** `HttpTemplate.logRequest` (line 536) гейтит
     тело на `Arrays.asList(POST, PATCH, DELETE)` — PUT выпадает
     полностью (включая "No request body"). Фикс: PUT в список +
     `Arrays.asList` (аллокация НА КАЖДОМ запросе) → `private static
     final Set<HttpMethod> BODY_LOGGING_METHODS`. GET/HEAD осознанно
     вне (RFC 9110: GET-body легален, семантика undefined).
     Бонус-факт из проверки: `logRequest` вызывается ОДИН раз на
     `exchange()` ДО retry-цикла — ретраи не дублируют request-лог.
  2. **`void.class` — ЧИНИМ** одной строкой в `processResponse`
     (line 165: `|| void.class.equals(returnType)`). Бонус-факты:
     `resolveReturnType` (HttpTemplateBuilder) прокидывает примитивный
     `void` как есть; маскирует early-return на пустом payload (line
     154); единственный существующий тест — `void` + ПУСТОЕ тело
     (GetMethodTest.getReturnsVoid) = ровно маскер; `returnType`
     потребляется только тремя ветками `processResponse` (поэтому фикс
     локальный, нормализация в builder отвергнута). Единственное
     настоящее behavior-изменение эпика: `void` + непустое тело →
     семантика `Void`-формы (null, тело отброшено) вместо
     codec-исключения.
  3. **`Retry-After` HTTP-date — ЧИНИМ.** `DateTimeFormatter.
     RFC_1123_DATE_TIME` (IMF-fixdate + rfc1123), wait = `max(0,
     date − now)` (floor 0 = «уже можно», зеркалит integer-форму).
     Форм-решение: шов `retryAfterSeconds` → `retryAfterDelayMillis`
     (Long, **миллисекунды** — естественная единица wait'а, не
     Duration — без wrapper-аллокаций; body `nextDelayMillis` не
     меняется, только имя/единица/Javadoc; package-private → ABI
     цела). asctime (obsolete, без zone) — НЕ принимаем → новый
     микро-gap.
  4. **payload-decode retry — ОСТАВЛЯЮ gap** (reaffirmed, не
     reopened): транзентные случаи = IOException = уже транспортный
     ретрай; ретрай декода сталкивается с «classified exactly once»
     (нужно отличать успешный HandledException от сбоя декода — новое
     состояние в цикле); 0.x-дисциплина (типизированное
     codec-исключение).
- **DRAFT:** шаг 1 `[x]`, шаг 3 раздвизен («HTTP-date — решение
  ADR-0009; payload-decode остаётся gap'ом»).
- **Кода нет** — sanity `./gradlew test --rerun`: **219/219** (0
  failures/0 errors по XML, 16 с, тёплый кэш); POM-гейт не трогал
  (build.gradle не менялся, 0 `<dependency>`, 0.6.2).
- **Mailbox:** заметка #23 (scope + ключевые форм-решения — миллисекунды
  в шве, asctime-микро-gap, public `setLogger` как готовый шов для
  log-capture-тестов; прецедента logger-capture в тест-сьюте НЕТ).
  Гигиена: удалено #20 (realized) — осталось #21, #22, #23 + правила
  Антона.

## Контекст для следующей сессии (#24)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом.** (Mailbox тиш после моего #23 ~06:43.)

### Что логично делать дальше
1. **DRAFT-шаг 2 — фиксы 0.5.0-квирков (по ADR-0009):** (a)
   `logRequest`: PUT + `Set.of` static final; (b) `processResponse`:
   `|| void.class.equals(returnType)`. Тесты по ADR: (a) log-capture
   через PUBLIC `HttpTemplate.setLogger` (новый шов для тестов,
   прецедента нет) — unit-тесты `logRequest` напрямую (PUT body /
   no body / multipart, GET с body — scope-pin) + одна интеграция
   (PUT end-to-end); (b) TDD-репродуктор ПЕРВЫМ: новый `void`-метод
   в фикстуре (или расширение `RetryController`/`TestController` —
   `TestController` плотный, dedicated-фикстура в духе
   `RetryController` — прецедент) + 200 с непустым телом → red с
   реальным классом/сообщением codec-исключения (НЕ гадать), потом
   одна строка, green; симметрия: `CompletableFuture<Void>` + непустое
   тело → null (async twin), `Void`-форма + непустое тело → null
   (если не закреплён).
2. **DRAFT-шаг 3 — HTTP-date `Retry-After`** (самый объёмный шаг
   эпика): парсер в `retryAfterDelayMillis` (RFC 1123 + integer +
   null-fallback), шов `nextDelayMillis` в миллисекундах (Javadoc +
   `RetryPolicyTest`-обновление), README-строка
   («integer-seconds `Retry-After`…» → «`Retry-After` (delta-seconds
   or HTTP-date)…»), ADR-0008 Known gaps правка (HTTP-date закрыт,
   payload-decode reaffirmed с указателем, +asctime), юниты парсера
   + одна wall-clock-интеграция (IMF-fixdate ~2 s в будущем, elapsed
   ≥ 2 s — прецедент integer-теста).
3. **DRAFT-шаг 4 — финальный gate:** тесты, POM, дистилляция в
   ROADMAP (carry-over-строка 0.5.0 — зачёркивание/удаление с
   указателем на ADR-0009), зачистка DRAFT.

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт vs реальность** (серия из 5+ раз): проверять по
  jar/тегам/исходникам/коду на шаге, править ADR обратно.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **Чужие API — только по jar/javap/исходникам; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function
  .identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml`
  (0 `<dependency>`).
- **Подсчёт тестов:** python3/bc НЕ пережили ребут — awk/grep-цикл по
  XML-заголовкам (tests=/failures=/errors= из первых строк
  `build/test-results/test/*.xml`).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat`
  mtime, `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc; dedicated-фикстура на новую
  аннотацию/поведение — RetryController/HttpVersionController).
- **Аллокации на hot path** — static constants, не per-call списки
  (именно поэтому `Arrays.asList` в logRequest — находка ADR-0009).
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся
  (ребут), первый прогон дня качает зависимости заново (~50 с).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 23 → после этой 24).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — Gson/Moshi, ADR-0008 — retry, **ADR-0009 — 0.x polish,
   готов**).

### Состояние git
- HEAD: коммит сессии #23 (ADR-0009 + DRAFT шаг 1 + память) поверх
  401bde7 (сессия #22).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за
  Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **219/219**.
