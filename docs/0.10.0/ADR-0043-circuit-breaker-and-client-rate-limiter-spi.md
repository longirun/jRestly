# ADR-0043: Circuit Breaker & Client Rate Limiter SPI

## Status
Accepted

## Context
В современных распределенных системах, при вызовах внешних микросервисов и сторонних API (платежные шлюзы, LLM-провайдеры, партнерские REST сервисы), надежность клиентской стороны (client-side resilience) имеет критическое значение:
1. **Каскадные сбои (Cascading Failures):** Если удаленный сервис падает или испытывает перегрузку, клиент не должен продолжать непрерывную бомбардировку запросами, забивая сокеты, пулы потоков и память. Паттерн **Circuit Breaker** отслеживает процент сбоев в скользящем окне вызовов и при превышении порога переводит цепь в состояние `OPEN`, немедленно прерывая дальнейшие вызовы исключением `CircuitBreakerOpenException` без реальной отправки в сеть, экономя системные ресурсы до момента перехода в пробное состояние `HALF_OPEN`.
2. **Предотвращение 429 Too Many Requests (Client-Side Rate Limiter):** Большинство публичных и внутренних API накладывают жесткие лимиты на частоту вызовов (RPS / RPM). Без локального троттлинга клиент получает постоянные HTTP 429 Too Many Requests и временные баны. Паттерн **Rate Limiter** (на базе алгоритма Token Bucket) позволяет регулировать частоту запросов на стороне клиента: либо плавно задерживать поток / future на время ожидания следующего доступного токена, либо мгновенно отсекать превышение с `RateLimitExceededException` (fail-fast).
3. **Zero-Dep Core и прозрачные методы:** Никаких тяжелых внешних библиотек (Resilience4j, Bucket4j, Sentinel). Реализация должна полностью опираться на стандартные возможности Java 21 (`java.util.concurrent`, `java.time`, `java.util.concurrent.atomic`). Никаких принудительных обёрток возвращаемых значений вроде `Response<T>` — строго сохраняются инварианты ADR-0003 и ADR-0004.

## Decision

### 1. Zero-Dep Circuit Breaker (`ru.jrestly.resilience.CircuitBreaker`)
Вводим интерфейс `ru.jrestly.resilience.CircuitBreaker` со следующими возможностями:
- Состояния: `State { CLOSED, OPEN, HALF_OPEN }`.
- Методы управления и проверки:
  - `String name()`: имя экземпляра предохранителя.
  - `State getState()`: текущее состояние.
  - `boolean tryAcquirePermission()`: неблокирующая попытка получить разрешение на выполнение запроса.
  - `void acquirePermission() throws CircuitBreakerOpenException`: проверка разрешения с выбросом исключения при `OPEN`.
  - `void recordSuccess(long durationNanos)`: учет успешного вызова.
  - `void recordFailure(long durationNanos, Throwable throwable)`: учет сбойного вызова.
  - `CircuitBreakerMetrics metrics()`: снимок метрик (`totalCalls`, `successfulCalls`, `failedCalls`, `failureRate`, `state`).
  - `void transitionToClosedState()`, `transitionToOpenState()`, `transitionToHalfOpenState()`: ручное или программное переключение состояний.
- Конфигурация `CircuitBreakerConfig`:
  - `float failureRateThreshold` (процент сбоев для размыкания цепи, дефолт `50.0f`, диапазон (0, 100]).
  - `int minimumNumberOfCalls` (минимальное число вызовов в окне для расчета процента, дефолт `10`).
  - `int slidingWindowSize` (размер скользящего кольцевого буфера, дефолт `20`).
  - `Duration waitDurationInOpenState` (время нахождения в состоянии `OPEN` до перехода в `HALF_OPEN`, дефолт `Duration.ofSeconds(5)`).
  - `int permittedNumberOfCallsInHalfOpenState` (число пробных вызовов в `HALF_OPEN`, дефолт `3`).
  - `Predicate<Throwable> recordException` (предикат классификации сбоев: по умолчанию сетевые сбои `IOException`, `TimeoutException`, а также статусы 5xx `ServerErrorException` и `UnexpectedStatusException` со статусом >= 500 считаются сбоями; клиентские 4xx `ClientErrorException` сбоем сервера не являются).
- Исключение `CircuitBreakerOpenException`:
  - Наследник `RuntimeException`. Содержит `circuitBreakerName()` и `remainingWaitDuration()`.
- Реестр `CircuitBreakerRegistry`:
  - Потокобезопасный `ConcurrentHashMap` для совместного использования одного предохранителя несколькими методами или клиентами по имени (`getOrCreate(name, config)`).

### 2. Zero-Dep Client-Side Rate Limiter (`ru.jrestly.resilience.RateLimiter`)
Вводим интерфейс `ru.jrestly.resilience.RateLimiter`:
- Методы:
  - `String name()`: имя ограничителя частоты.
  - `boolean tryAcquire()`: попытка получить токен без ожидания.
  - `boolean tryAcquire(Duration timeout)`: попытка получить токен с ожиданием до `timeout`.
  - `void acquire() throws RateLimitExceededException`: получение токена в пределах настроенного `timeoutDuration` с выбросом исключения при исчерпании лимита.
  - `RateLimiterConfig config()`: конфигурация.
  - `RateLimiterMetrics metrics()`: снимок метрик (`availablePermissions`, `numberOfWaitingThreads`).
- Конфигурация `RateLimiterConfig`:
  - `int limitForPeriod`: количество разрешений за период (дефолт `10`).
  - `Duration limitRefreshPeriod`: период пополнения (дефолт `Duration.ofSeconds(1)`).
  - `Duration timeoutDuration`: предельное время ожидания токена (дефолт `Duration.ZERO` — мгновенный fail-fast).
- Алгоритм:
  - Zero-dep потокобезопасный Token Bucket с атомарным расчетом доступных токенов на основе монотонного системного времени (`System.nanoTime()`).
- Исключение `RateLimitExceededException`:
  - Наследник `RuntimeException`. Содержит `rateLimiterName()`.
- Реестр `RateLimiterRegistry`:
  - Потокобезопасный реестр по имени (`getOrCreate(name, config)`).

### 3. Декларативная эргономика
- Аннотация `@CircuitBreaker` (`ElementType.METHOD`, `ElementType.TYPE`):
  - `String name() default "default";`
  - `float failureRateThreshold() default 50.0f;`
  - `int minimumNumberOfCalls() default 10;`
  - `int slidingWindowSize() default 20;`
  - `long waitDurationMs() default 5000;`
  - `int permittedNumberOfCallsInHalfOpenState() default 3;`
  - `boolean enabled() default true;` // точечный opt-out на методе
- Аннотация `@RateLimiter` (`ElementType.METHOD`, `ElementType.TYPE`):
  - `String name() default "default";`
  - `int limitForPeriod() default 10;`
  - `long limitRefreshPeriodMs() default 1000;`
  - `long timeoutDurationMs() default 0;`
  - `boolean enabled() default true;`

### 4. Конфигурация модулей и клиентов
- В `ModuleInfo`:
  - `default CircuitBreaker getCircuitBreaker() { return null; }`
  - `default CircuitBreakerRegistry getCircuitBreakerRegistry() { return null; }`
  - `default RateLimiter getRateLimiter() { return null; }`
  - `default RateLimiterRegistry getRateLimiterRegistry() { return null; }`
- В `SimpleModuleInfo.Builder` и `JRestlyClient.Builder`:
  - `circuitBreaker(CircuitBreaker)`
  - `circuitBreakerRegistry(CircuitBreakerRegistry)`
  - `rateLimiter(RateLimiter)`
  - `rateLimiterRegistry(RateLimiterRegistry)`

### 5. Валидация в `ControllerValidator`
- `@CircuitBreaker`:
  - `!name.isBlank()`
  - `failureRateThreshold > 0 && failureRateThreshold <= 100`
  - `minimumNumberOfCalls > 0`
  - `slidingWindowSize >= minimumNumberOfCalls`
  - `waitDurationMs > 0`
  - `permittedNumberOfCallsInHalfOpenState > 0`
- `@RateLimiter`:
  - `!name.isBlank()`
  - `limitForPeriod > 0`
  - `limitRefreshPeriodMs > 0`
  - `timeoutDurationMs >= 0`

### 6. Транспортная интеграция в `HttpTemplateBuilder` и `HttpTemplate`
- Приоритеты разрешения: Метод > Sub-интерфейс > Super-интерфейс > Клиент.
- Перед отправкой запроса (синхронно и асинхронно):
  1. `rateLimiter.acquire()`: при исчерпании лимита выбрасывается `RateLimitExceededException` (или future завершается с ним). Запрос в сеть не отправляется.
  2. `circuitBreaker.acquirePermission()`: при состоянии `OPEN` выбрасывается `CircuitBreakerOpenException` (или future завершается с ним). Запрос в сеть не отправляется.
- После выполнения запроса:
  - При успехе: `circuitBreaker.recordSuccess(duration)`.
  - При ошибке: проверяется `config.recordException().test(throwable)`, и при положительном результате фиксируется `circuitBreaker.recordFailure(duration, throwable)`.

## Consequences
- **Плюсы:**
  - Полная защита клиентских приложений от каскадных сбоев и перегрузок внешних API.
  - Zero-dep архитектура: ровно 0 сторонних зависимостей.
  - Идеальная поддержка как синхронных вызовов, так и `CompletableFuture<T>`.
  - Поддержка декларативного стиля через аннотации `@CircuitBreaker` и `@RateLimiter`, а также программного через fluent builder клиента.
  - Возможность шаринга инстансов по имени через реестры `CircuitBreakerRegistry` и `RateLimiterRegistry`.
- **Минусы / Ограничения:**
  - Не заменяет распределенный кластерный Rate Limiter (Redis / API Gateway), фокус — на локальной защите микросервиса-клиента.
