# DRAFT: активная зона после закрытия эпика Circuit Breaker & Client Rate Limiter SPI (ADR-0043)

> Эпик Circuit Breaker & Client Rate Limiter SPI (ADR-0043, `docs/0.10.0/`) закрыт (сессия #121):
> концепт и ADR-0043 → zero-dep отказоустойчивость клиентской стороны:
> `ru.jrestly.resilience`: `CircuitBreaker` (FSM `CLOSED`, `OPEN`, `HALF_OPEN`), `CircuitBreakerConfig` (порог сбоев, sliding window, таймаут восстановления, классификация исключений: 5xx/IO сбои vs 4xx норма), `CircuitBreakerMetrics`, `CircuitBreakers`, `CircuitBreakerRegistry`, `CircuitBreakerOpenException`,
> `RateLimiter` (zero-dep Token Bucket на monotonic nanoseconds), `RateLimiterConfig`, `RateLimiterMetrics`, `RateLimiters`, `RateLimiterRegistry`, `RateLimitExceededException`,
> `@CircuitBreaker` и `@RateLimiter` (`ru.jrestly.annotation`): декларативные аннотации на методах и интерфейсах с opt-out (`enabled = false`),
> `SimpleModuleInfo.Builder` & `JRestlyClient.Builder`: fluent-методы `circuitBreaker(...)`, `circuitBreakerRegistry(...)`, `rateLimiter(...)`, `rateLimiterRegistry(...)`, изолированные per-module реестры по умолчанию,
> `ControllerValidator`: валидация непустых имен, положительных порогов, допустимых окон и неотрицательных таймаутов,
> `HttpTemplateBuilder`: прозрачное разрешение с учетом приоритетов Method > Sub-interface > Super-interface > Client,
> `HttpTemplate`: защита и быстрый отказ (fail-fast) до сетевого вызова в `exchange()` и `exchangeAsync()` без обращения к сети, учет исходов вызовов,
> unit-тесты (`CircuitBreakerTest`, `RateLimiterTest`, `ResilienceConfigTest`) и WireMock интеграционные тесты (`CircuitBreakerWireMockTest`, `RateLimiterWireMockTest`, итого +30 тестов) →
> дистилляция в ROADMAP («Circuit Breaker & Client Rate Limiter SPI (ADR-0043) — COMPLETE ✅») + гейты 924/924 тестов (111 классов).
> Следующий ход — свободный, выбор из активной зоны.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals):
  Test-source javadoc вынесен за скобки аудита; наведение лоска в тестовых классах.
- **Кандидат №2 — Interceptor Execution Order & Priority SPI (ADR-0044)**:
  Zero-dep строгое детерминированное упорядочивание интерцепторов (`@Order` / приоритеты, фазы аутентификации, логирования, модификации заголовков), short-circuiting ответа из интерцептора без сетевого вызова (мок/кэш).

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
  шаблоны URI и массивы параметров (ADR-0025), виртуальные потоки (ADR-0026),
  декларативные таймауты (ADR-0027), декларативные интерцепторы (ADR-0029),
  отслеживание прогресса загрузки (ADR-0030), куки и управление сессиями (ADR-0032),
  отслеживание прогресса скачивания и прямой возврат byte[] (ADR-0033),
  форматирование query коллекций (ADR-0034), стриминг тела запроса (ADR-0035),
  SPI метрик и телеметрии (ADR-0036), настройка прокси и аутентификатора (ADR-0037),
  согласование протокола HTTP/2 и трейлеры (ADR-0038), персистентность cookie store (ADR-0039),
  продвинутые стратегии джиттера и ограничения Retry-After (ADR-0040),
  сжатие тела запроса и Content-Encoding SPI (ADR-0041),
  трассировка запросов и проброс Correlation ID / W3C TraceContext (ADR-0042),
  Circuit Breaker и ограничение частоты запросов Rate Limiter SPI (ADR-0043).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
