# ADR-0040: Advanced Exponential Backoff, Jitter Strategies & Retry-After Bounds

## Status
Accepted

## Context
В распределенных микросервисах, высоконагруженных шлюзах и интеграциях со сторонними API сбои (таймауты, сетевые сбои, 429 Too Many Requests, 503 Service Unavailable) неизбежны. В ADR-0008 в jRestly была заложена базовая модель повторных попыток (`RetryPolicy`, аннотация `@Retry`).

Однако в боевой эксплуатации распределенных систем возникают критические проблемы:
1. **Thundering Herd & Retry Storms:**
   Когда множество инстансов сервиса используют одну и ту же стратегию бэкоффа, они синхронизируются и волнами шлют повторные запросы, добивая восстанавливающийся бэкенд.
   Согласно классическому исследованию Marc Brooker в блоге AWS Architecture ("Exponential Backoff And Jitter"), разные профили нагрузки требуют разных алгоритмов рандомизации:
   - `FULL_JITTER`: случайный выбор `[0, bound]` — минимизирует общую конкуренцию.
   - `EQUAL_JITTER`: `bound / 2 + random[0, bound / 2]` — гарантирует разумный базовый интервал ожидания (50%) и сглаживает пики.
   - `DECORRELATED_JITTER`: `min(maxBackoff, random[initialBackoff, previousDelay * 3])` — адаптивный бэкофф, не зависящий жестко от номера попытки.
   - `NO_JITTER` (`NONE`): детерминированная задержка `bound` (необходима для детерминированных тестов, пакетной обработки, фоновых очередей).
2. **Множитель экспоненты (Backoff Multiplier):**
   Ранее множитель был жестко зафиксирован на значении `2.0`. В зависимости от SLA и характера сервиса требуется настраивать плавный рост (например, `1.5`) или ускоренный рост (например, `3.0`).
3. **Защита от зависаний по `Retry-After` (Retry-After Bounds & Opt-Out):**
   - Ранее клиент безусловно брал `Math.max(bound, retryAfter)`. Если сервер возвращал `Retry-After: 86400` (24 часа), клиент зависал на 24 часа в ожидании. Необходим настраиваемый потолок `maxRetryAfter` (по умолчанию ограниченный `maxBackoff`), предотвращающий зависание потоков или ресурсов.
   - Возможность отключить учет заголовка `Retry-After` (`respectRetryAfter = false`) при наличии недоверенных или некорректных промежуточных прокси.

## Decision

1. **Jitter Strategy Enum (`ru.jrestly.http.Jitter`):**
   Ввести перечисление `Jitter` с 4 стратегиями:
   - `FULL` (дефолт, обратная совместимость): `random[0, bound]`.
   - `EQUAL`: `bound / 2 + random[0, bound / 2]`.
   - `DECORRELATED`: `min(maxBackoff, random[initialBackoff, previousDelay * 3])`.
   - `NONE`: строго детерминированный `bound`.

2. **Расширение `RetryPolicy` и `RetryPolicy.Builder`:**
   - Добавить параметры:
     - `jitter(Jitter jitter)` (дефолт: `Jitter.FULL`)
     - `backoffMultiplier(double multiplier)` (дефолт: `2.0`, валидация: `>= 1.0`)
     - `respectRetryAfter(boolean respectRetryAfter)` (дефолт: `true`)
     - `maxRetryAfter(Duration maxRetryAfter)` (дефолт: `null`, при обращении fallback на `maxBackoff`)
   - Геттеры: `getJitter()`, `getBackoffMultiplier()`, `isRespectRetryAfter()`, `getMaxRetryAfter()`.
   - Метод вычисления задержки:
     `long nextDelayMillis(long failedAttempt, long previousDelayMillis, Long retryAfterMillis, Random random)`
     с сохранением сигнатуры `nextDelayMillis(long failedAttempt, Long retryAfterMillis, Random random)` для полной обратной совместимости.
   - Валидация:
     `backoffMultiplier >= 1.0`, `maxRetryAfter >= 0`, `jitter != null`.

3. **Аннотация `@Retry`:**
   - Добавить атрибуты:
     - `Jitter jitter() default Jitter.FULL;`
     - `double multiplier() default 2.0;`
     - `boolean respectRetryAfter() default true;`
     - `long maxRetryAfterMs() default -1;` (-1 означает дефолт `maxBackoffMs`).
   - `HttpTemplateBuilder.resolveRetryPolicy` связывает декларативные атрибуты аннотации с билдером `RetryPolicy`.

4. **Транспортная интеграция в `HttpTemplate`:**
   - Передача `previousDelayMillis` между шагами ретрая в синхронном цикле и асинхронном пайплайне (`CompletableFuture`).
   - Безопасное отсечение `retryAfterMillis` потолком `maxRetryAfter`.

## Consequences
- **Zero-Dependency:** Реализация базируется исключительно на `java.time.Duration`, `java.util.Random`, `java.util.Objects`.
- **Строгая обратная совместимость:** Все существующие тесты (828/828) продолжают проходить без изменений, дефолты полностью идентичны предыдущему поведению.
- **Отказоустойчивость:** Защита от Thundering Herd, retry storms и защита клиентов от зависания из-за некорректных серверных значений `Retry-After`.
