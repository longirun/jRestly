# ADR-0021: Transparent response decompression (GZIP & Deflate)

> 2026-10-15 · **Accepted** · Concept, implementation, and comprehensive unit + WireMock tests verified in session #99.
> Addresses candidate #1 from the active zone of DRAFT.md and the maintainer's 2026-10-14
> mailbox guidance on library maturity vectors ("прозрачный GZIP / Deflate").

## Context

In real-world web services, microservice APIs, and high-throughput HTTP integrations, servers
frequently compress response payloads using `gzip` or `deflate` encoding to significantly
reduce network transfer volume and latency.

The HTTP/1.1 and HTTP/2 standards define content negotiation via:
- Client request header: `Accept-Encoding: gzip, deflate`
- Server response header: `Content-Encoding: gzip` (or `x-gzip`, `deflate`)

### The Problem in JDK `HttpClient`

The standard JDK 21 `java.net.http.HttpClient` deliberately does **not** decompress responses
automatically (documented in JDK-8211339). When a remote server returns a compressed payload:
1. `HttpResponse.BodyHandlers.ofByteArray()` yields compressed binary bytes.
2. The JSON codec (Jackson/Gson/Moshi) immediately fails with a deserialization error (e.g.
   `JsonParseException: Illegal character / not valid JSON`) or returns gibberish if the return
   type is `String`.
3. Error handlers (`@OnError`, `UnexpectedStatusException`) fail to extract readable error
   details from compressed 4xx/5xx responses.
4. Streaming responses (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`) return
   compressed streams requiring manual unwrapping by the caller.

### Constraints & Invariants

1. **Zero-dep core:** Rely strictly on Java 21 standard library classes from `java.util.zip`
   (`GZIPInputStream`, `InflaterInputStream`, `Inflater`) present in `java.base`. No external
   compression libraries (e.g. Apache Commons Compress, Brotli4j).
2. **Transparent & declarative:** Controller interfaces remain clean without any `Response<T>`
   wrappers (ADR-0004). Downstream codecs, interceptors, and error handlers receive transparently
   decompressed data.
3. **Opt-out and custom header respect:** Decompression is enabled by default for developer
   ergonomics, but can be disabled via `ModuleInfo` / client builder (`decompression(false)`).
   If the caller supplies an explicit `Accept-Encoding` header (e.g. `identity`), jRestly respects
   it and does not overwrite it.
4. **Header hygiene (RFC 9110 / OkHttp model):** When a response is decompressed, the
   transport-specific headers `Content-Encoding` and `Content-Length` are stripped from the
   resulting `WireResponse` so interceptors and consumers do not observe misleading metadata.

---

## Decisions

### 1. Configuration surface: `ModuleInfo`, `SimpleModuleInfo`, `JRestlyClient`

We add a default method to `ru.jrestly.ModuleInfo`:

```java
/**
 * Whether transparent response decompression for {@code gzip} and
 * {@code deflate} content encodings is enabled (ADR-0021). Enabled by
 * default ({@code true}). When enabled, requests automatically include
 * {@code Accept-Encoding: gzip, deflate} unless an explicit
 * {@code Accept-Encoding} header is already declared, and responses with
 * matching {@code Content-Encoding} are transparently decompressed.
 *
 * @return {@code true} if decompression is enabled, {@code false} otherwise
 */
default boolean isDecompressionEnabled() {
    return true;
}
```

In `SimpleModuleInfo.Builder`:
```java
public Builder decompression(boolean enabled) {
    this.decompressionEnabled = enabled;
    return this;
}
```

In `JRestlyClient.Builder`:
```java
public Builder decompression(boolean enabled) {
    moduleInfoBuilder.decompression(enabled);
    return this;
}
```

### 2. Request header negotiation: `Accept-Encoding: gzip, deflate`

In `HttpTemplateBuilder.createHeaders()`:
When `moduleInfo.isDecompressionEnabled()` is `true`, the builder inspects all assembled headers
(including auth headers, class-level, method-level, and parameter-level `@RequestHeader` headers).
If no header with name `Accept-Encoding` (case-insensitive) is present, the builder appends:
```java
result.add(new Header("Accept-Encoding", "gzip, deflate"));
```
If an explicit `Accept-Encoding` is provided by the caller, it is left untouched.

### 3. Response decompression engine: `HttpTemplate.handleDecompression`

Inside `HttpTemplate`, both synchronous `send(request)` and asynchronous `sendAsync(request)` pass
the raw `WireResponse` through `handleDecompression(response)` before passing it to retries,
logging, interceptors, or payload decoding:

1. **Check enablement:** If `!moduleInfo.isDecompressionEnabled()`, return untouched.
2. **Inspect `Content-Encoding`:** Search headers for case-insensitive `Content-Encoding`. If
   absent or equal to `"identity"`, return untouched.
3. **Supported algorithms:**
   - `"gzip"` / `"x-gzip"`: decompressed via `GZIPInputStream`.
   - `"deflate"`: decompressed via `InflaterInputStream` (with raw RFC 1951 fallback if zlib
     header is missing).
   - Unknown encodings (e.g. `br`, `zstd`): returned untouched to avoid corrupting payloads.
4. **Header hygiene:** Create a sanitized header list with `Content-Encoding` and `Content-Length`
   removed.
5. **Payload handling:**
   - **Byte array payloads (`response.body()`):** If body is empty (0 bytes), return empty.
     Otherwise, decompress bytes via in-memory stream into decompressed `byte[]`.
   - **Streaming payloads (`response.stream()`):** Wrap the live stream into
     `new GZIPInputStream(stream)` or `new InflaterInputStream(stream)`. Closing the wrapped
     stream properly closes the underlying network stream.

### 4. Error path & Status check transparency

Because decompression occurs in `send` / `sendAsync`:
- HTTP 4xx / 5xx responses carrying compressed JSON error payloads are decompressed before
  `@OnError` and `UnexpectedStatusException` consume them, producing human-readable error messages.
- `ResponseInterceptor` instances receive the decompressed `WireResponse`.
- Retry probe decoding (`probePayloadDecode`) operates on decompressed JSON.

---

## Status and Verification Plan

- [x] **Step 1:** Concept and Architecture Decision Record (ADR-0021).
- [x] **Step 2:** Configuration in `ModuleInfo`, `SimpleModuleInfo`, `JRestlyClient` + request header assembly + unit tests (`DecompressionConfigTest`, 17 tests).
- [x] **Step 3:** Full decompression implementation in `HttpTemplate` + comprehensive WireMock integration tests (`GzipDeflateDecompressionTest`, 11 tests covering gzip, x-gzip, deflate zlib, deflate raw, streaming, SSE/lines, error payloads, async).
- [x] **Step 4:** Finalization (status Accepted, ROADMAP distillation, gates 465/465 tests, 55 test classes, 0 warnings under `-Werror`).
