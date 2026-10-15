# DRAFT: активная зона после закрытия Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055)

> Эпик Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning (ADR-0055, `docs/0.10.0/`) закрыт (сессия #135):
> - В `MethodMetadata` предвычислены и закэшированы: `staticCookieHeader` (строка формата `k=v; k2=v2`), флаг `hasCookieParams`, флаги `hasExplicitContentTypeInStaticHeaders`, `isStaticConditional` и `hasTrace`.
> - В `HttpTemplateBuilder.resolveCookies()` добавлен fast-path: при отсутствии динамических cookie-параметров возвращается `methodMetadata.staticCookieHeader()` напрямую без аллокаций `LinkedHashMap` (0 heap allocations).
> - В `HttpTemplateBuilder.createHeaders()` добавлен fast-path для статических запросов: при отсутствии динамических заголовков, куки и auth список `staticHeaders` возвращается напрямую без создания промежуточных копий `ArrayList`.
> - В `HttpTemplateBuilder` аудит auth-заголовков переведен на `List.of()` и плоский цикл, устранив Stream API из `authHeaderNames`.
> - Для методов без query параметров `createRequestParams()` пропускается, устанавливая неизменяемый синглтон `List.of()`.
> - В `HttpTemplate.createUri()` и `HttpTemplateBuilder.createUrlEncodedBody()` сборка параметров переведена с Java Stream API на прямые плоские циклы с `StringBuilder`.
> - В `HttpTemplate.processResponse()` аллокация `Duration.ofNanos(System.nanoTime() - startNanos)` сделана строго ленивой (создается только при активном перехвате `ResponseHeaders`).
> - В `HttpTemplate.exchange()` массив `attemptsHolder` аллоцируется строго лениво (только при активных метриках или circuit breaker).
> - `HttpEngineOptions` предвычисляется один раз в `HttpTemplateBuilder` и кэшируется в `HttpTemplate`, устраняя повторные аллокации на каждый send/hop.
> - В `AbstractHttpClient.isLoginInBackgroundNeeded()` проверка `isAuthorized()` вынесена на первое место, устраняя избыточные вызовы Reflection API на авторизованных запросах.
> - В `HttpTemplateBuilder.resolveCompression()` задействован синглтон `DEFAULT_NO_COMPRESSION`.
> - Замеры JMH с `-prof gc`: аллокации на `jrestly_getUser` снизились с **49898 B/op до 49378 B/op**, на `jrestly_ping_zeroDepRaw` снизились с **48030 B/op до 47392 B/op**.
> - Достигнута отметка **1035/1035 тестов** в 134 классах (+9 новых тестов), 0 warnings под `-Werror`, POM строго zero-dep.
> Следующий ход — свободный, выбор из активной зоны.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals):
  Наведение лоска и лаконичного javadoc в тестовых классах.

- **Кандидат №2 — HTTP Trailers & Advanced Streaming ergonomics**:
  Расширенные сценарии работы с трейлерами и чанками в стриминге данных (LLM / SSE).

- **Кандидат №3 — OkHttpEngine / ApacheHttpEngine external modules**:
  Вынос внешних коннекторов в отдельные субмодули для максимальной скорости под нагрузкой.


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
  Circuit Breaker и ограничение частоты запросов Rate Limiter SPI (ADR-0043),
  Interceptor @Order Priority (ADR-0044).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
