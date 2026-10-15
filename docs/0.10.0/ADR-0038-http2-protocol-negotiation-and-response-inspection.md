# ADR-0038: HTTP/2 Protocol Negotiation & Response Inspection

> 2026-10-15 · Accepted · Status: Complete · Next free ADR: 0039

## Context

HTTP/2 (RFC 7540 / RFC 9113) and HTTP/1.1 (RFC 7230 / RFC 9112) represent the foundation of modern enterprise REST APIs, microservices, and gRPC-web gateways. In the Java 21 standard library, `java.net.http.HttpClient` negotiates the HTTP protocol version during the connection handshake (typically via TLS ALPN or HTTP/1.1 Upgrade). A client configured with `HttpClient.Version.HTTP_2` may successfully establish an HTTP/2 multiplexed session or transparently fall back to HTTP/1.1 if the target endpoint or intermediate proxies do not support HTTP/2.

Prior to ADR-0038, `ru.jrestly` lacked end-to-end propagation of negotiated protocol information:
1. `HttpTemplate.toWireResponse(HttpResponse<?> response)` captured the status code, headers, and body/stream, but completely discarded `response.version()`.
2. As a consequence, `WireResponse` had no protocol version component; callers of downstream interceptors (`ResponseInterceptor` via `ResponseContext.response()`) could not inspect what protocol was actually negotiated.
3. Call-site metadata inspection via `ResponseHeaders` (passed as an unannotated method parameter or captured via `ResponseHeaders.capture(...)`, ADR-0022) had no method to check `version()`, `isHttp2()`, or `isHttp1()`.
4. Observability and metrics integration via `CallMetrics` (ADR-0036) lacked a protocol version accessor, preventing telemetry collectors (such as OpenTelemetry, Micrometer, Prometheus) from recording the `http.flavor` or `network.protocol.version` attribute.
5. In `HttpTemplateBuilder.getHttpVersion()`, `@HttpVersion` declared on super-interfaces was ignored if declared across an interface hierarchy, failing to follow the standard `ru.jrestly` precedence rule (Method > Sub-interface > Super-interface > Client default).
6. Regarding HTTP response trailers (RFC 7230 §4.4 / RFC 7540 §8.1.2): the JDK 21 platform `java.net.http.HttpResponse` does not expose an accessor for trailing headers (it remains encapsulated inside internal JDK streaming state). However, library users require a standardized parser for declared trailer headers (`Trailer: ...`) and a forward-compatible model structure for trailing headers in `WireResponse` and `ResponseHeaders`.

## Decision

Introduce zero-dependency HTTP/2 protocol negotiation propagation, response inspection, and trailer header declarations:

### 1. Wire Model Protocol Propagation (`WireResponse`)
Extend the `WireResponse` record with `HttpClient.Version version` and `List<Header> trailers`:
```java
public record WireResponse(
        int statusCode,
        List<Header> headers,
        byte[] body,
        InputStream stream,
        HttpClient.Version version,
        List<Header> trailers
)
```
- Provide backward-compatible constructors:
  - `WireResponse(int statusCode, List<Header> headers, byte[] body)`
  - `WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream)`
  - `WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream, HttpClient.Version version)`
- Compact constructor performs defensive copying and normalization: `null` version defaults to `HttpClient.Version.HTTP_1_1`, `null` trailers default to `List.of()`.

### 2. Transport Capture & Logging (`HttpTemplate`)
- In `toWireResponse(HttpResponse<?> response)`: extract `response.version()` and construct `WireResponse` with the actual negotiated version.
- In `handleDecompression(WireResponse response)`: preserve `response.version()` and `response.trailers()` across transparent GZIP/Deflate decoding.
- In `logResponse(WireResponse response, long elapsedMs)`: include the negotiated protocol in INFO log output (`"Response: HTTP/2 200 in 12 ms"` vs `"Response: HTTP/1.1 200 in 12 ms"`).
- In `processResponse(...)`: propagate `response.version()` and `response.trailers()` to `responseHeadersHolder` and active `CURRENT_CAPTOR`.

### 3. Hierarchical `@HttpVersion` Resolution (`HttpTemplateBuilder`)
Refactor `HttpTemplateBuilder.getHttpVersion()` to scan the full interface inheritance hierarchy using `collectInterfaceHierarchy(controller)` in bottom-up order (`for (int i = hierarchy.size() - 1; i >= 0; i--)`):
- Method `@HttpVersion` takes absolute precedence.
- Sub-interface `@HttpVersion` overrides super-interface `@HttpVersion`.
- Super-interface `@HttpVersion` applies when sub-interface has no annotation.
- Falls back to `null` (inherits client-level `moduleInfo.getHttpVersion()`).

### 4. Call-Site Metadata Inspection (`ResponseHeaders`)
Enhance `ru.jrestly.http.ResponseHeaders`:
- Add `HttpClient.Version version()` accessor, defaulting to `HTTP_1_1` when unpopulated.
- Add helper predicates `boolean isHttp2()` and `boolean isHttp1()`.
- Add `List<Header> trailers()` and case-insensitive lookup methods `Optional<String> firstTrailer(String name)` and `List<String> trailerValues(String name)`.
- Add RFC 7230 / RFC 9110 declared trailer parser `List<String> declaredTrailers()`: extracts and trims comma-separated trailer header names declared in the `Trailer` header.
- Provide overloads for `populate(...)` and constructors accepting `version` and `trailers`, ensuring full backwards compatibility.
- Ensure `ResponseHeaders.capture(...)` forwards the negotiated version and trailers.

### 5. Telemetry & Observability SPI (`CallMetrics`)
Extend `ru.jrestly.metrics.CallMetrics` with a default method:
```java
default HttpClient.Version version() {
    return response().map(WireResponse::version).orElse(HttpClient.Version.HTTP_1_1);
}
```
Non-breaking addition for all `ClientMetricsListener` implementations.

### 6. Platform Limitations & Non-Goals
- **JDK 21 Platform Limitation:** `java.net.http.HttpResponse` in Java 21 LTS does not expose a public API for trailing headers (trailers). Attempting to access them via private internal fields (`jdk.internal.net.http.Stream`) would violate the Zero-Dependency core invariant and require dangerous `--add-opens`.
- **Declared trailers vs Wire trailers:** `declaredTrailers()` inspects the standard HTTP `Trailer` header present in response headers, while the `trailers()` list in `ResponseHeaders` and `WireResponse` allows testing, mock injection, and future JDK platform compatibility.

## Consequences & Verification

- **Backwards Compatibility:** 100% source and binary compatible. All existing constructors, default methods, and tests pass without modification.
- **Zero-Dep Core:** Uses strictly `java.net.http.HttpClient.Version` and `java.base`. 0 external dependencies.
- **Verification:**
  - Unit tests for `WireResponse`, `ResponseHeaders`, `CallMetrics`, and `@HttpVersion` hierarchy resolution.
  - WireMock integration tests verifying negotiated version propagation in method parameters, capture pattern, async `CompletableFuture`, GZIP decompression, and telemetry metrics.
