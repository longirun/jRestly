# ADR-0055: Zero-Garbage Dispatch Pipeline & Hot-Path Allocation Pruning

> 2026-10-15 · Accepted · Status: Complete · Next free ADR: 0056

## Context

In high-throughput microservices, real-time streaming integrations, and latency-sensitive API clients, per-request heap allocation rate is a primary driver of garbage collection overhead, thread contention, and tail latency (p99/p99.9). Following the raw-byte codec and transport streaming optimizations introduced in ADR-0054, maintainer benchmarking of `ru.jrestly` against JDK `HttpClient` loopback TCP sockets revealed remaining allocation overheads along the request dispatch hot-path:

1. **Unconditional `resolveCookies()` Allocation:** On every declarative method dispatch, `HttpTemplateBuilder.resolveCookies()` unconditionally instantiated a `new LinkedHashMap<>()` and a Java Stream pipeline (`cookies.entrySet().stream().map(...).collect(...)`), even when neither static `@Cookie` annotations nor dynamic method parameters existed.
2. **Redundant `ArrayList` Clones in `createHeaders()`:** When a request had only static headers (e.g. from interface annotations or default `Accept`), `createHeaders()` still allocated a defensive `new ArrayList<>(staticHeaders)`, followed by `HashSet` allocations for name deduplication.
3. **Stream Pipeline in Auth Header Drops:** In `HttpTemplateBuilder`, `moduleInfo.getAuthProvider().getAuthHeaders().stream().map(Header::name).toList()` was executed unconditionally per request. For unauthenticated clients, this created a Stream pipeline and spliterators just to produce an empty list.
4. **Redundant Parameter Collections:** For parameterless HTTP methods (such as `ping()`, health checks, or ID-based path queries without query parameters), `createRequestParams()` unconditionally instantiated a `new ArrayList<>(0)`.
5. **Stream-Based URI & Form Serialization:** `HttpTemplate.createUri()` used `requestParams.stream().map(...).collect(Collectors.joining("&"))`, creating intermediate stream pipelines, lambda instances, and String concatenations per query string. A similar pattern was used in `createUrlEncodedBody()`.
6. **Unconditional `Duration.ofNanos()` Allocation:** In `HttpTemplate.processResponse()`, `Duration callDuration = Duration.ofNanos(System.nanoTime() - startNanos)` was allocated on every completed HTTP request, even though it was only consumed if `responseHeadersHolder` was present or `ResponseHeaders.CURRENT_CAPTOR` was active.
7. **Per-Send `HttpEngineOptions` Allocation:** `HttpTemplate.send()` and `sendAsync()` created a `new HttpEngineOptions(...)` on every attempt and redirect hop, despite options remaining invariant for the invocation.
8. **Eager Reflection in `isLoginInBackgroundNeeded()`:** In `AbstractHttpClient.isLoginInBackgroundNeeded()`, `method.getAnnotation(Authorization.class)` and `method.getAnnotation(Anonymous.class)` were queried before checking `moduleInfo.getAuthProvider().isAuthorized()`, triggering Reflection metadata lookups on every authorized request.
9. **Unconditional `int[]` Holder Allocation:** In `HttpTemplate.exchange()`, `int[] attemptsHolder = new int[] {1}` was allocated for every synchronous request, even when telemetry metrics listeners and circuit breakers were unconfigured.
10. **Transient `EffectiveCompression` Objects:** `HttpTemplateBuilder.resolveCompression()` instantiated a `new EffectiveCompression(...)` on every request with a body even when compression was disabled.

## Decision

Eliminate these transient allocations along the request dispatch pipeline while preserving 100% backward compatibility, zero external dependencies, and strict behavioral fidelity:

### 1. Zero-Allocation Static Cookies in `MethodMetadata`
- Pre-compute and cache `staticCookieHeader` as an immutable formatted String (`"name=value; name2=value2"`) in `MethodMetadata`.
- In `HttpTemplateBuilder.resolveCookies()`:
  - If `!methodMetadata.hasCookieParams()`, return `methodMetadata.staticCookieHeader()` directly (0 heap allocations).
  - When dynamic cookie parameters are present, format using a direct `StringBuilder` without Stream API.

### 2. Immutable Static Headers Fast-Path
- Pre-compute `hasExplicitContentTypeInStaticHeaders` and `isStaticConditional` in `MethodMetadata`.
- In `HttpTemplateBuilder.createHeaders()`:
  - If no authentication headers are configured, no dynamic `@RequestHeader` parameters are provided, no cookies are present, tracing is disabled, and static headers do not contain an explicit `Content-Type`, return `methodMetadata.staticHeaders()` directly without copying (0 allocations).
  - Apply copy-on-write semantics if headers need mutation downstream.

### 3. Stream-Free Auth Header Names & Fast Conditional Checks
- In `HttpTemplateBuilder`: if `moduleInfo.getAuthProvider().getAuthHeaders()` is empty, supply `List.of()` (singleton). If non-empty, extract names with a direct pre-sized loop.
- In `isConditionalRequest()`: if `!methodMetadata.hasRequestHeaders()`, return `methodMetadata.isStaticConditional()` directly in $O(1)$. If dynamic headers are present, evaluate using case-insensitive equality without allocating `.toLowerCase()` strings.

### 4. Zero-Allocation Parameterless Fast-Path
- In `HttpTemplateBuilder`: if `!methodMetadata.hasRequestParams() && methodMetadata.staticDefaultParams().isEmpty()`, assign `List.of()` directly to `requestParams`, skipping `createRequestParams()` and `new ArrayList<>(0)` allocations.

### 5. Stream-Free Query & UrlEncoded Serialization
- Replace Stream pipelines in `HttpTemplate.createUri()` and `HttpTemplateBuilder.createUrlEncodedBody()` with pre-sized `StringBuilder` iteration loops.

### 6. Lazy `Duration` & Telemetry Allocation
- In `HttpTemplate.processResponse()`: defer `Duration.ofNanos(...)` instantiation to only execute when `responseHeadersHolder != null` or `ResponseHeaders.CURRENT_CAPTOR.get() != null`.
- In `HttpTemplate.exchange()`: allocate `int[] attemptsHolder` only when `metricsListener != null` or `circuitBreaker != null`.

### 7. Cached Default Compression & Pre-Built Engine Options
- Cache a static `DEFAULT_NO_COMPRESSION` singleton in `HttpTemplateBuilder`.
- Pre-construct `HttpEngineOptions` in `HttpTemplateBuilder` and store it in `HttpTemplate`, eliminating per-send options allocations across redirect hops and retries.

### 8. Short-Circuit Authorized Check in Proxy Dispatch
- In `AbstractHttpClient.isLoginInBackgroundNeeded()`, check `moduleInfo.getAuthProvider().isAuthorized()` first. If already authorized, return `false` immediately without reflection calls to `method.getAnnotation(...)`.

## Consequences & Verification

- **Allocation Reduction:** Eliminates hundreds of bytes to multiple kilobytes of transient heap allocations on every request dispatch, reducing GC pause frequency and allocation pressure under heavy concurrent load.
- **Strict Backward Compatibility:** Public API, wire behavior, redirect handling, and error handling remain completely identical.
- **Zero Dependencies:** Pure Java 21 core, strictly 0 dependencies in Maven POM.
- **Verification:**
  - Automated unit tests covering all optimized fast-paths: static cookies, parameterless requests, static headers, conditional requests, and form-urlencoded serialization.
  - Full `./gradlew test` suite green (1026/1026 tests).
  - JMH benchmarks verifying reduced allocation rate.
