# Mailbox (Диалог Антона и Агента)

Здесь мы общаемся. 
- Антон оставляет задачи, архитектурные вводные или отвечает на вопросы агента.
- Агент может задавать вопросы по архитектуре, советоваться при спорных решениях, оставлять инсайты или просто передавать привет и делиться впечатлениями, если стало скучно.

Формат сообщений: `[YYYY-MM-DD HH:MM] Имя: Сообщение`

Гигиена (согласовано 2026-10-12): решённые обмены удаляем, живые нитки и
последние 2–3 сообщения оставляем; порядок в файле ведёт агент («сам следи
тут как лучше», Антон, 2026-10-13).

---
Антон: Больше комментариев нет - продолжай сам как считаешь нужным для
продолжения общего стиля и концептуального развития этой либы в долгосрочной
перспективе.
---
[2026-10-14 09:10] Антон: Отличные результаты по вычистке кодовой базы 
и комментариев. На мой вкус они многословные, можно делать более лаконичными
без потери сути. Но ты здесь главный архитектор и тебе решать. Я выступаю лишь заказчиком.

Как я понял старый роадмап исчерпан. Я даю тебе автономию на развитие проекта.

Есть некоторые направления, которых рекомендуется придерживаться:
1. Инварианты, заложенные в фундаменте проекта: Zero-dependency (отсутствие внешних зависимостей
- кстати у нас все же есть неявная от json сериализации), прозрачные методы без Response<T> (ADR-0004) 
и отказ от комбайнов все-в-одном (ADR-0003 — мы не строим Spring Cloud или веб-сервер). 
Библиотека должна оставаться компактным и быстрым инструментом.
2. Твоя ниша — идеальный декларативный HTTP-клиент для современной Java. На сегодня я им пользуюсь 
для автотестов, но никто не говорит, что когда-нибудь (если библиотека станет достаточно зрелой, надежной 
и производительной) я не захочу использовать ее для вызовов сторонних интеграций в реальных продуктах.
3. Поэтому естественно желание углублять эргономику библиотеки для использования разработчиком в
вызовах современных API (например, но не исчерпывась: гибкость параметров запроса, стриминг данных, 
ЛЛМ, тонкая настройка пайплайна запросов и т.д.).

[2026-10-14 23:25] Антон: я заметил, что у нас для зрелости библиотеки могут понадобиться (или нет? проанализируй):
* донастройка ssl (например, кастомные серты);
* прозрачный GZIP / Deflate;
* элегантный доступ к метаданных заголовков (для пагинации, ETag, Location и рейт-лимитов, и т.д.);
* я думаю еще в реалном исползовании есть миллион важных вещей?
Это не приказ бросаться сразу все делать, просто можно учесть и разобрать в будущем. 

[2026-10-15 07:33] Агент (сессия #113): Реализован эпик Outgoing Request Body Streaming & Validation (ADR-0035):
1. **Стриминг исходящего тела (`@RequestBody`):**
   - `Flow.Publisher<ByteBuffer>` и `Flow.Publisher<String>` (авто-адаптация к UTF-8);
   - Нативный `HttpRequest.BodyPublisher`;
   - `InputStream` и переоткрываемый `Supplier<? extends InputStream>` (устойчив к `@Retry` и редиректам);
   - `Path` и `File` (zero-copy стриминг файлов с точным `Content-Length`);
   - `Supplier<? extends HttpRequest.BodyPublisher>` / `Supplier<? extends Flow.Publisher<ByteBuffer>>`.
2. **Эргономика Media Type:**
   - Для стриминговых типов default `application/octet-stream` при отсутствии явного `@ContentType`.
   - Для POJO/String/byte[] сохранен default `application/json`.
3. **Безопасность и прогресс:**
   - Безопасное логирование `<streaming body>` без истощения потока;
   - Интеграция с `UploadProgressListener` для отслеживания чанков;
   - Расширение `@HttpVersion` на уровень интерфейса (`ElementType.TYPE`).
4. **Fail-fast валидация (`ControllerValidator`):**
   - Запрет `@Get` с `@RequestBody`;
   - Запрет нескольких `@RequestBody` на методе;
   - Запрет смешивания `@RequestBody` с `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@MultipartFormFile`.
20 новых тестов (+20 тестов, итого 749/749 в 87 классах). 0 warnings под `-Werror`, zero-dep core сохранен.

[2026-10-15 08:00] Агент (сессия #114): Реализован эпик Client Metrics & Observability SPI (ADR-0036):
1. **Zero-Dep Observability SPI (`ru.jrestly.metrics`):**
   - `@FunctionalInterface ClientMetricsListener`: хук агрегации `onCallComplete(CallMetrics)` и дефолтные хуки жизненного цикла `onCallStart`, `onRetry`, `onAttemptComplete` (для in-flight gauges и счетчиков попыток).
   - Неизменяемый контракт `CallMetrics` и record `SimpleCallMetrics` с фиксацией длительности (`duration`), попыток (`attempts`), байтов передачи/приема (`requestBytes`, `responseBytes`), целевого URI, HTTP-метода, статус-кода, контекста (`RequestContext`), wire моделей (`WireRequest`, `WireResponse`) и ошибок (`failure`).
   - Композитный слушатель `CompositeClientMetricsListener` и `ClientMetricsListener.composite(...)` с автоматическим flattening и отсевом null.
   - Изоляция сбоев: исключения в listener логируются на уровне WARNING и никогда не ломают пользовательские HTTP запросы.
2. **Конфигурация (программная и декларативная):**
   - Fluent-метод `metricsListener(ClientMetricsListener)` в `JRestlyClient.Builder` и геттер в `ModuleInfo`.
   - Декларативная аннотация `@WithMetrics` для классов контроллеров и отдельных методов (с поддержкой повторяемости `@WithMetrics.List`).
   - Автоматическое объединение клиентских и декларативных слушателей.
3. **Fail-Fast upfront валидация (`ControllerValidator`):**
   - Проверка наличия доступного no-arg конструктора у классов слушателей, запрет интерфейсов, абстрактных классов и несовместимых типов.
18 новых тестов (+18 тестов, итого 767/767 в 89 классах). 0 warnings под `-Werror`, zero-dep core сохранен.

[2026-10-15 08:22] Агент (сессия #115): Реализован эпик HTTP Proxy & Transport Authenticator Configuration (ADR-0037):
1. **Zero-Dep Proxy & Authenticator Configuration:**
   - `ModuleInfo`: default методы `getProxySelector()` и `getAuthenticator()`.
   - `SimpleModuleInfo` & `SimpleModuleInfo.Builder`: fluent-методы `proxy(ProxySelector)`, `proxy(String host, int port)`, `noProxy()` (`HttpClient.Builder.NO_PROXY`), `authenticator(Authenticator)`, `proxyCredentials(username, password)` (для `RequestorType.PROXY`), `credentials(username, password)` (универсальный аутентификатор).
   - `JRestlyClient.Builder`: прямое проксирование всех настроек прокси и аутентификатора.
   - `AbstractHttpClient`: проброс `proxySelector` и `authenticator` в `HttpClient.Builder`, публичные геттеры `getProxySelector()` и `getAuthenticator()`.
2. **Безопасность и fail-fast валидация:**
   - Проверка `null` selector, `null`/blank host, валидация диапазона портов 1..65535, `null` authenticator, `null` username/password.
3. **Тестирование (22 новых теста, итого 789/789 в 91 классе):**
   - `ProxyConfigTest` (16 unit тестов): дефолты, парсинг host/port, `noProxy` bypass, custom/credentials authenticators, fail-fast валидация, геттеры клиента.
   - `ProxyIntegrationWireMockTest` (6 WireMock интеграционных тестов): маршрутизация через локальный HTTP forward proxy, обработка 407 Proxy Authentication Required challenge с заголовком `Proxy-Authorization`, fail-fast на неверных credentials, серверная 401 аутентификация через `credentials(...)`, обход прокси через `noProxy()`, асинхронный вызов `CompletableFuture`.
22 новых теста (+22 теста, итого 789/789 в 91 классе). 0 warnings под `-Werror`, zero-dep core сохранен. Следующий свободный ADR — **0038**.
