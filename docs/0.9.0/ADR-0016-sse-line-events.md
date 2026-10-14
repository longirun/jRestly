# ADR-0016: Server-Sent Events & line streaming (`Stream<String>`, `Stream<ServerSentEvent>`)

> 2026-10-14 · Proposed · concept step of the "Server-Sent Events & line streaming"
> epic (DRAFT, 6 steps). Implementation follows in Steps 2–5; this ADR is the
> source of truth for the design decisions, the test strategy, and the Javadoc
> contract.

## Context

Following the response byte streaming epic (ADR-0015, `InputStream` return type),
this is the second ergonomics epic for modern APIs — addressing direction 3 from
the maintainer's 2026-10-14 mailbox ("request parameter flexibility, data streaming,
LLM, request pipeline tuning").

Modern LLM endpoints (OpenAI, Anthropic, Ollama, HuggingFace, vLLM) and real-time
APIs stream incremental text using two primary wire formats:
1. **Server-Sent Events (SSE, `text/event-stream`):** standardized by WHATWG/W3C.
   Data chunks are framed with `data: `, event types with `event: `, identifiers
   with `id: `, comments with `: `, and event boundaries with empty lines (`\n\n`).
   LLM providers stream token deltas (e.g., `data: {"choices":[{"delta":{"content":"..."}}]}`)
   terminating with a sentinel like `data: [DONE]`.
2. **Line-delimited streams (e.g., `application/x-ndjson`, CSV, raw logs):**
   individual lines carry standalone records or JSON objects.

### Constraints & Invariants (from Direction 1)

1. **Zero-dep core:** JDK 21 standard library only (`java.util.stream.Stream`,
   `java.io.BufferedReader`, `java.net.http.HttpClient`, `java.nio.charset.Charset`).
   No external reactive stream libraries, no Netty, no custom HTTP parser.
2. **Transparent methods without `Response<T>` (ADR-0004):** the return type
   declares the contract directly:
   - `Stream<String>` declares line streaming (raw text lines).
   - `Stream<ServerSentEvent>` declares structured SSE streaming.
   - `CompletableFuture<Stream<...>>` is the non-blocking async twin.
3. **Anti-combination (ADR-0003):** no heavy event loop, no client-side SSE
   reconnection manager or auto-retry multiplexer; jRestly remains a compact,
   fast, declarative HTTP client.

## Decisions

### Aspect 1: Surface — the return type is the contract

- `Stream<String>` as a return type means: "stream the response body as individual
  lines of text (CRLF / LF stripped)".
- `Stream<ServerSentEvent>` as a return type means: "parse the response body as
  Server-Sent Events per WHATWG SSE specification and yield parsed events".
- `ServerSentEvent` is an immutable record in `ru.jrestly.http`:
  `public record ServerSentEvent(String id, String event, String data, Long retry)`
  with convenient accessors and utility factories (`ofData(String)`).
- Both return types have corresponding async twins:
  `CompletableFuture<Stream<String>>` and `CompletableFuture<Stream<ServerSentEvent>>`.
  The future completes when response headers are received and validated, handing
  the active stream to the caller.
- Neither requires custom marker annotations (`@StreamBody`, `@Sse`); the return
  type is the contract (ADR-0004).

### Aspect 2: Engine — built upon the ADR-0015 stream seam

- Investigation of JDK 21 `HttpResponse.BodyHandlers.ofLines()` reveals that its
  underlying implementation (`ResponseSubscribers.createLineStream`) simply wraps
  the JDK's `HttpResponseInputStream` into:
  `new BufferedReader(new InputStreamReader(stream, charset)).lines().onClose(() -> Utils.close(stream))`.
- Therefore, the HTTP engine uses the existing `WireResponse.stream` (`ofInputStream`)
  seam established in ADR-0015!
- **Benefits of reusing the stream seam:**
  1. `WireResponse` requires no ABI or structural changes — it already carries
     `InputStream stream`.
  2. Intermediate stream disposal (for 3xx redirects and retries) is already
     implemented and battle-tested (`closeIntermediateStream`).
  3. Error paths (`@OnError`, unexpected statuses) consume the raw `InputStream`
     via `consumePayload(stream)` without re-assembling fragmented text lines,
     preserving byte-exact error decoding and raw error bodies.

### Aspect 3: Contract & Codec — JSON by return type

- **Success path:**
  - A `Stream<String>` or `Stream<ServerSentEvent>` return bypasses JSON codec
    deserialization of the entire response body.
  - The caller operates directly on the stream. For JSON lines / SSE data, callers
    can deserialize individual lines or event data payloads using their configured
    codec as needed (e.g., `events.filter(...).map(e -> codec.deserialize(e.data(), Delta.class))`).
- **Error path:**
  - If the server responds with an error status (e.g. 400 Bad Request, 401 Unauthorized,
    429 Rate Limit, 500 Internal Error), `processResponse` consumes the response stream,
    decodes error objects for `@OnError` methods into `HandledException`, or surfaces
    the raw payload in `UnexpectedStatusException`. Error handling remains completely
    uniform across byte, line, and object responses.
- **Retry & Decode probe:**
  - Decode-retry (ADR-0011) treats stream return types as non-decodable at the
    template level (`probePayloadDecode` returns true). Status and transport retries
    operate normally prior to stream consumption.

### Aspect 4: SSE Framing & Parsing

- The SSE parser adheres to the WHATWG Server-Sent Events specification:
  - Lines starting with `data:` (or `data: `) are extracted. Consecutive `data`
    lines in the same event are concatenated with `\n`.
  - Lines starting with `event:` set the event name (defaults to `null` or `"message"`).
  - Lines starting with `id:` set the event ID.
  - Lines starting with `retry:` parse reconnection time in milliseconds.
  - Lines starting with `:` (comments) are ignored (standard keep-alive pings).
  - An empty line dispatches the accumulated event to the stream subscriber.
  - Trailing events without a trailing blank line are dispatched when the stream ends.
- The parser is streaming and lazy: implemented via a custom `Iterator` / `Spliterator`
  over the line stream, consuming lines only as downstream requests events.

### Aspect 5: Lifecycle & Charset

- **Caller lifecycle ownership:**
  `Stream<T>` implements `AutoCloseable`. Closing the stream (via `try-with-resources`
  or `stream.close()`) closes the underlying `BufferedReader` and `InputStream`,
  releasing the underlying network connection.
- **Charset:**
  Parsed from the `Content-Type` header (e.g. `text/event-stream; charset=utf-8`).
  If absent or unparseable, defaults to `StandardCharsets.UTF_8` per RFC 9110 and
  WHATWG SSE specifications.
- **Socket timeout:**
  `HttpRequest.timeout` bounds the time to receive response headers. Once headers
  are processed and the stream is handed to the caller, slow network reads do not
  trigger the connect/header socket timeout, identical to JDK `ofLines()` / `ofInputStream()`.

### Aspect 6: Logging

- Request logging is unchanged.
- Response logging logs status, headers, and elapsed time to headers.
- Payload logging outputs `"streaming lines, Content-Length: N"` or `"streaming events (length unknown)"`
  without reading or buffering the stream, avoiding premature stream consumption.

## Shape (seams touched)

- `ru.jrestly.http.ServerSentEvent`: public immutable record for SSE events.
- `ru.jrestly.http.ServerSentEventParser`: package-private streaming parser
  converting a line stream into an event stream.
- `ru.jrestly.http.HttpTemplateBuilder`:
  - `resolveReturnType`: recognizes `Stream<String>` and `Stream<ServerSentEvent>`
    (and unwraps them from `CompletableFuture`). Sets `streamBody = true`.
- `ru.jrestly.http.HttpTemplate`:
  - `processResponse`: branches on `Stream.class` return types to convert
    `response.stream()` into `Stream<String>` or `Stream<ServerSentEvent>`.
  - Attaches `onClose()` handler to the stream to ensure underlying connection
    closure.
- `ControllerValidator` / README: validation and user documentation for LLM streaming.

## Test strategy (WireMock suite & unit tests)

1. **SSE Parser unit tests:**
   - Single-line `data: hello`.
   - Multi-line data (`data: part1\ndata: part2` -> `"part1\npart2"`).
   - Event types, IDs, retry values.
   - Comments (`: ping`) ignored.
   - LLM-style token stream with `data: [DONE]` sentinel.
   - Malformed lines and blank line handling.
2. **Line streaming unit/wire tests:**
   - UTF-8 and custom charset (`ISO-8859-1`) from `Content-Type`.
   - NDJSON streaming.
   - Proper `onClose()` closing the underlying `InputStream`.
3. **End-to-end integration tests with WireMock:**
   - Mock LLM streaming endpoint emitting SSE events.
   - Sync `Stream<ServerSentEvent>` and `Stream<String>`.
   - Async `CompletableFuture<Stream<ServerSentEvent>>`.
   - Error status (400, 500) consuming stream and throwing `HandledException` / `UnexpectedStatusException`.
   - Redirect & retry behavior with stream disposal.

## Consequences

- Direct, first-class support for LLM APIs (OpenAI, Anthropic, Ollama) and real-time
  event streams in Java 21 without external dependencies.
- Retains zero-dependency core and transparent API principles.
- The 0.9.0 cycle advances with a unified streaming story (byte streaming in ADR-0015,
  line/event streaming in ADR-0016).
