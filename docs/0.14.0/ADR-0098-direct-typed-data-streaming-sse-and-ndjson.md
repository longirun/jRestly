# ADR-0098: Direct Typed Data Streaming (SSE & NDJSON) — Extending ADR-0004 to Streaming

> 2026-10-21 · Accepted · Implemented in Session #377 · Target Release: 0.14.0

## Context

In ADR-0004 (*Transparent method calls as the API contract*), jRestly established its core philosophy:
> *"The method return type IS the contract: the deserialized body type, `String` for raw payloads, `void` for none. No `Response<T>` — ever."*

While ADR-0016 introduced raw line (`Stream<String>`) and WHATWG SSE (`Stream<ServerSentEvent>`) streaming, and ADR-0097 added `Stream<TypedServerSentEvent<T>>`, the user interface was still forced to declare wrapper types when consuming structured streaming data (such as LLM tokens or real-time event feeds). To access the actual payload, callers were obliged to unwrap envelopes manually via `.map(TypedServerSentEvent::data)` or deal with raw strings.

Furthermore, if a developer declared a direct stream of business models such as `Stream<ChatChunk>` or `Stream<StockPrice>`, the library historically fell back to non-streaming buffered decoding, reading the whole body into memory or failing when streaming sockets remained open indefinitely.

## Decision

1. **Direct `Stream<T>` Declaration ("Return type is the contract"):**
   Methods in controller interfaces can now directly declare `Stream<T>` (as well as `CompletableFuture<Stream<T>>` and `Optional<Stream<T>>`), where `T` is any business DTO, Java record, collection, or generic type:
   ```java
   public interface AiChatClient {
       @Post("/v1/chat/completions")
       @Header("Accept: text/event-stream")
       Stream<ChatChunk> streamChat(@RequestBody ChatRequest request);

       @Post("/v1/chat/completions")
       CompletableFuture<Stream<ChatChunk>> streamChatAsync(@RequestBody ChatRequest request);

       @Get("/v1/stock/feed")
       Stream<StockQuote> streamQuotes();
   }
   ```

2. **Automatic Protocol Adaptation (SSE vs. NDJSON):**
   - **Server-Sent Events (`text/event-stream` or default):** The stream is decoded via `ServerSentEventParser`. Each event's `data:` field is deserialized into `T` via the configured `PayloadCodec`.
   - **Stream Termination (`[DONE]` standard):** If a stream emits the standard LLM termination marker `data: [DONE]` (OpenAI, Anthropic, Ollama, DeepSeek, vLLM), `takeWhile(raw -> !raw.isDone())` gracefully terminates the stream without throwing JSON parse exceptions.
   - **Keep-Alive & Ping Immunity:** SSE comment lines (`:keepalive`) and heartbeat events with empty data are omitted, ensuring the caller receives only valid instances of `T`.
   - **NDJSON / JSON Lines (`application/x-ndjson`, `application/jsonlines`, `application/stream+json`):** The stream is decoded line by line, skipping blank lines and deserializing each non-empty line via `PayloadCodec`.

3. **Lifecycle & Socket Hygiene:**
   Closing the returned `Stream<T>` (via try-with-resources `try (Stream<ChatChunk> s = api.streamChat(...))`) cascades directly through `Stream.onClose` to close the underlying network `InputStream` and release HTTP socket connections immediately.

4. **Error Handling & Resilience:**
   Error statuses continue to adhere strictly to ADR-0004: non-2xx responses (outside `@ExpectStatus`) do not return open streams. The error body is consumed and parsed into `@OnError` handles or wrapped into `UnexpectedStatusException`. Missing resources (404/204) for `Optional<Stream<T>>` yield `Optional.empty()`.

5. **Direct Data Reconnection Utilities:**
   `TypedServerSentEvent.reconnectingData(...)` and `ServerSentEvent.reconnectingData(...)` provide built-in reconnection helpers yielding clean streams of `T` with `Last-Event-ID` propagation and exponential backoff.

## Consequences

- **Ergonomics:** Complete elimination of boilerplate envelope unwrapping. Controllers stay minimal and expressive.
- **Coverage & Reliability:**
  - `ResponseBody`: 100% branch coverage (176/176 covered, 0 missed branches).
  - `ResponseProcessor`: 100% branch coverage (90/90 covered, 0 missed branches).
  - Total test suite expanded to **2,189 / 2,189** green tests across all 8 modules.
  - Overall branch coverage reached an all-time record of **97.51%** with missed branches dropping to **113**.
