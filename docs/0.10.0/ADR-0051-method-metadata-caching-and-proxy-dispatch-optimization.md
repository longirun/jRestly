# ADR-0051: MethodMetadata Caching and Proxy Dispatch Hot-Path Optimization

## Status
Accepted

## Context
В ходе нагрузочного тестирования и JMH профилирования (ADR-0049, ADR-0050) было установлено, что jRestly опережает Spring Framework 6 RestClient (+6.1% ops/s) и генерирует на 17.5% меньше мусора памяти под высокой параллельной нагрузкой.

Однако при детальном анализе горячего пути диспетчеризации динамического прокси (`AbstractHttpClient.createController` -> `HttpTemplateBuilder.build()`) было выявлено скрытое узкое место:
1. На **каждый** вызов метода интерфейса конвейер заново выполняет полное рефлексивное сканирование:
   - `resolveRequestMeta(method)`: рефлексия аннотаций HTTP-методов (`@Get`, `@Post`, `@Put`, `@Delete`, `@Patch`).
   - `collectInterfaceHierarchy(controller)`: рекурсивный обход интерфейсов контроллера для поиска аннотаций `@Timeout`, `@ExpectStatus`, `@FollowRedirects`, `@HttpVersion`, `@OnError`, `@RequestCompression`, `@CircuitBreaker`, `@RateLimiter`, `@WithMetrics`.
   - Рефлексивный разбор аннотаций `@WithInterceptor` и вычисление приоритетов `@Order`.
   - Рефлексивный разбор generic return type (`CompletableFuture`, `Optional`, `InputStream`, `Stream`).
   - Поиск intrinsic параметров (`ResponseHeaders.class`).
2. Рефлексивные вызовы Java (`Method.getAnnotation()`, `Class.getAnnotationsByType()`) аллоцируют массивы и создают паразитное давление на память и CPU при высокой частоте запросов (RPS).
3. При этом метаданные метода интерфейса и его аннотации являются **абсолютно иммутабельными** и зависят исключительно от пары `(Method, controllerClass)`.

## Decision
1. **Выделение package-private `MethodMetadata` (`ru.jrestly.http`):**
   - Создать иммутабельный класс `MethodMetadata`, инкапсулирующий все статически вычисляемые метаданные метода контроллера:
     * `RequestMeta` (HTTP метод, путь, RequestType, inline query params).
     * Декларативные Content-Type и Accept заголовки метода и интерфейса.
     * Заголовки трассировки W3C Trace (`Trace`).
     * `CollectionFormat` по умолчанию.
     * `OnErrorParser` и список ожидаемых статусов `expectStatuses`.
     * `FollowRedirectsNumber` и `HttpClient.Version`.
     * Переопределение `RetryPolicy` (из аннотации `@Retry`).
     * Переопределение `Duration timeout` (из аннотации `@Timeout`).
     * Декларативные интерцепторы (`requestInterceptorClasses`, `responseInterceptorClasses`).
     * Классы листенеров прогресса (`UploadProgressListener`, `DownloadProgressListener`).
     * Метрики (`metricsListenerClass`).
     * Настройки сжатия тела (`compression`).
     * Экземпляры `CircuitBreaker` и `RateLimiter` (полученные из статических реестров по имени аннотации).
     * Метаданные возвращаемого типа `ReturnTypeInfo` (`targetType`, `async`, `optional`, `streamBody`).
     * Индекс параметра `responseHeadersHolderIndex` (-1 если отсутствует).
     * Имя заголовка аутентификации `authDetailsHeaderName` (из `@SetAuthDetails`).
2. **Потокобезопасный Concurrent кэш:**
   - В `MethodMetadata` реализовать кэш `ConcurrentHashMap<MethodKey, MethodMetadata>`, где ключ — `record MethodKey(Method method, Class<?> controllerClass)`.
   - Метод `MethodMetadata.of(Method method, Class<?> controllerClass)` выполняет быстрый lock-free `computeIfAbsent`.
3. **Оптимизация `HttpTemplateBuilder`:**
   - Заменить повторный рефлексивный разбор аннотаций в методе `build()` на мгновенное чтение предрассчитанных свойств из `MethodMetadata`.
   - Сохранить 100% обратную совместимость: семантика переопределения таймаутов, интерцепторов, кодеков и политик ретраев сохраняется в неизменном виде.

## Consequences
- **Плюсы:**
  - Полное устранение повторных вызовов Reflection API на горячем пути вызова методов прокси.
  - Значительное снижение накладных расходов диспетчеризации в памяти и аллокаций мусора per-request.
  - 100% сохранение zero-dependency core и обратной совместимости: класс `MethodMetadata` полностью инкапсулирован внутри package-private пакета `ru.jrestly.http`.
- **Минусы / Замечания:**
  - Первый вызов каждого метода контроллера производит единовременный разбор и прогрев кэша (single-flight init), все последующие вызовы выполняются без рефлексии метаданных.
