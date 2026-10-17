# ADR-0035: Outgoing Request Body Streaming & Request Body Validation

## Status
Accepted

## Context
1. **Symmetric Streaming Architecture:**
   - In sessions #107–#109 (ADR-0015, ADR-0016), jRestly established reactive response streaming (`Flow.Publisher<T>`, `Flow.Publisher<String>`, `Flow.Publisher<ServerSentEvent<T>>`, `InputStream`, `Stream<String>`).
   - However, outgoing request bodies (`@RequestBody`) were previously restricted to in-memory buffers: `byte[]`, `String`, or POJOs serialized through the JSON codec.
   - For modern APIs (large file uploads, binary transfers, media ingestion, chunked JSON streaming, NDJSON, and AI/LLM token streaming), buffering entire payloads in memory causes high heap pressure, potential `OutOfMemoryError`, and prevents true stream pipelining.
2. **Standard Java 21 Primitives:**
   - Java 9+ `java.util.concurrent.Flow.Publisher<ByteBuffer>` and Java 11+ `java.net.http.HttpRequest.BodyPublishers` provide native reactive publishing primitives:
     - `HttpRequest.BodyPublishers.fromPublisher(Flow.Publisher<? extends ByteBuffer>)`
     - `HttpRequest.BodyPublishers.ofInputStream(Supplier<? extends InputStream>)`
     - `HttpRequest.BodyPublishers.ofFile(Path)`
3. **Resilience & Re-sending:**
   - Single-use streams (such as raw `InputStream` or single-subscription `Publisher`) cannot be consumed multiple times.
   - For requests governed by `@Retry` policies (ADR-0009) or redirect hops (307/308 Temporary/Permanent Redirect), re-sending requires a factory mechanism: `Supplier<? extends InputStream>`, `Supplier<? extends Flow.Publisher<ByteBuffer>>`, or `Supplier<? extends HttpRequest.BodyPublisher>`.
4. **Validation & Fail-Fast Integrity:**
   - `ControllerValidator` previously allowed multiple `@RequestBody` annotations on a single method or combining `@RequestBody` with other HTTP parameter annotations (e.g., `@RequestParam`).
   - A `@Get` method with `@RequestBody` was silently ignored at runtime instead of being caught up front.

## Decision
1. **Support Streaming `@RequestBody` Types:**
   - Extend `HttpTemplateBuilder` and `WireRequest` to support streaming request bodies without eager memory buffering:
     - `Flow.Publisher<ByteBuffer>` / `Flow.Publisher<? extends ByteBuffer>`: native reactive streaming chunks via `BodyPublishers.fromPublisher(...)`.
     - `Flow.Publisher<String>`: text/string streaming chunks automatically adapted to `ByteBuffer` publishers with UTF-8 encoding.
     - `HttpRequest.BodyPublisher`: direct use of pre-constructed or custom standard body publishers.
     - `InputStream`: stream via `BodyPublishers.ofInputStream(() -> stream)`.
     - `Supplier<? extends InputStream>`: reusable stream supplier for retry/redirect resilience.
     - `Supplier<? extends HttpRequest.BodyPublisher>` / `Supplier<? extends Flow.Publisher<ByteBuffer>>`: reusable publisher suppliers.
     - `Path` and `File`: direct zero-copy file streaming via `BodyPublishers.ofFile(path)`.
2. **Default Content-Type Semantics:**
   - If `@ContentType` or `@Post(contentType = ...)` / `@Put(contentType = ...)` is explicitly specified, it is honored.
   - If not specified:
     - For streaming binary/reactive types (`Flow.Publisher`, `InputStream`, `Supplier`, `Path`, `File`, `HttpRequest.BodyPublisher`), default to `"application/octet-stream"`.
     - For conventional POJOs, `String`, and `byte[]`, maintain backward-compatible default (`requestType.mime()`, typically `"application/json"`).
3. **Wire Model & Builder Integration:**
   - Enhance `WireRequest` with `Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier`.
   - Maintain backward-compatible constructors and builder methods (`withBodyPublisher`, `withBodyPublisherSupplier`, `bodyPublisherSupplier`).
   - In `HttpTemplate.logRequest`, avoid consuming live streams; log `<streaming body>` with content length when available.
   - Seamlessly integrate with `UploadProgressListener` (ADR-0030) using existing `ProgressBodyPublisher`.
4. **Fail-Fast Validation in `ControllerValidator`:**
   - Ensure at most one `@RequestBody` parameter per method.
   - Prohibit combining `@RequestBody` with `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, or `@MultipartFormFile` on the same parameter.
   - Prohibit `@RequestBody` on `@Get` methods.

## Consequences
- **Positive:**
  - Full end-to-end streaming symmetry: both request body and response body can be streamed reactively using standard Java 21 `Flow.Publisher`.
  - Zero heap footprint when streaming large files and chunked data.
  - Full compatibility with `UploadProgressListener`, `@Retry`, and redirects.
  - Zero external dependencies (Zero-Dep Core invariant preserved).
  - Clear, fail-fast controller validation diagnostics.
- **Negative:**
  - Raw `InputStream` instances without a `Supplier` cannot be re-read on retries; documentation advises using `Supplier<InputStream>` or `Path` when retries are enabled.

## Amendment #198

Audit "ADR against code", 17th slice — light **ADR-0035** (outgoing request body streaming), the last unwalked ADR of the accepted-ADR fiber.

**Verified against code, no divergence — the slice is clean (third after #192 and #197):**

- **§1 types** — all eight are wired through `HttpTemplateBuilder.createJsonBodyPayload`: `Flow.Publisher<ByteBuffer>`/`Flow.Publisher<String>` (adapted to `ByteBuffer` via `adaptToByteBufferPublisher`), `HttpRequest.BodyPublisher`, `InputStream`, `Supplier<InputStream>`, `Supplier<BodyPublisher>`, `Supplier<Flow.Publisher<ByteBuffer>>`, `Path`, `File`.
- **§2 default Content-Type** — an explicit `@ContentType`/`@RequestHeader("Content-Type")` is honored; a streaming/reactive `body` falls back to `application/octet-stream`, while `String`/`byte[]`/POJO keep `requestType.mime()`.
- **§3 wire model & builder** — `WireRequest.bodyPublisherSupplier` + the 7-arg backward-compatible constructor + `withBodyPublisher`/`withBodyPublisherSupplier`/`Builder` carry the supplier across retries and 307/308 redirect hops (`HttpTemplate.nextHop` preserves it); `logRequest` emits `<streaming body>` without touching the live stream.
- **§4 fail-fast validation** — `ControllerValidator` rejects multiple `@RequestBody`, any combination with `@RequestParam`/`@PathVariable`/`@RequestHeader`/`@Cookie`/`@MultipartFormFile`, and `@RequestBody` on `@Get` (covered by `RequestBodyValidationTest`).

**Untested normative seams locked by five guard tests (green on the current code; the sensitivity is structural):**

1. `Supplier<HttpRequest.BodyPublisher>` is re-supplied once per transmission across `@Retry` (`RequestBodyStreamingWireMockTest.testUploadSupplierBodyPublisherAcrossRetry`).
2. `Supplier<Flow.Publisher<ByteBuffer>>` yields a fresh publisher per call (`testUploadSupplierFlowPublisher`).
3. A `307` redirect replays a streaming `Supplier<InputStream>` body on the second hop (`testRedirect307ReplaysStreamingSupplierBody`).
4. A `File` body streams with a known `Content-Length` and defaults to `application/octet-stream` (`testUploadFileDefaultsToOctetStream`).
5. `HttpTemplate.logRequest` logs the `<streaming body>` placeholder and never invokes the publisher supplier (`HttpTemplateLoggingTest.streamingBodyLogsPlaceholderWithoutConsumingSupplier`).

**Wording drifts (documented here, no code change):**

- §2 references `@Post(contentType = ...)` / `@Put(contentType = ...)`, but the verb annotations expose a `requestType` attribute (a `RequestType` enum); the raw media type is declared through `@ContentType`. The sentence should name `requestType`/`@ContentType`.
- §3 says log `<streaming body>` "with content length when available"; the length is only knowable by invoking the supplier, which the same sentence forbids. The placeholder is logged alone — kept as-is.

**Non-goal:** a `null` streaming argument (e.g. a `null` `Flow.Publisher`) has no runtime type, so the Content-Type falls back to `requestType.mime()` (`application/json`) instead of `application/octet-stream`; the declared parameter type is not consulted for the null case. No body is sent either way, so both media types are defensible — left unchanged.
