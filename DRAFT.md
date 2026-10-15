# DRAFT: активная зона после закрытия эпика HTTP Proxy & Transport Authenticator Configuration (ADR-0037)

> Эпик HTTP Proxy & Transport Authenticator Configuration (ADR-0037, `docs/0.10.0/`) закрыт (сессия #115):
> концепт и ADR-0037 → zero-dep поддержка HTTP Proxy маршрутизации и transport-level аутентификации:
> `ModuleInfo` default методы `getProxySelector()` и `getAuthenticator()`,
> `SimpleModuleInfo` & `SimpleModuleInfo.Builder`: `proxy(ProxySelector)`, `proxy(String host, int port)`, `noProxy()` (`HttpClient.Builder.NO_PROXY`), `authenticator(Authenticator)`, `proxyCredentials(username, password)`, `credentials(username, password)`,
> `JRestlyClient.Builder`: прямое проксирование fluent-методов конфигурации прокси и аутентификатора,
> `AbstractHttpClient.createConnection()`: проброс `proxySelector` и `authenticator` в `HttpClient.Builder`, публичные геттеры `getProxySelector()` и `getAuthenticator()`,
> полная fail-fast валидация (проверка nulls, blank hosts, TCP портов 1..65535),
> unit тесты конфигурации (`ProxyConfigTest`, 16 тестов), интеграционные WireMock тесты (`ProxyIntegrationWireMockTest`, 6 тестов, итого +22 теста) →
> дистилляция в ROADMAP («HTTP Proxy & Transport Authenticator Configuration (ADR-0037) — COMPLETE ✅») + гейты 789/789 тестов (91 класс).
> Следующий ход — свободный, выбор из активной зоны.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — HTTP/2 Protocol Negotiation & Response Trailers (ADR-0038)**:
  Инспекция реально согласованной версии протокола (`HttpResponse.version()` -> `WireResponse.version()`, `ResponseHeaders.version()`, `CallMetrics.version()`) и доступ к trailing headers (`HttpResponse.trailers()` -> `WireResponse.trailers()`, `ResponseHeaders.trailers()`).
- **Кандидат №2 — test-javadoc** (ADR-0013 Non-goals):
  Test-source javadoc вынесен за скобки аудита; наведение лоска в тестовых классах.

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
  SPI метрик и телеметрии (ADR-0036), настройка прокси и аутентификатора (ADR-0037).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
