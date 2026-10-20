# ADR-0092: RedirectExecutor Extraction — Decoupling Redirect Execution Loops, Hop Lifecycle, and Cross-Origin Hygiene from HttpTemplate

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the ongoing god-class decomposition of `ru.jrestly.http.HttpTemplate` (1,714 lines, WMC 304, 54 missed branches), we complete the redirect subsystem extraction initiated in ADR-0066 by extracting the execution loops (`executeWithRedirects`, `executeAsyncWithRedirects`, `executeAsyncHop`), intermediate stream resource management, and hop budget tracking into `ru.jrestly.http.RedirectExecutor`.

---

## 1. Problem & Boundaries

In ADR-0066, `Redirects` was created to encapsulate next-hop resolution (301/302/303 downgrade vs. 307/308 preservation, cross-origin credential hygiene, Location parsing). However, the actual iteration loops and lifecycle management remained inside `HttpTemplate`:
1. **Synchronous Execution Loop (`executeWithRedirects`):** Stateful while-loop decrementing hop budget, invoking `nextHop`, closing intermediate streams, and sending next hops.
2. **Asynchronous Execution Pipeline (`executeAsyncWithRedirects`, `executeAsyncHop`):** Recursive `thenCompose` stage composition over `sendAsync`, propagating budget decrements and closing intermediate streams.
3. **Resource Lifecycle (`closeIntermediateStream`):** Intermediate response streams on redirect hops must be promptly closed to prevent socket descriptor leaks.
4. **Diagnostic Exhaustion (`logExhausted`):** Emitting diagnostic logs when a server loops past the configured hop budget.

This execution loop cluster added ~70 lines of procedural branching and 12 WMC points to `HttpTemplate`, with scattered lambda branches.

---

## 2. Architecture & Design Decisions

### (D1) Unified `RedirectExecutor`
All redirect concerns — policy decisions, execution orchestration, and intermediate resource release — are unified into package-private `ru.jrestly.http.RedirectExecutor`:
```java
final class RedirectExecutor {
    @FunctionalInterface
    interface SyncSender {
        WireResponse send(WireRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface AsyncSender {
        CompletableFuture<WireResponse> send(WireRequest request);
    }

    private final Integer followRedirectsNumber;
    private final List<String> authHeaderNames;
    private final System.Logger logger;

    RedirectExecutor(Integer followRedirectsNumber, List<String> authHeaderNames, System.Logger logger) { ... }

    WireResponse execute(WireRequest request, SyncSender sender) throws IOException, InterruptedException { ... }

    CompletableFuture<WireResponse> executeAsync(WireRequest request, AsyncSender sender) { ... }

    void closeIntermediateStream(WireResponse response) { ... }
}
```

### (D2) Loose Coupling via Functional Sender Interfaces
`RedirectExecutor` accepts `SyncSender` and `AsyncSender` lambdas (`this::send`, `this::sendAsync`). It knows nothing about `HttpEngine`, interceptors, or metrics, achieving:
- Zero cyclic dependencies (ADR-0081 C9);
- Total testability without network or mocking frameworks;
- Symmetrical composition with `RetryExecutor` (ADR-0091).

### (D3) Backward Compatibility Facade
`ru.jrestly.http.Redirects` is retained as a package-private delegating facade to `RedirectExecutor`. Existing unit tests (`RedirectsTest`, `RedirectsCrossOriginTest`) continue to pass without changes, preserving all historical test guarantees.

### (D4) HttpTemplate Seam
In `HttpTemplate`:
- `private RedirectExecutor redirectExecutor;` is lazily instantiated via `redirectExecutor()`;
- `executeWithRedirects` delegates to `redirectExecutor().execute(request, this::send)` in 1 line;
- `executeAsyncWithRedirects` delegates to `redirectExecutor().executeAsync(request, this::sendAsync)` in 1 line;
- `executeAsyncHop` is completely removed from `HttpTemplate`;
- `closeIntermediateStream` delegates to `redirectExecutor().closeIntermediateStream(response)`.

---

## 3. Verification & Metrics

- **Red-First TDD:** `RedirectExecutorTest` written before implementation (56 compile-red errors).
- **Collaborator Test Suite:** 25 dedicated unit tests in `RedirectExecutorTest` plus 18 existing tests in `RedirectsTest` and `RedirectsCrossOriginTest` (total 43 tests).
- **Collaborator Coverage (ADR-0081 C11):**
  - Branches: **84/84 (100.00%)** — **0 missed branches**;
  - Complexity: **58/58 (100.00%)** — **0 missed complexity**;
  - Lines: **85/85 (100.00%)**;
  - Instructions: **369/369 (100.00%)**;
  - Methods: **16/16 (100.00%)**.

### Scorecard Impact (ADR-0081)

| Metric | Before (#351) | After (#352) | Delta | Status |
|---|---|---|---|---|
| `HttpTemplate` Lines | 1,714 | 1,673 | −41 | Yellow/Red (Target ≤ 400) |
| `HttpTemplate` WMC | 304 | 292 | −12 | **WMC < 300 for first time!** |
| `HttpTemplate` Missed Branches | 54 | 51 | −3 | Improving |
| Total Library Missed Branches | 296 | **294** | −2 | **New historical all-time low!** |
| Total Library Branch Coverage | 93.23% | **93.29%** | +0.06% | **New all-time record!** |
| Total Library Complexity Coverage | 92.03% | **92.11%** | +0.08% | **New all-time record!** |
| Gate Passing Tests | 2,016 / 2,016 | **2,041 / 2,041** | +25 tests | 100% green |
| Javadoc `-Werror` | Clean | Clean | 0 warnings | 100% green |
