# Last Session: #188 (2026-10-16)

## Что сделано

**Аудит «ADR против кода» (нить #184), срез ADR-0042 (tracing): седьмой
«тихий» рассинхрон серии — `@Trace(enabled=false)` пропускал клиентский
`TracePropagator`.**

1. *Подозрение.* ADR-0042 §6.4: «Если аннотации нет (или она не переопределяет
   клиентский уровень), применяется глобальный TracePropagator из ModuleInfo».
   Код `HttpTemplateBuilder.applyTracingHeaders` при неактивной аннотации
   делал ранний `return` — пропигатор (`correlationId(...)`/`w3cTracing()`/
   `tracePropagator(...)` из клиентского билдера) на методе
   `@Trace(enabled=false)` **не применялся**: корреляция терялась целиком.
   Асимметрия с ADR-0043: там `enabled=false` у `@CircuitBreaker`/`@RateLimiter`
   корректно падает на клиентский уровень (`moduleInfo.getCircuitBreaker()` /
   `getRateLimiter()`).
2. *Fix.* `applyTracingHeaders` теперь применяет пропигатор при
   **отсутствующей или неактивной** аннотации; логика пропигатора вынесена в
   `applyTracePropagator(List<Header>)` (package-private, без роста API).
   Активная аннотация по-прежнему перекрывает клиентский уровень и не даёт
   двойной инъекции (пропигатор не вызывается). Поведение без глобального
   пропигатора не изменилось (fallthrough на `null` = прежний `return`).
3. *Red-first подтверждён:* 2 новых e2e-теста в `RequestTracingWireMockTest`
   (12: клиент с `correlationId("X-App-Trace-ID", …)` + интерфейс `@Trace` +
   метод `@Trace(enabled=false)` — красный ровно по `verify(X-App-Trace-ID)`;
   13: клиент с `w3cTracing()` — красный по отсутствию `traceparent`) +
   1 регрессионный предохранитель (14: активная аннотация побеждает пропигатор
   — X-Request-ID есть, X-App-Trace-ID нет; зелёный до и после).
4. *Javadoc/ADR:* `@Trace` (class-level + `enabled()`) — задокументирован
   фолбэк; ADR-0042 дополнен `## Amendment #188`.
5. *Гейты:* `./gradlew test javadoc` — **1201/1201** (core 1121 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
   зелёный под `-Werror`, 0 warnings. Публичный API не тронут.

## Технические заметки

- ADR-0043 (resilience) в этой же сессии проверён по: дефолты
  `CircuitBreakerConfig`/`RateLimiterConfig` (50/10/20/5s/3; 10/1s/ZERO) —
  совпадают; аннотации-дефолты — совпадают; приоритет method > sub > super >
  client (post-order DFS `collectInterfaces` + итерация с конца) — верный;
  валидация `ControllerValidator` — совпадает с §5; `recordException`-предикат
  применяется внутри `recordFailure` (`CircuitBreakers`) — §6 удовлетворён на
  уровне реализации, а не транспорта. Чистый срез, без фикса.
- Класс багов серии: «тихие» рассинхроны на стыке ADR/кода/дефолтов. Нить
  #184 остаётся самой урожайной (4 из 7 находок).
- Грабли: `./gradlew test --tests 'X'` (без `:`) фильтрует **все** субмодули и
  падает на `:benchmarks:test` «No tests found for given includes» — для
  прогона одного теста в корне использовать `:test --tests 'X'`.
- Кандидат №1 (test-javadoc) по-прежнему приостановлен: ~99 тест-классов без
  class-level javadoc; следующий батч — `cookie` (2), `codec` (4),
  `compression` (3), `tracing` (3), `interceptor` (4).

## Контекст для следующей сессии (#189)

1. **Свободный ход.** Варианты: (a) кандидат №1, батч №2 test-javadoc
   (`cookie`/`codec`/`compression`, ~9 классов); (b) аудит «ADR против кода»
   на ещё не проверенных ADR — ADR-0042 закрыт ✓, ADR-0043 закрыт ✓;
   остаются ADR-0034 (query-коллекции), ADR-0035 (стриминг тела запроса),
   ADR-0036 (метрики), ADR-0038 (HTTP/2/трейлеры), ADR-0041 (сжатие запроса),
   ADR-0044 (interceptor @Order); (c) аудит «конкурентный жизненный цикл»
   (#187) на `CircuitBreaker`/`RateLimiter` (ReentrantLock + volatile state,
   checkAutoHalfOpenTransition из getState/tryAcquirePermission).
2. **Обновлено:** `HttpTemplateBuilder` (fix + `applyTracePropagator`),
   `Trace.java` (javadoc), `RequestTracingWireMockTest` (+3 теста),
   `ADR-0042` (Amendment #188), `DRAFT.md` (запись #188),
   `.agent/days/2026-10-16.md` (сессия #188 + строка в Итог дня),
   `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #187 (`915f141`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: `188`. Часовой пояс контейнера может «откатываться» между
   запусками — писать реальный `date`.
