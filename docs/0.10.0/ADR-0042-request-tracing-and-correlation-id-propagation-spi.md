# ADR-0042: Request Tracing & Correlation ID Propagation SPI

## Status
Accepted

## Context
В современных микросервисных распределенных системах и API-интеграциях критически важно сквозное отслеживание (distributed tracing) и корреляция сетевых запросов.
При обращении к сервисам шлюзы (API Gateway, Nginx, Envoy, Spring Cloud Gateway) и бэкенды требуют наличия уникальных идентификаторов вызовов для аудита, сбора логов и расследования инцидентов:
1. **Correlation ID:** заголовки де-факто стандартов `X-Request-ID` и `X-Correlation-ID`.
2. **W3C Trace Context (RFC 8414 / W3C Recommendation):** стандартизированный заголовок `traceparent` (`00-${traceId}-${spanId}-${traceFlags}`) и сопутствующий `tracestate`.

До настоящего момента в **jRestly** разработчик был вынужден либо вручную объявлять `@RequestHeader("X-Request-ID") String reqId` на каждом методе каждого интерфейса, либо вручную создавать кастомный `RequestInterceptor` с императивным добавлением заголовков. Это создавало бойлерплейт, загрязняло сигнатуры бизнес-методов и увеличивало вероятность ошибок.

При этом необходимо строго сохранить фундаментальные инженерные принципы:
1. **Zero-Dep Core:** отсутствие сторонних зависимостей (OpenTelemetry SDK, Brave, Zipkin, Slf4j MDC). Реализация должна полностью опираться на стандартные возможности Java 21 (`java.util.UUID`, `java.util.HexFormat`, `java.util.function`, `ThreadLocal`).
2. **Прозрачные методы (ADR-0004):** сигнатуры методов контроллеров остаются чистыми бизнес-контрактами без принудительных обёрток.
3. **Декларативность и программная гибкость:** поддержка как аннотации `@Trace` на интерфейсах/методах, так и конфигурации через fluent-билдер клиента.
4. **Контекстное распространение (Context Propagation):** возможность связки с `ThreadLocal` текущего запроса для автоматического проброса входящего correlation ID во все исходящие клиенты.

## Decision

### 1. Zero-Dep W3C Trace Context (`ru.jrestly.tracing.TraceContext`)
Вводим неизменяемый record `TraceContext(String traceId, String spanId, String traceFlags, String traceState)`:
- `traceId`: 16 байт = 32 lowercase hex символа (ненулевой).
- `spanId`: 8 байт = 16 lowercase hex символов (ненулевой).
- `traceFlags`: 1 байт = 2 lowercase hex символа (`"01"` — sampled, `"00"` — not sampled).
- `traceState`: опциональная строка состояний (opaque vendor state).
- Методы:
  - `toTraceparent()`: форматирование в каноничный вид `"00-" + traceId + "-" + spanId + "-" + traceFlags`.
  - `isSampled()`: проверка флага трассировки `(flags & 1) != 0`.
  - `child()`: порождение дочернего контекста (тот же `traceId`, новый случайный `spanId`, те же флаги и состояние).
  - `parse(String traceparent)` / `parse(String traceparent, String tracestate)`: валидирующий парсинг строки `traceparent` с проверкой 4 секций, длины, hex-символов и отсутствия all-zero идентификаторов.
  - `random()`: фабрика создания случайного контекста с флагом `"01"`.

### 2. Zero-Dep ID Generators (`ru.jrestly.tracing.TraceIdGenerator` & `TraceIdGenerators`)
- `@FunctionalInterface public interface TraceIdGenerator { String generate(); }`
- Фабрики:
  - `uuid()`: UUID v4 строковое представление (`UUID.randomUUID().toString()`).
  - `hex(int byteLength)`: генерация hex-строки заданной длины на основе `HexFormat`.
  - `prefixed(String prefix, Supplier<String> supplier)`: добавление префикса (например, `"req-" + uuid`).
  - `threadLocal(ThreadLocal<String> holder, Supplier<String> fallback)`: извлечение ID из `ThreadLocal` с fallback при отсутствии.

### 3. Trace Context Propagation SPI (`ru.jrestly.tracing.TracePropagator`)
- `TraceRequestContext`: контекст исходящего запроса (`controllerClass`, `method`, `url`, `httpMethod`, `existingHeaders`).
- `TracePropagator`: функциональный SPI интерфейс с методом `Map<String, String> inject(TraceRequestContext context)`.
- Фабрики в `TracePropagators`:
  - `correlationId(String headerName, TraceIdGenerator generator)`: добавление correlation ID в заголовок `headerName` (по умолчанию `X-Request-ID`).
  - `w3c()`: генерация W3C TraceContext `traceparent` (и `tracestate`).
  - `w3c(Supplier<TraceContext> supplier)`: инъекция контекста из внешнего поставщика (например, активного OpenTelemetry / Micrometer span).
  - `threadLocal(String headerName, ThreadLocal<String> threadLocal, boolean generateIfMissing)`: динамический проброс из `ThreadLocal`.
  - `composite(TracePropagator... propagators)`: объединение нескольких провайдеров.

### 4. Декларативная эргономика (`ru.jrestly.annotation.Trace`)
- Аннотация `@Trace` на интерфейсах (`ElementType.TYPE`) и методах (`ElementType.METHOD`):
  - `String header() default "X-Request-ID"`: имя заголовка.
  - `boolean w3c() default false`: режим стандарта W3C (`traceparent`).
  - `boolean enabled() default true`: возможность точечного отключения на методе (`@Trace(enabled = false)`).
  - `boolean forceNew() default false`: принудительная генерация нового ID вместо сохранения существующего.
- Валидация в `ControllerValidator`: пустое или blank имя `header()` выбрасывает `ControllerValidationException`.

### 5. Конфигурация в `ModuleInfo` и `JRestlyClient`
- `ModuleInfo`: метод `default TracePropagator getTracePropagator() { return null; }`.
- `SimpleModuleInfo.Builder` и `JRestlyClient.Builder`:
  - `tracePropagator(TracePropagator propagator)`
  - `correlationId(String headerName)`
  - `correlationId(String headerName, Supplier<String> generator)`
  - `correlationId(ThreadLocal<String> threadLocal)`
  - `w3cTracing()` / `w3cTracing(boolean enabled)`

### 6. Пайплайн и приоритеты в `HttpTemplateBuilder`
- Приоритеты разрешения:
  1. Если в запросе пользователь явно передал заголовок через `@RequestHeader` (например `@RequestHeader("X-Request-ID") String id`), переданное значение сохраняется и не перезаписывается (если не задан `forceNew = true`).
  2. Аннотация `@Trace` на методе контроллера переопределяет аннотацию `@Trace` на интерфейсе.
  3. Если аннотация активна, генерируется соответствующий correlation ID или W3C `traceparent`.
  4. Если аннотации нет (или она не переопределяет клиентский уровень), применяется глобальный `TracePropagator` из `ModuleInfo`.

### 7. Эргономика в `ResponseHeaders`
- `headers.requestId()`: возвращает `Optional<String>` из заголовка `X-Request-ID` (или `X-Correlation-ID`).
- `headers.traceparent()`: возвращает `Optional<String>` из заголовка `traceparent`.
- `headers.traceContext()`: парсит `TraceContext` из заголовка `traceparent`.

## Consequences
- **Плюсы:**
  - Полная поддержка распределенной трассировки и correlation ID из коробки без единой внешней зависимости.
  - Декларативная чистота интерфейсов (не нужно прописывать заголовки в сигнатурах методов).
  - Готовые интеграции для работы с `ThreadLocal` и W3C стандартами.
  - Безопасность: явные заголовки пользователя никогда не затираются случайно.
- **Минусы / Ограничения:**
  - Сторонние tracing фреймворки (OpenTelemetry, Micrometer) подключаются через простой однострочный lambda-адаптер `TracePropagator`, сохраняя zero-dep ядро jRestly.
