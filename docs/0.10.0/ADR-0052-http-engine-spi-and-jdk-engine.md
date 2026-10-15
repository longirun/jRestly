# ADR-0052: Pluggable HttpEngine SPI and JdkHttpEngine Architecture

## Status
Accepted

## Context
1. В jRestly ядро библиотеки изначально строилось вокруг стандартного `java.net.http.HttpClient` из Java 21 (`java.base`). Это обеспечило абсолютный zero-dependency фундамент (0 внешних библиотек в POM публикации).
2. При этом высокоуровневый конвейер `HttpTemplate` напрямую обращался к методам `httpClient.send(...)` и `httpClient.sendAsync(...)`, конструируя платформо-зависимые `java.net.http.HttpRequest` и `java.net.http.HttpResponse`.
3. В сессии #131 Антон отметил важный архитектурный вектор:
   > "чтобы дойти по скорости до мастодонтов, надо сделать JdkHttpEngine дефолтным а через SPI разрешить подключать внешний клиент, который может в скорость. Как думаешь?"
4. Подключение внешних высокоскоростных сетевых клиентов (OkHttp, Apache HttpClient 5), а также сверхлегких mock-транспортов для in-memory тестирования микросервисов без выделения сокетов ОС требует чистой абстракции сетевого транспорта, изолированной от шаблонного пайплайна (интерцепторы, ретраи, сжатие, Circuit Breaker, метрики, десериализация DTO).
5. В ядре уже сформированы платформо-независимые модели `WireRequest` и `WireResponse` (ADR-0047), что создает идеальный фундамент для выделения `HttpEngine`.

## Decision
1. **Интерфейс `HttpEngine` (`ru.jrestly.engine`):**
   - Выделить открытый интерфейс сетевого транспорта:
     ```java
     public interface HttpEngine extends AutoCloseable {
         WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException;
         CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options);
         @Override
         default void close() {}
     }
     ```
2. **Параметры вызова `HttpEngineOptions` (`ru.jrestly.engine`):**
   - Неизменяемый record, передающий настройки единичного вызова:
     * `Duration timeout` — эффективный таймаут запроса (с учетом `@Timeout` метода и клиента).
     * `HttpClient.Version version` — запрошенная версия протокола HTTP.
     * `boolean streamResponse` — флаг потокового чтения (`InputStream`) без промежуточной буферизации.
     * `UploadProgressListener uploadProgressListener` — листенер прогресса отправки тела.
     * `DownloadProgressListener downloadProgressListener` — листенер прогресса получения ответа.
3. **Стандартная zero-dependency реализация `JdkHttpEngine` (`ru.jrestly.engine`):**
   - Реализует `HttpEngine` поверх стандартного `java.net.http.HttpClient`.
   - Поддерживает фабрики `JdkHttpEngine.from(HttpClient)` (оборачивание внешнего клиента без закрытия) и `JdkHttpEngine.create(ModuleInfo)` (автоматическая сборка со всеми SSL, cookies, proxy, virtual threads).
   - Инкапсулирует трансляцию `WireRequest` -> `HttpRequest` и `HttpResponse` -> `WireResponse`.
4. **Service Provider Interface (SPI) `HttpEngineProvider` & `HttpEngineResolver`:**
   - Интерфейс `HttpEngineProvider` для расширения через `java.util.ServiceLoader`:
     * `boolean isAvailable()` — проверка доступности необходимых классов сторонних клиентов на classpath.
     * `HttpEngine createEngine(ModuleInfo moduleInfo)` — фабрика движка.
   - Класс `HttpEngineResolver` реализует стратегию разрешения:
     1. Явный `ModuleInfo.getHttpEngine()` всегда имеет наивысший приоритет.
     2. Первый доступный `HttpEngineProvider` из `ServiceLoader`.
     3. Fallback по умолчанию: `JdkHttpEngine.create(moduleInfo)` (100% zero-dependency).
5. **Интеграция в клиентский конвейер:**
   - В `ModuleInfo` добавлен метод `default HttpEngine getHttpEngine() { return null; }`.
   - В `SimpleModuleInfo.Builder` и `JRestlyClient.Builder` добавлен fluent-метод `httpEngine(HttpEngine)`.
   - В `AbstractHttpClient` сохранена строгая обратная совместимость: поле `protected HttpClient httpClient` доступно для подклассов при использовании `JdkHttpEngine`, а `getHttpEngine()` возвращает активный транспорт.
   - `HttpTemplate` и `HttpTemplateBuilder` полностью освобождены от прямого вызова `java.net.http.HttpClient`: все запросы делегируются в `httpEngine.send(request, options)` и `httpEngine.sendAsync(request, options)`.

## Consequences
- **Плюсы:**
  - Полная модульность сетевого транспорта: возможность бесшовного подключения специализированных высокоскоростных движков (OkHttpEngine, ApacheHttpEngine) или кастомных mock-движков для unit-тестов.
  - Высокоуровневые возможности jRestly (авто-декомпрессия gzip/deflate, Circuit Breaker, Rate Limiter, Retry loop, W3C TraceContext, Request/Response интерцепторы, маппинг в DTO) работают поверх любого `HttpEngine`.
  - Zero-dependency инвариант ядра строго сохранен: ровно 0 зависимостей в Maven POM публикации.
  - Строгая обратная совместимость: существующий код и тесты с `HttpClient` работают без изменений.
- **Минусы / Замечания:**
  - Дополнительный слой делегирования на уровне `HttpEngine` (накладные расходы пренебрежимо малы, менее 5 наносекунд).
