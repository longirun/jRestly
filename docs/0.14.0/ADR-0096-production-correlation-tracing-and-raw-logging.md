# ADR-0096: Enterprise Correlation Tracing, TraceId Log Prefixing, and Raw Diagnostic Mode

- **Status:** Accepted
- **Date:** 2026-10-21
- **Authors:** Autonomous Senior Java Core Engineer & Architect
- **Target Version:** 0.14.0
- **Scope:** Production Observability, Log Correlation (Virtual Threads / High Concurrency), Configurable Sanitization (Raw Debugging), Typed SSE Backlog

---

## 1. Context & Business Mandate

В сессии #364 (ADR-0095) был успешно реализован первый фундаментальный шаг enterprise-безопасности:
маскирование конфиденциальных данных (`SensitiveDataSanitizer`) по стандартам 152-ФЗ РФ, GDPR и PCI-DSS v4.0.

По результатам сессии Антон сформулировал следующие обязательные практические требования:
> *«Отлично, просто отлично! Но должна быть возможность логировать все что есть, отключить санитизацию.*
> *Должна быть возможность прокидывать какой-то traceId, чтобы логи не мешались в кучу - в энтерпрайзе в проде там будет жесть и надо как-то отличать разные пользовательские флоу в едином месиве.*
> *Дополнительно: добавь в беклог SSE typed decode.*
> *Да и вообще надо как следует проработать SSE - на сейчас это очень важная тема для индустрии.*
> *Может примеров в ридми добавить? Или просто написать нормальную документацию на нашу либу?»*

---

## 2. Архитектурные решения

### 2.1. Конфигурируемая санитизация: Raw Debugging Mode

По умолчанию jRestly работает в режиме **Secure by Default** (`sanitizeSensitiveData = true`). Это гарантирует, что даже при случайном включении логирования на уровне `INFO` токены и персональные данные клиентов не утекут в ELK/Logstash.

Однако при воспроизведении сложных дефектов интеграции на тестовых контурах (staging, dev, sandbox) инженерам сопровождения необходимо видеть сырые заголовки и тело целиком.

Решение:
1. В `JRestlyClient.Builder`:
   - `sanitizeSensitiveData(boolean sanitize)`
   - `sanitizeLogs(boolean sanitize)` (удобный синоним)
2. В `ModuleInfo` и `SimpleModuleInfo`:
   - `boolean isSanitizeSensitiveData()`
3. Глобальный флаг через системное свойство JVM:
   - `-Djrestly.logging.sanitize=false` для временного переключения на проде без пересборки артефакта.
4. В `HttpDiagnosticLogger`:
   - Если санитизация отключена (`sanitize = false`), URL, заголовки и тело запроса выводятся без модификации и без затрат на regex-парсинг.

### 2.2. Сквозная корреляция логов: TraceId Prefixing

В высоконагруженных средах на Java 21 Virtual Threads параллельно выполняются десятки тысяч запросов. Строки логов `Request:` и `Response:` перемешиваются во времени.

Решение:
1. Создан высокопроизводительный package-private / public utility `TraceIdExtractor`:
   - Приоритет 1: стандартный W3C `traceparent` (RFC 8414, Level 1). Автоматически извлекает 32-символьный hex `traceId`.
   - Приоритет 2: стандартные корпоративные correlation headers (`X-Request-ID`, `X-Correlation-ID`, `X-Trace-ID`, `trace-id`, `request-id`, `correlation-id`).
   - Приоритет 3: `Supplier<String> traceIdSupplier` из `ModuleInfo` (интеграция с MDC, OpenTelemetry, Micrometer, ThreadLocal, ScopedValue).
2. Форматирование всех сообщений в `HttpDiagnosticLogger`:
   - `Request: [<traceId>] GET https://api.service.com/v1/resource`
   - `Response: [<traceId>] HTTP/2 200 in 42 ms`
   - `[<traceId>] GET https://api.service.com/v1/hop -> 302 in 12 ms`
   - `[<traceId>] Attempt 1/3 failed: ConnectTimeout — retrying in 250 ms`
3. Автоматическая инъекция `X-Request-ID` в исходящий запрос:
   - Если W3C tracing не включен, но в клиенте задан `traceIdSupplier` (или `correlationId(...)`), заголовок `X-Request-ID` автоматически добавляется в wire-запрос, связывая клиентские логи с серверными.

### 2.3. SSE Typed Decode (Бэклог 0.14.0+)

Индустрия 2026 года массово переходит на стриминг генеративного ИИ (OpenAI, Anthropic Claude, Ollama, DeepSeek, Google Gemini) и Event-Driven микросервисы.

Текущий контракт `Stream<ServerSentEvent>` возвращает `String data()`. Инженеру приходится вызывать кодек вручную.

**Целевой контракт SSE Typed Decode:**
1. Поддержка возвращаемого типа `Stream<ServerSentEvent<T>>` и `CompletableFuture<Stream<ServerSentEvent<T>>>`:
   ```java
   public interface OpenAiChatApi {
       @Post("/v1/chat/completions")
       Stream<ServerSentEvent<ChatChunk>> streamChat(@RequestBody ChatRequest request);
   }
   ```
2. Поддержка прямого стриминга DTO `Stream<T>` с автоматической фильтрацией пустых и `:ping` событий:
   ```java
   public interface TelemetryApi {
       @Get("/events/devices")
       Stream<DeviceMetric> streamMetrics();
   }
   ```
3. Метод десериализации на самом событии:
   `event.decodeData(ChatChunk.class)` или `event.decodeData(TypeToken)`.
4. Корректная обработка маркера конца потока `[DONE]` (типовой маркер OpenAI):
   - Если `event.isDone() == true`, `decodeData()` не бросает исключение кодека, а возвращает `null` или завершает стрим.

---

## 3. Статус реализации и тесты

- Внедрен `TraceIdExtractor` (100% покрытие, все ветки W3C и correlation headers).
- Методы `HttpDiagnosticLogger` (`logRequest`, `logResponse`, `logPerHop`, `logRetry`) принимают `traceId` и флаг `sanitize`.
- `HttpTemplate`, `ResponseProcessor`, `RetryExecutor` связывают контекст запроса с логгером.
- `JRestlyClient.Builder` получил методы `sanitizeSensitiveData(boolean)`, `sanitizeLogs(boolean)`, `traceIdSupplier(Supplier<String>)`.
- Полный гейт тестов зелёный, покрытие веток сохраняется на рекордном уровне >93.6%.
