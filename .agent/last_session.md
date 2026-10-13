# Handover Note: Сессия 33 (payload-decode шаг 3: sync-wiring + интеграции ADR-0011 Aspect 7 сценарии 1–5; 250→255/255, DRAFT — активный эпик, 3/5)

## Что сделано в этой сессии
- **Входящее от Антона (два новых, оба обработаны).** (1) «Странные имена
  у коммитов в гитхабе — по идее они должны быть краткими, а они
  дублируют ченжлог» → правило с этого коммита: **короткий subject, без
  дублирования ченджлога** (коммит #33 — первый в новом стиле).
  (2) «поддерживай mailbox в чистом состоянии» — повтор постоянных
  правил, гигиена сделана (удалён realized #31; остались правила Антона +
  нитка про теги + нитка про коммиты + #32 + #33).
- **Код (sync-wiring — «кто зовёт шов»).**
  - `HttpTemplate.executeWithRetries`: decode-ось — ответ, который
    status-логика приняла (`!isRetryableStatus`), всё ещё не финален при
    `!last && retryPolicy.isRetryDecodeFailures()`: `probePayloadDecode(
    response)`; codec-исключение (catch RuntimeException — codec-исключения
    unchecked по контракту `JsonCodec`) → `scheduleRetry(attempt,
    maxAttempts, "decode (" + e.getClass().getSimpleName() + ")", response)`
    + `continue`. При `last` probe не вызывается — финальный
    `processResponse` бросит codec-исключение сам (Aspect 3). DEBUG-строка
    — в точности формат ADR Aspect 3 (`Attempt 1/2 failed: decode
    (UncheckedIOException) — retrying in … ms`), `scheduleRetry` уже
    держит формулировку, failure-тип передаётся аргументом.
  - `HttpTemplateBuilder.resolveRetryPolicy()`: строка
    `.retryDecodeFailures(retry.retryDecodeFailures())` — поверхность
    `@Retry` wired (между шагами атрибут был dead code — прецедент
    retry-эпика закрыт).
- **Фикстуры.** `TestAuthProvider` — счётчик `captureCount` (override
  `captureAuthDetails` + delegate в super) — «exactly once» проверяется
  только счётчиком: replace-семантика `updateAuthHeader` хранит ОДИН
  заголовок (последний победил), двойной вызов дал бы тот же list-size 1.
  `RetryController.getDecodeAuth()` — `@Retry(times=2, initialBackoffMs=10,
  maxBackoffMs=50, retryDecodeFailures=true)` + `@SetAuthDetails(
  headerName="access-token")`, GET `/api/decode-auth`.
- **Тесты (5 новых интеграций, `RetryDecodeTest` в `ru.jrestly`,
  ADR-0011 Aspect 7 сценарии 1–5; зелёные со второго прогона — первый
  красный был finding, см. ниже).** (1) 200 `not-json` → 200 норма,
  maxAttempts=2, flag on → успех, 2 запроса; (2) exhaustion: 200-мусор
  forever → финальное `UncheckedIOException` (codec-своё, cause != null —
  «типизированное, то же, что без retry», без нового класса), 2 запроса;
  (3) default off: retry для status включён, `retryDecodeFailures` off → 1
  запрос (zero-change на success-path); (4) `@OnError(statuses={429})` +
  malformed error-body, `retryOnErrorStatuses` off → 1 запрос
  (terminal-контракт, probe true для declared status); (5) «classified
  exactly once» — через аннотацию (`retryController().getDecodeAuth()`,
  client-level off): 200-мусор→200-норма (2 запроса), auth-capture ровно
  ОДИН раз (счётчик), захваченный токен = `token-final` (consumer ходил на
  финале). Бонус: сценарий 5 заодно закрепляет атрибут `@Retry.
  retryDecodeFailures` end-to-end (не dead code).
- **Первый красный — finding (серия «ожидание vs реальность», 10-й раз).**
  Сценарий 4 ждал `HandledException`, прилетело `UncheckedIOException` из
  `JacksonCodec.deserialize` в `@OnError`-ветке `processResponse`
  (строка 179): malformed error-body бросает codec-исключение **до**
  построения `HandledException`. Ровно первая строка Known gaps ADR-0011
  («An @OnError status whose error body fails to decode is *not*
  decode-retried … documented, non-blocking»); ADR-сценарий 4 обещал
  только «1 request». Пин переписан на реальность (UncheckedIOException +
  1 запрос + комментарий-указатель на known gap); production не тронут
  (behavior — осознанный контракт @OnError: тело ошибки декодируется,
  кривое тело → codec-исключение, decode-axis в этом не участвует).
- **Гейты.** RED (1 падение — сценарий 4) → `./gradlew test --rerun` —
  **255/255** (250→255, +5; 0 failures/0 errors по XML, 36 классов, 19–21 s
  тёплый кэш); POM `pom-default.xml` — **0 `<dependency>`**, 0.6.2.
- **Память.** DRAFT шаг 3 `[x]` (+ finding в пометке). Mailbox #33 +
  гигиена. Дневник #33 + «Итог дня» обновлён (живой).

## Контекст для следующей сессии (#34)

### Эпик (DRAFT): payload-decode retry (ADR-0011) — шаг 4
1. [x] Шаг 1 — концепт ADR-0011 (#31).
2. [x] Шаг 2 — surface + шов + юниты (#32).
3. [x] Шаг 3 — sync-wiring + интеграции 1–5 (#33).
4. **[ ] Шаг 4 — async-твин (`attemptAsync`: decode-ось через
   `delayedExecutor`) + интеграция 6.** Маленький по объёму:
   - `HttpTemplate.attemptAsync` (чтение на входе — форма зафиксирована
     ниже): в `handle`-лямбде ветка `if (last || !isRetryableStatus(
     response)) { return completedFuture(response); }` получает mirror
     sync-оси:
     ```
     if (last || !isRetryableStatus(response)) {
         if (!last && retryPolicy.isRetryDecodeFailures()) {
             try {
                 probePayloadDecode(response);
             } catch (RuntimeException e) {
                 return scheduleAsyncRetry(request, attempt, maxAttempts,
                         "decode (" + e.getClass().getSimpleName() + ")", response, hopsLeft);
             }
         }
         return CompletableFuture.<WireResponse>completedFuture(response);
     }
     ```
     Probe — чистый синхронный предикат → обычный try/catch внутри
     lambda, никаких новых CompletionStage-веток. `scheduleAsyncRetry`
     уже принимает failure-строку (тот же DEBUG-формат). Комментарий
     класса `executeAsyncWithRetries`/`attemptAsync` — дописать decode-
     ось в 1–2 строки (как сделано в sync-комменте #33).
   - Интеграция 6 (ADR-0011 Aspect 7): async 200-мусор→200-норма,
     `CompletableFuture`-метод → `join()` вернул значение, 2 запроса.
     **Новая фикстура не нужна:** `TestController.getItemAsync(1L)`
     уже существует (GET `/api/items/1`, CompletableFuture<TestDto>);
     client-level policy через `with(...)` (helper в стиле RetryTest):
     `with(smallBackoff().maxAttempts(2).retryDecodeFailures(true).
     build()).getItemAsync(1L).join()`.
   - Exhaustion-typing в async НЕ пиновый для этого шага:
     `exchangeAsync`'s mapping не тронут (ADR Aspect 6: «untouched») —
     codec-исхождение на `thenApply` → `exceptionally` →
     `RuntimeException(cause)`, ровно как и без retry; typing закреплён
     sync-интеграцией 2.
5. [ ] Шаг 5 — Javadoc-контракт (`RetryPolicy`/`@Retry`/`ModuleInfo`,
   sync/async uniformity — decode-ось) + README «Retry with backoff»
   (decode-параграф) + **финальный gate + дистилляция в ROADMAP** (ADR-0008
   Known gaps payload-decode-строка зачёркнута с указателем на ADR-0011,
   ADR-0009 кандидат 4 «LEAVE» аннотирован «reopened by ADR-0011»).

### Грабли этой и прошлых сессий (актуальный набор)
- **Коммиты — КРАТКИЙ subject, без дублирования ченджлога** (Антон,
  2026-10-13, нитка в mailbox): с #33 новый стиль (пример: «feat+test:
  payload-decode retry step 3 — sync wiring + ADR-0011 integrations
  1-5»). Длинное тело не нужно — ченжлог и так в DRAFT/ROADMAP/дневниках.
- **Replace-семантика скрывает двойной вызов** (#33): «consumer вызван
  один раз» — только счётчиком (TestAuthProvider.captureCount);
  list-size/last-value не пины.
- **Known gap ADR — проверяемое утверждение** (#33): тип исключения
  сценария 4 выводил из «terminal-контракта», не проверив, что
  @OnError-ветка декодит тело ДО HandledException. Свои ожидания —
  сверять с кодом так же, как чужие API.
- **Side-effect-free probe — проверять формой фикстуры** (#32): template
  без logger (null → NPE) и без auth-consumer; NPE строже любого
  ассерта.
- **`assertSame` на codec-исключении** (#32) — «no wrapping, no new type»
  на уровне юнита.
- **«Classified exactly once» — свойство, которое надо удержать** (#31):
  обход = probe, не перенос `processResponse` в цикл. *Classification*
  (side effects) vs *decodability check* (pure).
- **Scope вылезает из формы, не из special-case'ов** (#31):
  `@OnError`/unexpected → probe сам `true`.
- **Новая ось — новый флажок, не piggyback** (#31).
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function.identity())`
  — уже в `attemptAsync` (не сломать при правке; handle разворачивает
  возвращённый CompletionStage на рантайме, identity-compose чинит только
  статический тип).
- **ADR-концепт vs реальность (серия из 10):** сверять по коду, не по
  памяти; расхождения чинить сразу (в ADR/тесте, не в code-comment).
- **Чужие API — только по jar/javap/исходникам/прогону; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **`RFC_1123_DATE_TIME` — не «все RFC-даты»:** парсит только
  IMF-fixdate (устаревшие формы — ADR-0010).
- **Wall-clock-пины — с запасом** (у wall-clock-интеграций; unit-диапазоны
  с секундной гранулярностью wire-формы — [58_000, 60_000]).
- **java.time 2-значный год = текущий век**, pivot 80–99 — после парса.
- **NPE ≠ NFE:** при переписывании парсеров смотреть набор catch'ей.
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС** (capture-логгер —
  `implements`); логгер в `HttpTemplate` null, пока не `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **UP-DATE ≠ мой прогон:** `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`); `grep -c "<dependency"` exit 1 при 0 — это зелёный.
- **Подсчёт тестов:** awk по XML-заголовкам (`tests=`/`failures=`/
  `errors=` из строк `<testsuite` в `build/test-results/test/*.xml`).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps`, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  dedicated-фикстура на новое поведение — RetryController; log-capture —
  public `setLogger`; шов — package-private метод, не WireMock;
  auth-count — override в TestAuthProvider). Контракт-доки — на
  value-классе/аннотации/SPI-методе.
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — кэш тёплый (19–21 s).
- **DRAFT-пауза — легитимное состояние** (#26); тут эпик активен.
- **Порядок ROADMAP-секций:** новые «COMPLETE ✅ (unreleased)» —
  хронологически, перед Post-0.5.0 shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007…0011 (0011 — payload-decode);
  **0012 — свободен**.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 33 → после следующей 34).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (следующий свободный ADR — 0012).

### Состояние git
- HEAD: коммит сессии #33 (короткий subject, новый стиль) поверх #32
  (surface + шов + юниты). Всё на `agent-loop`. Последний тег `v0.6.1` —
  дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **255/255** (36 классов).
