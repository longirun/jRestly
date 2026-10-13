# Handover Note: Сессия 34 (payload-decode шаг 4: async-твин + интеграция 6; 255→256/256, DRAFT — активный эпик, 4/5)

## Что сделано в этой сессии
- **Входящее:** mailbox тиш после #33 (~час) — новых сообщений Антона
  нет, план (шаг 4) не менялся.
- **Код (async-твин — «кто зовёт шов» на async-контуре).**
  - `HttpTemplate.attemptAsync`: ветка `if (last ||
    !isRetryableStatus(response))` (final-условие handle-колбека)
    получила зеркало sync-оси #33: `!last &&
    retryPolicy.isRetryDecodeFailures()` → `probePayloadDecode(response)`;
    codec-исключение (catch RuntimeException) → `scheduleAsyncRetry(
    request, attempt, maxAttempts, "decode (" + e.getClass().
    getSimpleName() + ")", response, hopsLeft)`. Probe — обычный
    синхронный вызов в handle-колбеке (колбэк исполняется на завершившем
    потоке; `ThreadLocalRandom` и прочего thread-affine в probe нет) —
    никаких новых CompletionStage-веток, в точности форма, зафиксированная
    в last_session #33. При `last` probe не вызывается — финальный
    `processResponse` бросит codec-исключение сам (Aspect 3).
  - Комментарии: `executeAsyncWithRetries` +3 строки (decode-ось
    зеркалируется дословно — зеркало sync-коммента ADR-0011 Aspect 2);
    комментарий ветки в `attemptAsync` — адаптация sync-формулировки
    (side-effect-free probe → tail `exchangeAsync` ровно один раз).
- **Тест (1 новая интеграция, `RetryDecodeTest`, ADR-0011 Aspect 7
  сценарий 6).** Async 200-мусор→200-норма (WireMock-scenario
  `decode-flap-async` — своё имя, сценарии глобальны для инстанса),
  `with(smallBackoff().maxAttempts(2).retryDecodeFailures(true).build()).
  getItemAsync(1L).join()` → `TestDto`, 2 запроса. **Фикстуру не
  добавляли:** `TestController.getItemAsync(1L)` уже существует.
  Exhaustion-typing в async НЕ пиновый: маппинг `exchangeAsync` не
  тронут (ADR Aspect 6 — «untouched») — codec-исхождение на
  `thenApply` → `exceptionally` → `RuntimeException(cause)` ровно как
  и без retry; typing закреплён sync-интеграцией 2. Класс-Javadoc
  «scenarios 1–5» → «1–6».
- **Зелёный с первого прогона** (редкость): зеркало + форма из
  last_session дословно — 10-я серия «ожидание vs реальность» не
  сработала, потому что сверяться было не с чем: всё уже решено в #31–#33.
- **Гейты.** `./gradlew test --rerun` — **256/256** (255→256, +1; 0
  failures/0 errors по XML, 36 классов, 19 s тёплый кэш); сначала
  целевой прогон (`RetryDecodeTest` + `ru.jrestly.http.*`). POM
  `pom-default.xml` — **0 `<dependency>`**, 0.6.2.
- **Память.** DRAFT шаг 4 `[x]`. Mailbox #34 + гигиена (удалён realized
  #32 — шаг 2 дистиллирован; остались правила Антона + нитки про теги/
  коммиты + #33 + #34). Дневник #34 + «Итог дня» обновлён (живой).

## Контекст для следующей сессии (#35)

### Эпик (DRAFT): payload-decode retry (ADR-0011) — шаг 5 (финальный)
1. [x] Шаг 1 — концепт ADR-0011 (#31).
2. [x] Шаг 2 — surface + шов + юниты (#32).
3. [x] Шаг 3 — sync-wiring + интеграции 1–5 (#33).
4. [x] Шаг 4 — async-твин + интеграция 6 (#34).
5. **[ ] Шаг 5 — Javadoc-контракт + README + финальный gate +
   дистилляция в ROADMAP.** Объём — как у #22 (gate retry-эпика):
   доки + память, кода почти нет:
   - **Javadoc-контракт (sync/async uniformity decode-оси).**
     `RetryPolicy`: буллет `retryDecodeFailures` в списке «what is
     retried» уже есть (#32) — проверить, что он не говорит «sync-only»;
     при необходимости — 1 строка «applies to both synchronous and
     CompletableFuture-returning controller methods» в стиле sync/async-
     абзаца #22. `@Retry`: атрибут `retryDecodeFailures` с Javadoc (#32) —
     то же зеркало. `ModuleInfo.getRetryPolicy()` — уже есть «applies to
     both» (sync/async) из #22 — decode-флаг через policy покрывается,
     отдельного упоминания может не понадобиться (сверить по тексту).
   - **README «Retry with backoff» — decode-параграф.** Секция существует
     (#22). Добавить абзац: отдельный opt-in `retryDecodeFailures`
     (default off), scope — complete-but-unbindable 200 (third
     failure-kind), cost — double-decode на финале, финальное исключение =
     codec-своё unchecked (без нового класса), sync/async uniformity.
     Фича-буллет в шапке — возможно +1 упоминание (сверить по тексту).
   - **Финальный gate:** `./gradlew test --rerun` (ожидается 256/256) +
     POM (0 `<dependency>`, 0.6.2).
   - **Дистилляция в ROADMAP:** новый раздел «Payload-decode retry —
     COMPLETE ✅ (2026-10-13, unreleased)» в стиле Gson/Moshi/Retry/0.x-
     polish-разделов: source of truth — ADR-0011, сессии #31–#35, решения
     (третий failure-kind, side-effect-free probe — «classified exactly
     once» сохранён, codec-своё исключение без нового класса, отдельный
     opt-in, ADR-0009-аргумент 1 сужен complete-but-unbindable vs
     incomplete), тестовый периметр (8 probe-юнитов + 6 интеграций,
     256/256). В Post-0.5.0 shortlist — зачёркивать нечего (shortlist
     выработан в #22) — проверять. **ADR-0008 Known gaps:**
     payload-decode-строка зачёркнута + указатель на ADR-0011 (прецедент
     #25/#28 с HTTP-date/асctime). **ADR-0009 кандидат 4:** «LEAVE»
     аннотирован «reopened by ADR-0011» (указатель-правка, решение стоит).
     DRAFT: эпик дистиллирован → зачистить под новое состояние (пауза/
     следующий кандидат — на усмотрение: контур 0.7.0-цикла в DRAFT
     остаётся живым кандидатом).
   - **Mailbox:** заметка #35 (эпик закрыт) + гигиена. Дневник #35 +
     «Итог дня» — возможно, вечерний финал дня (15-я сессия).

### Грабли этой и прошлых сессий (актуальный набор)
- **Коммиты — КРАТКИЙ subject, без дублирования ченджлога** (Антон,
  2026-10-13, нитка в mailbox): с #33 новый стиль (пример: «feat+test:
  payload-decode retry step 3 — sync wiring + ADR-0011 integrations 1-5»).
  Длинное тело не нужно — ченжлог и так в DRAFT/ROADMAP/дневниках.
- **Зеркало синхронизировано формой, а не памятью** (#34): async-ветка
  написана дословно по sync-коду (#33) + форма из last_session — первый
  прогон зелёный. Правило подтверждено в обратную сторону: когда
  last_session даёт форму дословно, «ожидание vs реальность» не срабатывает.
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
  auth-count — override в TestAuthProvider; async-интеграция — существующая
  фикстура + `with(policy)` helper). Контракт-доки — на value-классе/
  аннотации/SPI-методе.
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — кэш тёплый (19 s).
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
   сессий подкручивает harness (сейчас 34 → после следующей 35).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (следующий свободный ADR — 0012).

### Состояние git
- HEAD: коммит сессии #34 (короткий subject) поверх #33. Всё на
  `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **256/256** (36 классов).
