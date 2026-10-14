# DRAFT: активная зона после закрытия эпика Virtual Threads & Executor Customization (ADR-0026)

> Эпик Virtual Threads & Executor Customization (ADR-0026, `docs/0.10.0/`) закрыт 5/5 (сессия #104):
> концепт и ADR-0026 → `default Executor getExecutor()` и `default boolean isVirtualThreads()` в `ModuleInfo` +
> fluent `executor(Executor)`, `virtualThreads(boolean)`, `useVirtualThreads()` в `SimpleModuleInfo.Builder` и `JRestlyClient.Builder` +
> wiring `builder.executor(...)` в `AbstractHttpClient.createConnection()` с управлением жизненным циклом `managedExecutorService.close()` +
> асинхронный пайплайн (`sendAsync`, `exchangeAsync` thenApplyAsync/whenCompleteAsync) и scheduler retry backoff (`CompletableFuture.delayedExecutor`) на настроенном executor/virtual threads в `HttpTemplate` +
> unit тесты (`ExecutorConfigTest`, 8 тестов) и WireMock интеграционные тесты (`VirtualThreadAndExecutorWireMockTest`, 5 тестов, итого +13 тестов) →
> дистилляция в ROADMAP («Virtual Threads & Executor Customization (ADR-0026) — COMPLETE ✅») + гейты 549/549 тестов (66 классов).
> Следующий ход — свободный, выбор из активной зоны.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals):
  Test-source javadoc вынесен за скобки аудита; наведение лоска в тестовых классах.
- **Кандидат №2 — Per-method timeout controls (`@Timeout`)**:
  Декларативная перегрузка socket timeout per-method для медленных отчетов или быстрых health check вызовов.

---

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
  лучше — в этом файле и вообще на проекте»). Практика: mailbox не растёт
  бесконечно (применять правило «решённый обмен удаляем» в каждой сессии,
  где он применим); устаревшие «Пометки из mailbox» в DRAFT сверять с
  реальностью; мелкие расхождения (версии, статусы) чинить сразу, не копить;
  ROADMAP — верифицируемые утверждения (счётчики тестов, версии, хэши,
  ссылки) сверять с реальностью.
- **После закрытия эпика DRAFT не пустеет** (сессия #39): кандидаты
  поднимаются в активную зону — следующий ход выбирается из них.
- **Гейт без отрицательного теста — не гейт** (сессия #58).
- **Факты — по коду/дереву/jar, не по имени/памяти** (сессии #47–#57, #64).

Пометки из mailbox (актуальные на 2026-10-15):
- **Автономия + 3 направления (Антон, 2026-10-14 09:10, живая нить):**
  (1) инварианты — zero-dep, прозрачные методы (ADR-0004),
  анти-комбайн (ADR-0003); (2) ниша — идеальный декларативный
  HTTP-клиент для современной Java; (3) эргономика для современных
  API.
- **Векторы зрелости библиотеки (Антон, 2026-10-14 23:25, живая нить):**
  все направления зрелости последовательно реализуются: SSL/mTLS (ADR-0020),
  прозрачный GZIP/Deflate (ADR-0021), доступ к метаданным заголовков (ADR-0022),
  условные запросы & Optional (ADR-0023), гибкость form-data/multipart (ADR-0024),
  шаблоны URI и массивы параметров (ADR-0025).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
