# ADR-0047: Interceptor Chain Short-Circuit SPI

**Status:** Accepted  
**Date:** 2026-10-15  
**Session:** #127

---

## Context

In complex HTTP client architectures, certain cross-cutting requirements demand completing an HTTP call without actually dispatching a request across the physical network:
1. **Local and Edge Caching (HTTP Cache, Memory Cache, ETag Cache):** When a valid cached representation exists, an interceptor should immediately return the cached `WireResponse` without network I/O, saving bandwidth and latency.
2. **In-Memory Testing and Mocking:** Automated unit and component tests frequently need to mock specific HTTP responses or entire third-party APIs without binding TCP sockets or running external HTTP servers (such as WireMock).
3. **Offline Fallback and Circuit Breaker Stubs:** When the system is offline or a downstream service is known to be down, an interceptor can supply a pre-computed synthetic fallback response.
4. **Pre-flight Security and Policy Validation:** Interceptors enforcing client-side validation, rate quotas, or policy checks should be able to reject or simulate a response (e.g., 400 Bad Request, 403 Forbidden) locally before touching the network.

Prior to this ADR, `RequestInterceptor` only returned a modified `WireRequest`:
```java
WireRequest intercept(WireRequest request, RequestContext context);
```
Every invocation was unconditionally placed on the wire via `executeWithRetries()` / `executeAsyncWithRetries()`. There was no mechanism for an interceptor to abort network transmission and return a synthetic `WireResponse`.

## Decision

1. **Context-Driven Short-Circuit Capability (`RequestContext`):**
   - Extended `ru.jrestly.interceptor.RequestContext` with:
     - `default void shortCircuit(WireResponse response)`
     - `default Optional<WireResponse> shortCircuitResponse()`
     - `default boolean isShortCircuited()`
   - Implemented in `SimpleRequestContext` using a thread-safe `volatile WireResponse shortCircuitResponse` reference.
   - Any `RequestInterceptor` can invoke `context.shortCircuit(syntheticResponse)`.

2. **Dedicated `ShortCircuitInterceptor` SPI (`ru.jrestly.interceptor`):**
   - `@FunctionalInterface` extending `RequestInterceptor`:
     ```java
     @FunctionalInterface
     public interface ShortCircuitInterceptor extends RequestInterceptor {
         Optional<WireResponse> shortCircuit(WireRequest request, RequestContext context);

         @Override
         default WireRequest intercept(WireRequest request, RequestContext context) {
             Optional<WireResponse> synthetic = shortCircuit(request, context);
             if (synthetic != null && synthetic.isPresent()) {
                 context.shortCircuit(synthetic.get());
             }
             return request;
         }
     }
     ```
   - Standard static factories:
     - `ShortCircuitInterceptor.of(WireResponse response)`: unconditional synthetic response.
     - `ShortCircuitInterceptor.matching(Predicate<WireRequest> predicate, Function<WireRequest, WireResponse> supplier)`: conditional response based on request URI, method, headers, etc.

3. **Execution Pipeline Integration (`HttpTemplate`):**
   - During `applyRequestInterceptors()`:
     - After each interceptor runs, the engine checks `requestContext.shortCircuitResponse().isPresent()`.
     - If a synthetic response has been supplied, the request interceptor loop breaks immediately, avoiding unnecessary further mutations.
   - Network Bypass:
     - Physical network calls (`executeWithRetries` / `executeAsyncWithRetries`) are skipped entirely.
     - Rate limiters and circuit breakers are not acquired, preserving capacity and failure counters.
     - Number of network attempts is tracked as `0`.
   - Normal Response Pipeline Routing:
     - The synthetic `WireResponse` is decompressed if needed (`handleDecompression`), and passed to `processResponse()`.
     - Supports all return types: DTO objects (via configured `JsonCodec`), `String`, `byte[]`, `InputStream`, `Optional<T>`, `Stream<String>`, `CompletableFuture<T>`.
     - Respects `@OnError` status mappings (e.g. throwing custom typed exceptions on synthetic 4xx/5xx).
     - Respects `@ExpectStatus` status validation.
     - Populates `ResponseHeaders` holder and `ResponseHeaders.capture()` thread-local captors.
     - Fully triggers `ResponseInterceptor` and `ClientMetricsListener` with final duration and status.
   - Full Asynchronous Support:
     - In asynchronous invocations (`exchangeAsync`), short-circuited responses complete the returned `CompletableFuture` non-blockingly and asynchronously.

4. **Synthetic Response Construction Ergonomics (`WireResponse`):**
   - Enhanced `ru.jrestly.http.WireResponse` with static factory methods and a fluent `Builder`:
     - `WireResponse.of(int statusCode)`
     - `WireResponse.of(int statusCode, String body)`
     - `WireResponse.of(int statusCode, byte[] body)`
     - `WireResponse.of(int statusCode, List<Header> headers, String body)`
     - `WireResponse.of(int statusCode, List<Header> headers, byte[] body)`
     - `WireResponse.builder().statusCode(...).header(...).body(...).build()`
     - Accessors `header(String name)` and `hasBody()`.

5. **Fluent Client Builder Ergonomics:**
   - `JRestlyClient.Builder` and `SimpleModuleInfo.Builder` provide:
     - `shortCircuit(ShortCircuitInterceptor interceptor)`
     - `mock(WireResponse response)`
     - `mock(Predicate<WireRequest> predicate, WireResponse response)`
     - `mock(Predicate<WireRequest> predicate, Function<WireRequest, WireResponse> responseSupplier)`

## Consequences

- Zero-dependency invariant preserved: implemented 100% in Java 21 core.
- Backwards compatibility: Existing `RequestInterceptor` implementations continue to work unmodified.
- Testing ergonomics: Users can mock API calls in memory without spinning up HTTP servers or WireMock.
- Cache enablement: Paves the way for zero-dep HTTP caching layers.

## Amendment #249: Fix `HttpTemplate.fallbackMethod()` Reflection Resolution and Achieve 100% Method Coverage

1. **Bug Resolution (33rd "silent" finding of the series):**
   - In `HttpTemplate.createRequestContext()`, when `method == null` (e.g. synthetic or programmatic template dispatch), the fallback method reflection lookup called `HttpTemplate.class.getMethod("exchange")`.
   - Because `exchange()` is package-private, `getMethod` unconditionally failed with `NoSuchMethodException: ru.jrestly.http.HttpTemplate.exchange()`, wrapping into `IllegalStateException: exchange method not found`.
   - Fixed to `HttpTemplate.class.getDeclaredMethod("exchange")`.
2. **Quality Milestone:**
   - Full test coverage of edge cases in `HttpTemplateBuilder`, `CircuitBreaker` async pipeline, `MultipartBodyPublisher`, `TrailerCompletingInputStream`, `WireResponse`, and `CompositeClientMetricsListener`.
   - JaCoCo coverage reaches historic milestones: **100.00% Method Coverage (1418/1418)** and **100.00% Class Coverage (143/143)** across the entire library codebase.

