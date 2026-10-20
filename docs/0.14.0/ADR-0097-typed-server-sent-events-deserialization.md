# ADR-0097: Typed Server-Sent Events (SSE) Deserialization & AI/LLM Streaming

- **Status:** Accepted
- **Date:** 2026-10-21
- **Authors:** Autonomous Senior Java Core Engineer & Architect
- **Target Version:** 0.14.0
- **Scope:** Server-Sent Events (SSE), AI/LLM Token Streaming, WHATWG HTML EventSource, Pluggable Payload Codecs, Zero-Dep Core

---

## 1. Context & Motivation

С развитием генеративного искусственного интеллекта и стриминговых API (OpenAI, Anthropic, Ollama, DeepSeek, vLLM) протокол **Server-Sent Events (WHATWG HTML EventSource)** стал промышленным стандартом реального времени для передачи токенов и чанков модели.

В jRestly базовая поддержка SSE была реализована в ADR-0016 (`Stream<ServerSentEvent>`) и расширена в ADR-0059 (реконнекты, `Last-Event-ID`, виртуальный watchdog `jrestly-sse-stall-guard`).
Однако `ServerSentEvent` оставался чисто строковым: `String data()`.

Разработчикам приходилось вручную вызывать JSON-кодек на каждом событии:
```java
ChatChunk chunk = codec.deserialize(event.data(), ChatChunk.class);
```
При этом возникали две критические проблемы:
1. **Болезнь терминатора `[DONE]`:** По стандарту стриминга OpenAI/Anthropic/Ollama финальное событие передается как `data: [DONE]`. Попытка скормить эту строку в стандартный JSON-парсер (Jackson/Gson) выбрасывала `JsonParseException: Unrecognized token '[DONE]'`. Разработчикам приходилось писать костыли `if (!"[DONE]".equals(event.data()))`.
2. **Многословность и дублирование кода:** Для каждого микросервиса требовалось писать адаптеры и оборачивать стримы вручную.

Антон сформулировал приоритетную задачу:
> *«Дополнительно: добавь в беклог SSE typed decode.*
> *Да и вообще надо как следует проработать SSE - на сейчас это очень важная тема для индустрии.»*

---

## 2. Архитектурные решения

### 2.1. 100% обратная совместимость и безопасность типов

Попытка превратить существующий `ServerSentEvent` в generic `ServerSentEvent<T>` привела бы к катастрофическому breaking change:
- В байт-коде изменилась бы сигнатура метода `data()` с `()Ljava/lang/String;` на `()Ljava/lang/Object;` (`NoSuchMethodError` у скомпилированных клиентов);
- Во всех проектах с включенным компиляторным флагом `-Werror` использование `Stream<ServerSentEvent>` вызвало бы фатальную ошибку `[rawtypes]`.

**Решение:**
1. Существующий `ServerSentEvent(String id, String event, String data, Long retry)` остается **полностью неизменным** по сигнатуре и сохраняет свой строковый контракт WHATWG.
2. Введен новый специализированный неизменяемый тип:
   ```java
   public record TypedServerSentEvent<T>(
           String id,
           String event,
           T data,
           Long retry,
           String rawData
   ) implements SseEventContract
   ```
3. `ServerSentEvent` дополнен удобными методами конвертации и извлечения:
   - `<T> TypedServerSentEvent<T> toTyped(Class<T> type, PayloadCodec codec)`
   - `<T> TypedServerSentEvent<T> toTyped(Type type, PayloadCodec codec)`
   - `<T> T dataAs(Class<T> type, PayloadCodec codec)`
   - `<T> T dataAs(Type type, PayloadCodec codec)`

### 2.2. Автоматическая защита от маркера `[DONE]` (LLM Stream Terminal)

И `ServerSentEvent.toTyped()`, и декларативный стриминг в `ResponseProcessor` обрабатывают `[DONE]` «из коробки»:
- При поступлении `data: [DONE]` JSON-кодек **не вызывается**;
- Создается `TypedServerSentEvent` с `data = null`, `rawData = "[DONE]"` и `isDone() == true`;
- Пользовательский код безопасно использует стандартные предикаты потоков:
  ```java
  events.takeWhile(e -> !e.isDone()).forEach(...)
  ```

### 2.3. Декларативная привязка в HTTP-интерфейсах

Разработчик может напрямую объявлять типизированный стрим в декларативном интерфейсе клиента:
```java
public interface OpenAiChatApi {

    @Post("/v1/chat/completions")
    Stream<TypedServerSentEvent<ChatChunk>> streamChat(@RequestBody ChatRequest request);

    @Post("/v1/chat/completions")
    CompletableFuture<Stream<TypedServerSentEvent<ChatChunk>>> streamChatAsync(@RequestBody ChatRequest request);

    @Get("/v1/events/optional")
    Optional<Stream<TypedServerSentEvent<ChatChunk>>> streamOptional();
}
```

Конвейер исполнения:
1. `MethodMetadataParser` распознает `Stream<TypedServerSentEvent<T>>` и включает потоковый режим `streamBody = true` (чтение через `BodyHandlers.ofInputStream()` без буферизации всего ответа);
2. `ResponseProcessor` извлекает тип `T`, получает настроенный `PayloadCodec` клиента и создает ленивый маппинг;
3. Сетевой сокет привязывается к хуку `stream.onClose(...)`, гарантируя немедленное закрытие соединения при выходе из `try-with-resources`.

### 2.4. Единый контракт устойчивости (`SseEventContract`)

Создан внутренний интерфейс `SseEventContract`:
```java
interface SseEventContract {
    String id();
    Long retry();
    boolean isDone();
}
```
Благодаря этому механизм реконнекта с экспоненциальным backoff, пробросом `Last-Event-ID` и виртуальным watchdog `jrestly-sse-stall-guard` (`ServerSentEventReconnector<E extends SseEventContract>`) работает **одинаково надежно и для строковых, и для типизированных событий** без единой строки дублирования логики!

---

## 3. Результаты и верификация

1. **Тестирование:**
   - Новый модульный сьют `TypedServerSentEventTest` (фабрики, `isDone()`, `map()`, `parse()`, `toTyped()`, `dataAs()`, null-checks, `reconnecting()`);
   - Дополнен `HttpTemplateLineAndEventStreamTest` (проверка детекции возвращаемых типов билдером);
   - Дополнен `ResponseProcessorTest` (декодирование, отсутствие кодека, Optional 404/204 закрытие стрима);
   - Дополнен сквозной WireMock-сьют `ServerSentEventsAndLineStreamingTest` (синхронный и асинхронный LLM streaming, `@OnError` с HandledException).
2. **Метрики качества:**
   - Полный гейт: **2,114 / 2,114** тестов green во всех 8 модулях, 0 failures, 0 errors;
   - `TypedServerSentEvent`: **100% instructions, 100% branches, 100% lines, 100% complexity, 0 missed branches**;
   - Классовое покрытие по библиотеке: **100%** (168 / 168 классов);
   - Javadoc во всех модулях абсолютно чист под `-Werror`.
