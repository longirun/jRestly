# ADR-0011: CompletableFuture Controller Return Types

- **Status:** accepted
- **Date:** 2026-11-02
- **Scope:** FEAT-08 of the 0.9.0 cycle (TODO.md §2)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0003 / commit `f1a9dbe`

## Context

Prior to this feature, all controller method invocations were strictly synchronous and blocking:
1. `HttpTemplate.exchange()` executed `httpClient.send(...)` synchronously on the caller's thread.
2. Even when downstream services had high latency, the calling thread remained blocked until the response arrived, redirects completed, and decoding finished.
3. Callers requiring asynchronous non-blocking I/O had to manually wrap synchronous proxy calls in `CompletableFuture.supplyAsync(...)`, wasting thread pool resources and parking threads while waiting for network I/O.

Since the underlying HTTP engine is `java.net.http.HttpClient`, non-blocking dispatch is natively supported via `HttpClient.sendAsync(...)`.

---

## Decisions

### 1. Declarative Return Type Detection (`HttpTemplateBuilder`)

`HttpTemplateBuilder.resolveReturnType` inspects the method's generic return type at proxy creation:
- When the return type is `CompletableFuture<T>`:
  - Sets `async = true` on `HttpTemplate`.
  - Unwraps `T` (whether a class or parameterized type, including `Void` and `String`) as the wire contract `returnType`.
  - Raw `CompletableFuture` (without generic arguments) defaults `returnType` to `Object.class`.
- When the return type is not `CompletableFuture`, standard synchronous dispatch is retained.

### 2. Non-blocking Dispatch & Unified Exception Mapping (`HttpTemplate`)

- **Asynchronous Execution:**
  When `async` is enabled, `HttpTemplate.exchange()` delegates to `exchangeAsync(request, startNanos)` and returns `CompletableFuture<Object>` directly to the caller through the dynamic proxy.
- **Shared Seams:**
  - `toHttpRequest`, `logPerHop`, and `toWireResponse` are extracted to ensure `send(request)` and `sendAsync(request)` share 100% identical HTTP request construction, header mapping, and per-hop timing logging.
  - `processResponse` is extracted into a shared tail method: both sync and async paths run the exact same authorization details capture, response logging, `@OnError` parsing, status code check, and JSON / String / Void decoding.
- **Exception Uniformity:**
  On failure, the `CompletableFuture` completes exceptionally with the exact same exception type that the synchronous twin would throw:
  - `@OnError` match $\rightarrow$ `HandledException`
  - Unmatched HTTP error status $\rightarrow$ `UnexpectedStatusException`
  - Network / transport error (`IOException`) $\rightarrow$ `RuntimeException(IOException)`
  - Thread interruption $\rightarrow$ `Thread.currentThread().interrupt(); throw new RuntimeException(InterruptedException)`
  Under standard JDK `CompletableFuture` contracts, calling `.join()` throws `CompletionException` with the mapped exception as its direct cause, and calling `.get()` throws `ExecutionException` with the same cause.
- **Async Redirects:**
  Redirect following is shared between sync and async execution. The decision logic is extracted into `nextHop(request, response)` (including cross-origin credential dropping). In async mode, hops are chained non-blockingly using `thenCompose` over `sendAsync`.

---

## Deviations from the reference (`infinite` `f1a9dbe`)

1. **Clean Recursion Termination:** In the reference `f1a9dbe`, `logExhaustedRedirects` was chained in an outer `.thenApply` on recursive calls, causing redundant "Too many redirects" log lines on unwinding. In `main`, `logExhaustedRedirects` is invoked once when redirect recursion terminates (`next == null || hopsLeft <= 0`).
2. **Modern Exception Unwrapping:** Unwrapping in `exchangeAsync` leverages Java 21 type matching to cleanly preserve runtime exceptions without unnecessary wrapper nesting.

---

## Verification

1. `CompletableFutureReturnTest`:
   - Decoded DTO return (`CompletableFuture<TestDto>`).
   - Decoded collection return (`CompletableFuture<List<TestDto>>`).
   - POST request echoing decoded body (`CompletableFuture<TestDto>`).
   - Raw string return (`CompletableFuture<String>`).
   - `Void` return on HTTP 204.
   - `Void` return on non-empty 200 payload (payload discarded without error).
   - Truly non-blocking dispatch verified against a 750 ms delayed WireMock stub (method returns in < 100 ms).
   - `@OnError` exception mapping surfacing `HandledException`.
   - Unexpected HTTP status surfacing `UnexpectedStatusException`.
   - Transport fault surfacing `RuntimeException` with `IOException` cause.
   - Asynchronous 302 redirect following.
2. Gates: full WireMock test suite green (`./gradlew test`), zero runtime dependencies in generated POM.
