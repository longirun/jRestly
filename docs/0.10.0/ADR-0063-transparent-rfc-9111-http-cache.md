# ADR-0063: Transparent RFC 9111 HTTP Cache SPI and In-Memory Store

## Status

Accepted (2026-10-17, Session #238)

## Context

Following the competitive matrix and strategic gap analysis in ADR-0061, the second vital capability identified for enterprise production maturity was **client-side HTTP caching** conforming to RFC 9111.

In cloud native microservice topologies and third-party API consumption:
1. **Network Latency & Bandwidth:** High-frequency read queries against reference data, schema endpoints, and static resources waste round-trips and compute when payloads are unmodified.
2. **Standardization:** Protocols and CDNs rely on standard RFC 9111 headers (`Cache-Control`, `ETag`, `Last-Modified`, `Age`, `Expires`) rather than ad-hoc application-level caching.
3. **Zero-Dep & Transparency:** Existing Java clients either rely on heavy external dependencies or lack declarative integration, forcing developers to write manual conditional checks and response caching.

jRestly's core architecture requires:
- Zero external runtime dependencies (Java 21 `java.net.http.HttpClient` standard types only).
- Transparent return types (ADR-0004: methods return domain objects, not wrapper types; 304 revalidation must transparently decode the cached payload).
- Zero-garbage hot-path dispatch when caching is disabled (`httpCache == null`).
- Exact parity between synchronous (`exchange()`) and asynchronous (`exchangeAsync()`) pipelines.

## Decision

We introduce the `ru.jrestly.cache` SPI package, a thread-safe bounded in-memory LRU store, and transparent integration into the `HttpTemplate` dispatch lifecycle.

### 1. The `ru.jrestly.cache` SPI

The SPI comprises six clean interfaces and models:

1. **`HttpCache`:**
   - `Optional<CachedResponse> get(WireRequest request)`: queries the store for a cached entry on safe methods.
   - `void put(WireRequest request, WireResponse response)`: stores cacheable responses (status 200, 301, 404 with non-null payload bytes).
   - `WireResponse update(WireRequest request, WireResponse response304)`: merges 304 response headers with the stored entry and synthesizes a 200 OK response with the cached body.
   - `void invalidate(URI uri)`: invalidates entries on unsafe requests (`POST`, `PUT`, `DELETE`, `PATCH`).
   - `void clear()` and `long size()`.
2. **`CachedResponse`:** Immutable representation of a stored entry tracking status code, headers, payload bytes, `cachedAt` timestamp, freshness lifetime, `etag`, and `lastModified`. Provides `isFresh()`, `mustRevalidate()`, `hasValidators()`, and `toWireResponse()` with RFC 9111 `Age` calculation.
3. **`SimpleCachedResponse`:** Thread-safe canonical implementation performing defensive cloning of payload byte arrays and dynamic calculation/injection of the `Age` header.
4. **`CacheControl`:** RFC 9111 header parser supporting `max-age`, `no-cache`, `no-store`, `must-revalidate`, `stale-while-revalidate`, `private`, and `public`.
5. **`InMemoryHttpCache`:** Bounded LRU store with configurable capacity (default 100 entries), key normalization (`URI.normalize().toASCIIString()`), and `ReentrantLock` concurrency control. Merges headers per RFC 9111 §4.3.4 (retaining representations like `Content-Type` / `Content-Length`).
6. **`HttpCaches`:** Factory utilities (`HttpCaches.inMemory()` and `HttpCaches.inMemory(int maxEntries)`).

### 2. Client Configuration Surface

Integrated into `ModuleInfo`, `SimpleModuleInfo`, and `JRestlyClient`:

```java
// Default 100-entry in-memory cache
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .inMemoryCache()
        .build();

// Custom capacity
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .inMemoryCache(500)
        .build();

// Custom SPI implementation (e.g. off-heap or distributed)
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .httpCache(myCustomHttpCache)
        .build();
```

### 3. Pipeline Dispatch Lifecycle (`HttpTemplate`)

1. **Cache HIT Short-Circuit:**
   - If `effectiveRequest.method() == HttpMethod.GET` and `httpCache != null`:
     - Store is queried with `httpCache.get(effectiveRequest)`.
     - If entry is fresh, `!cached.mustRevalidate()`, and request does not carry `Cache-Control: no-cache`:
       - Short-circuits network transmission.
       - Synthesizes `WireResponse` with updated `Age` header.
       - Sets `attempts = 0` (recorded in metrics as a short-circuit; circuit breaker does not record execution).
       - Hands response to `processResponse` for transparent decoding.
2. **Conditional Revalidation:**
   - If entry is stale or requires revalidation, and has validators (`ETag` or `Last-Modified`):
     - Injects `If-None-Match` and/or `If-Modified-Since` into the request (without overwriting explicit user headers).
     - If origin server returns `304 Not Modified`:
       - Invokes `httpCache.update(request, response304)`.
       - Merges headers and synthesizes a `200 OK` `WireResponse` containing the cached body.
       - Transparently decodes domain return type; caller receives their expected object without handling 304 manually.
     - If origin server returns `200 OK`:
       - Invokes `httpCache.put(request, response)` to update the cached entry.
3. **Invalidation on Unsafe Methods:**
   - When requests using unsafe methods (`POST`, `PUT`, `DELETE`, `PATCH`) receive a non-error status code (`200..399`), `httpCache.invalidate(uri)` is automatically invoked.
4. **Directives Handling:**
   - `Cache-Control: no-store` on request or response bypasses cache storage and lookup completely.
   - `Cache-Control: no-cache` on request forces conditional revalidation with origin server.

## Verification

- **Unit Tests:**
  - `ru.jrestly.cache.CacheControlTest`: 5 unit tests covering parsing, directives, quoting, and multiple headers.
  - `ru.jrestly.cache.InMemoryHttpCacheTest`: 7 unit tests covering LRU eviction, ETag/Expires freshness, safe method restrictions, 304 header merging, and invalidation.
- **End-to-End Integration Tests (`ru.jrestly.HttpCacheWireMockTest`):**
  - Cache HIT serves from memory without wire request, `Age` header verified.
  - Expired entry revalidates with `If-None-Match` and updates on 304.
  - Server modification returns new 200 OK and updates cache.
  - Request `Cache-Control: no-cache` forces revalidation.
  - Request `Cache-Control: no-store` bypasses cache.
  - Response `Cache-Control: no-store` prevents caching.
  - Mutation via `POST` and `DELETE` invalidates cached URI.
  - Async `CompletableFuture` cache hit and revalidation.
  - Live `InputStream` consumption of cached payload.
  - `ClientMetricsListener` reports `attempts = 0` on cache hit.
- **Full Verification Gate:**
  - `./gradlew test javadoc jacocoTestReport` passes with 0 failures under `-Werror`.
  - Total test count increased from **1270** to **1292** (+22 tests).
