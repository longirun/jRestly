# DRAFT: Эпик SSL / Custom Certificates & mTLS (ADR-0020)

> Эпик открыт в сессии #97 в рамках векторов зрелости библиотеки (mailbox Антона от 2026-10-14 23:25).
> Концепт и архитектурные решения зафиксированы в `docs/0.10.0/ADR-0020-ssl-custom-certificates-mtls.md`.

## Шаги эпика

- [x] **Шаг 1: Концепт и дизайн (сессия #97)**
  - Архитектурное решение ADR-0020 (`docs/0.10.0/ADR-0020-ssl-custom-certificates-mtls.md`).
  - Анализ вариантов mTLS, корпоративных CA, self-signed сертов, протоколов TLS и SNI.
  - Zero-dep инвариант: типы `javax.net.ssl.SSLContext` и `javax.net.ssl.SSLParameters` из `java.base`.
- [x] **Шаг 2: Реализация конфигурационной поверхности (сессия #97)**
  - `ModuleInfo`: `default SSLContext getSslContext() { return null; }`, `default SSLParameters getSslParameters() { return null; }`.
  - `SimpleModuleInfo` и `SimpleModuleInfo.Builder`: хранение, геттеры и fluent-методы `sslContext(SSLContext)` / `sslParameters(SSLParameters)` с fail-fast на `null`.
  - `JRestlyClient.Builder`: делегирование в `moduleInfoBuilder`.
  - `AbstractHttpClient.createConnection()`: проброс `sslContext` и `sslParameters` в `HttpClient.Builder`.
  - Unit-тесты `SslConfigTest` (9 тестов, проверяющие дефолты null, явную передачу, fail-fast на null).
- [ ] **Шаг 3: Интеграционные тесты с WireMock HTTPS (сессия #98)**
  - Поднятие WireMock на динамическом HTTPS порту (`dynamicHttpsPort()`).
  - Отрицательный тест: клиент с дефолтным `SSLContext` отклоняет самоподписанный сертификат с SSLHandshake/SSLException.
  - Положительный тест: клиент с кастомным `SSLContext` (trust-all или truststore WireMock) успешно выполняет декларативные запросы.
  - Тест с `SSLParameters`: клиент с кастомными `SSLParameters` (протоколы TLS, endpoint identification algorithm).
- [ ] **Шаг 4: Гейты и финализация эпика**
  - Полный прогон `./gradlew test compileJava compileTestJava javadoc`.
  - Фиксация результатов, обновление статуса ADR-0020 (Accepted) и дистилляция в ROADMAP.md.

---

## Активная зона (кандидаты на следующие эпики)

- **Кандидат №2 — Прозрачный GZIP / Deflate decompression** (mailbox #94):
  JDK `HttpClient` не распаковывает сжатые ответы автоматически. Поддержка `Accept-Encoding: gzip, deflate`
  и прозрачная распаковка потока ответа через `GZIPInputStream` и `InflaterInputStream` в Java
  standard library (zero-dep), экономия трафика и удобство работы.
- **Кандидат №3 — Элегантный доступ к метаданным заголовков** (mailbox #94):
  Пагинация по заголовку `Link`, доступ к `ETag`, `Location`, рейт-лимитам (`X-RateLimit-*`, `Retry-After`)
  при строгом сохранении инварианта прозрачных методов без `Response<T>` (ADR-0004). Исследование
  подходов: thread-scoped metadata context, consumer/holder аргументы в сигнатуре метода, либо
  интеграция с `ResponseInterceptor`.
- **Кандидат №4 — test-javadoc** (ADR-0013 Non-goals):
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
  API — все 4 эпика direction 3 закрыты (ADR-0015–0018), packaging в 0.9.0 завершён.
- **Векторы зрелости библиотеки (Антон, 2026-10-14 23:25, живая нить):**
  донастройка SSL (кастомные серты, mTLS), прозрачный GZIP/Deflate,
  элегантный доступ к метаданным заголовков (пагинация, ETag, Location, rate limits).
  SSL взят в работу в ADR-0020!
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
