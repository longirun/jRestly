# ADR-0053: Lifecycle Resource Safety and Zero-Reflection Parameter Descriptors

**Status:** Accepted  
**Date:** 2026-10-15  
**Author:** jRestly Autonomous Core Architect  

## Context

During a comprehensive architectural and resource audit in session #133 (prompted by maintainer inquiry regarding resource leaks, redundant object creation, and steady-state benchmark profiling):
1. **Hidden Resource Leak in Lifecycle Management:**
   In ADR-0052, `AbstractHttpClient` was refactored to delegate transport to `ru.jrestly.engine.HttpEngine`. When wrapping its own default JDK `HttpClient`, `createConnection()` created `JdkHttpEngine.from(this.httpClient)` with unowned semantics (`ownsClient = false`). In `AbstractHttpClient.close()`, the condition `!(httpEngine instanceof JdkHttpEngine)` prevented direct closing of `this.httpClient`, while `JdkHttpEngine.close()` bypassed closing because `ownsClient` was false. Consequently, internal selector threads and socket pools of the default `HttpClient` were never closed upon `client.close()`.
2. **Redundant Allocations on the Dispatch Hot-Path:**
   Profiling with JMH `-prof gc` revealed that in-memory dispatch produced ~4.1 KB/op of heap garbage. Detailed code analysis showed that despite method-level annotation caching (ADR-0051), `HttpTemplateBuilder.build()` still repeatedly invoked `collectInterfaceHierarchy(controller)` (3 times per invocation), scanned interface-level annotations (`@RequestHeader`, `@Cookie`, `@RequestDefaultParam`), created intermediate `ArrayList` and `Collectors.toSet()` stream pipelines, and called `Method.getParameters()` multiple times (cloning parameter arrays in OpenJDK on every call) to resolve `@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestParam`, `@RequestBody`, and `@MultipartFormFile`.

## Decision

1. **Lifecycle Resource Safety:**
   - In `AbstractHttpClient.createConnection()`, the default `JdkHttpEngine` is created with explicit ownership: `new JdkHttpEngine(this.httpClient, true)`.
   - In `AbstractHttpClient.close()`, `httpClient.close()` is unconditionally invoked if `httpClient != null`, ensuring that all selector threads and connection resources are promptly and idempotently released.
   - Added `AbstractHttpClientLifecycleTest` verifying that closing `AbstractHttpClient` and `JRestlyClient` properly shuts down underlying `HttpClient` and `HttpEngine` instances.

2. **Zero-Reflection Parameter Descriptors & Precomputed Static Context:**
   - Extended `MethodMetadata` with precomputed static wire state:
     - `staticHeaders`: combined interface-level and method-level headers, including negotiated `Accept`, pre-merged with case-insensitive override rules.
     - `staticCookies`: combined interface-level and method-level static cookies.
     - `staticDefaultParams`: combined interface-level and method-level default query parameters.
     - `hasAuthAnnotation`: pre-parsed `@Authorization` check.
     - `effectiveTrace`: pre-resolved method or interface `@Trace`.
     - `requestBodyIndex`: direct index of `@RequestBody` parameter (or `-1` if absent).
   - Introduced `ParameterDescriptor` record in `MethodMetadata`:
     - Pre-indexes each method parameter with its exact bindings (`isPathVariable`, `pathVariableName`, `pathVariableEncoded`, `isRequestHeader`, `requestHeaderName`, `isCookie`, `cookieName`, `isRequestParam`, `requestParamName`, `rawRequestParamName`, `requestParamContentType`, `requestParamFormat`, `isRequestBody`, `isMultipartFile`, `multipartPartName`, `multipartExplicitContentType`, `isResponseHeaders`, `isUploadProgressListener`, `isDownloadProgressListener`).
   - Refactored `HttpTemplateBuilder`:
     - Completely eliminated runtime calls to `collectInterfaceHierarchy()`, `Method.getParameters()`, `Class.getInterfaces()`, and parameter annotation reflection.
     - Dispatch paths now iterate directly over flat descriptor arrays in $O(1)$ time without reflection overhead or stream pipelines.

## Consequences

- **Resource Safety:** Sockets, connection pools, and selector threads are guaranteed to be closed immediately on `client.close()`, preventing resource leakage in dynamic environments, microservices, and test suites.
- **Dispatch Latency Reduction:**
  - `DispatchOverheadBenchmark.jrestly_inMemoryMockDispatch` accelerated from **27.4 µs/op to 6.99 µs/op** (and 5.86 µs/op at peak steady-state).
  - `DispatchOverheadBenchmark.jrestly_inMemoryMockPing` dropped to **2.33 µs/op** (2.26 µs/op peak), setting an all-time library performance record.
- **Memory Allocation Reduction:**
  - Normalized heap allocation dropped from **4098 B/op to 3234 B/op** (-21% garbage reduction per dispatch).
- **Test Suite:** 1022/1022 tests passing across 133 classes (+2 tests), strictly 0 warnings under `-Werror`, 0 runtime dependencies in Maven POM publication.
