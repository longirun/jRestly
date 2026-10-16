# Last Session: #189 (2026-10-17)

## Что сделано

**Аудит «ADR против кода» (нить #184), срез ADR-0044 (interceptor @Order):
восьмой «тихий» рассинхрон серии — утечка скопа сортировки.**

1. *Факт.* ADR-0044: «Only sorts within a single `@WithInterceptor` value
   array», «The inter-level ordering guarantee (Global → Super → Sub →
   Method) is not affected», «Cross-annotation ordering still follows
   declaration order». Код `MethodMetadata.parseInterceptors` делал
   пер-декларационную `sortedByOrder` (правильно) **плюс** финальную
   глобальную стабильную сортировку всего разрешённого списка по `@Order` —
   поверх всех аннотаций и уровней. Git-история: глобальная сортировка
   была уже в оригинальном #122 (тогда резортила и глобальные клиентские
   интерцепторы); ADR-0051 (переезд в `MethodMetadata`) глобальные
   исключил, но декларативную утечку сохранил.
2. *Следствия (оба покрыты тестом):* sub/method-интерцептор с `@Order(-10)`
   опережал super-интерцептор с `@Order(0)` (инверсия пирамиды ADR-0029);
   второй `@WithInterceptor` с отрицательным `@Order` выдвигался перед
   первым. Тихо: при всех нулевых значениях (без `@Order`) стаб-сортировка
   — no-op.
3. *Fix:* финальная глобальная сортировка удалена (2 строки + комментарий);
   каждая декларация уже отсортирована `sortedByOrder()` внутри себя.
   Публичный API не тронут. Javadoc `@WithInterceptor` уточнён:
   «Within each `@WithInterceptor` declaration … Separate annotations and
   scope levels keep the order above, so `@Order` never reorders across
   them».
4. *Red-first:* 2 новых теста в `InterceptorResolutionTest`
   (`orderAnnotationDoesNotCrossScopeLevels` — OrderedSuperApi/
   OrderedSubApi с `@Order(-10)` на sub; `orderAnnotationDoesNotReorderAcrossAnnotations`
   — CrossAnnotationApi, две аннотации, вторая с `@Order(-10)`) — красные
   ровно по позициям 0/1, зелёные после. Старые порядковые тесты
   (ADR-0044 ×3, `pipelineOrderingHierarchy`, dedup) не тронуты.
5. *Документы:* ADR-0044 → `## Amendment #189`.
6. *Гейты:* `./gradlew test javadoc` — **1203/1203** (core 1123 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
   зелёный под `-Werror`, 0 warnings.

## Технические заметки

- Вывод по нити: класс «тихих» багов серии #181–#189 — это **скопы/границы
  применения** механик: #184 — скоп дефолта (кап Retry-After), #188 — скоп
  аннотации (пропигатор), #189 — скоп декларации (сортировка `@Order`).
  При проверке ADR сверять именно граничные формулировки «где/в пределах
  чего», а не основную механику.
- `InterceptorResolutionTest` теперь покрывает: пирамиду уровней, дедуп,
  внутри-массивную сортировку (3 ADR-0044 теста), пер-уровневую и
  пер-аннотационную изоляцию (2 новых).
- Кандидат №1 (test-javadoc) по-прежнему приостановлен: ~99 тест-классов без
  class-level javadoc; следующий батч — `cookie` (2), `codec` (4),
  `compression` (3), `tracing` (3), `interceptor` (4).

## Контекст для следующей сессии (#190)

1. **Свободный ход.** Варианты: (a) аудит «ADR против кода» — ADR-0044
   закрыт ✓; остаются ADR-0041 (сжатие запроса, `minSizeBytes`/алгоритмы/
   SPI `ContentCompressor` — проверить дефолты против ADR), ADR-0034
   (query-коллекции: дефолт формата, вложенные коллекции, fallback),
   ADR-0036 (метрики: хуки, дефолты, жизненный цикл), ADR-0035 (стриминг
   тела запроса: контракт `BodyPublisher`, progress, retry-совместимость),
   ADR-0038 (HTTP/2/трейлеры: согласование, `followRedirects`, границы
   движков); (b) кандидат №1, батч №2 test-javadoc; (c) аудит
   «конкурентный жизненный цикл» (идея #187) на `CircuitBreaker`/
   `RateLimiter` (ReentrantLock + volatile state, checkAutoHalfOpenTransition
   из getState/tryAcquirePermission).
2. **Обновлено:** `MethodMetadata` (удалена финальная глобальная
   сортировка), `WithInterceptor.java` (javadoc), `InterceptorResolutionTest`
   (+2 теста), `ADR-0044` (Amendment #189), `DRAFT.md` (запись #189),
   `.agent/days/2026-10-17.md` (кофе + сессия #189),
   `.agent/last_session.md`.
3. **Ветка** `agent-loop`; HEAD до этого коммита — #188 (`f88fb79`).
4. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: `189`. Часовой пояс контейнера может «откатываться» между
   запусками (в этой сессии системное время показывало 2026-10-16T20:30Z =
   2026-10-17 00:30 +04) — писать реальный календарный день.
