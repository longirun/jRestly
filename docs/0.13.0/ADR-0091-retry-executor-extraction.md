# ADR-0091: RetryExecutor Extraction — Resilient Retry Loop Decoupling and HttpTemplate Complexity Reduction

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the ongoing god-class decomposition of `ru.jrestly.http.HttpTemplate` (1,976 lines, WMC 364, 63 missed branches), we extract the entire retry coordination mechanism — synchronous and asynchronous retry loops, exponential backoff with jitter and Retry-After header parsing, status classification, idempotency guards, and payload decode probe — into a dedicated collaborator `ru.jrestly.http.RetryExecutor`.

---

## 1. Problem & Boundaries

Prior to this change, `HttpTemplate` was responsible for both executing HTTP transfers and coordinating complex resilient retry policies (ADR-0008, ADR-0011, ADR-0015, ADR-0040):
1. **Synchronous Retry Pipeline (`executeWithRetries`):** Synchronous iteration with status checks, transport `IOException` handling, `InterruptedException` fast-failing, intermediate stream closing, backoff sleep calculation, attempt tracking, and per-attempt metric emission.
2. **Asynchronous Retry Pipeline (`executeAsyncWithRetries`, `attemptAsync`, `scheduleAsyncRetry`):** Asynchronous stage composition chaining over `CompletableFuture.delayedExecutor`, non-blocking delays, and stage handle unwrapping.
3. **Payload Decode Probe (`probePayloadDecode`):** Side-effect-free payload decode verification (ADR-0011) ensuring that decode errors trigger retries before final classification.
4. **Retry Predicates & Status Classification:** Determining retryable statuses (`isRetryableStatus`, `isNonIdempotent`), respecting `@OnError` terminal contracts, and handling method idempotency.

This dense cluster of retry logic contributed ~300 lines of high-complexity code (~80 WMC) to `HttpTemplate`, making it the sole remaining RED god-class in the library.

---

## 2. Architecture & Design Decisions

### (D1) Extraction of `RetryExecutor` & `RetryConfig`
The retry mechanics are encapsulated in `ru.jrestly.http.RetryExecutor` (package-private, `final`, stateful per-call instance):
```java
final class RetryExecutor {
    @FunctionalInterface
    interface SyncSender {
        WireResponse send(WireRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface AsyncSender {
        CompletableFuture<WireResponse> send(WireRequest request);
    }

    record RetryConfig(
            RetryPolicy retryPolicy,
            HttpMethod httpMethod,
            System.Logger logger,
            ClientMetricsListener metricsListener,
            OnErrorParser onErrorParser,
            List<Integer> expectStatuses,
            boolean conditional,
            boolean optional,
            Type returnType,
            Function<List<Header>, PayloadCodec> codecResolver,
            Executor executor
    ) { ... }
}
```

By introducing the `RetryConfig` record, constructor parameter count is reduced to 1, satisfying the **C8 rule (≤ 7 parameters)** of ADR-0081.

### (D2) Clean Functional Sender Abstraction
`RetryExecutor` does not depend on `HttpTemplate` or redirect mechanics directly. It accepts functional interfaces `SyncSender` and `AsyncSender`:
- In sync mode: `req -> executeWithRedirects(req)`
- In async mode: `req -> executeAsyncWithRedirects(req, hopsLeft)`

This cleanly decouples the retry concern from redirect execution, avoiding cyclic dependencies (C9 rule: zero package cycles, loose coupling).

### (D3) 100% Backward Compatibility in `HttpTemplate`
In `HttpTemplate`:
- `retryExecutor()` is lazily instantiated from builder-populated fields.
- `executeWithRetries` and `executeAsyncWithRetries` delegate in one line to `retryExecutor().execute(...)` and `retryExecutor().executeAsync(...)`.
- `probePayloadDecode(WireResponse)` and `isRetryableStatus(WireResponse)` remain as package-private delegates for existing white-box unit tests (`HttpTemplateDecodeProbeTest`, `HttpTemplateLineAndEventStreamErrorAndAsyncTest`, `HttpTemplateStreamContractTest`).
- `unwrapCause(Throwable)` is shared cleanly.

---

## 3. Verification & Metrics (ADR-0081 Scorecard)

### Test Suite (`RetryExecutorTest`)
A new dedicated collaborator test suite (`RetryExecutorTest.java`) with **27 exhaustive unit tests** verifies:
- Disabled retry policy passthrough (sync & async, success & failure);
- Transport failure retries and exhaustion;
- Fail-fast `InterruptedException` handling;
- Retryable status (503) retry and intermediate stream cleanup;
- Non-retryable status (400) immediate return;
- `@OnError` terminal status contract vs `retryOnErrorStatuses(true)`;
- Non-idempotent method (POST/PATCH) rules;
- Payload decode failure probe in both sync and async pipelines;
- Zero backoff fast-paths;
- All edge branches of `probePayloadDecode` (null body, empty bytes, primitive void, Void, byte[], String, Optional, DTOs).

**JaCoCo Collaborator Coverage (`RetryExecutor` & `RetryConfig`):**
- **124/124 Branches (100.00%) — 0 missed branches (C11 rule green!)**
- **152/152 Lines (100.00%)**
- **885/885 Instructions (100.00%)**
- **81/81 Complexity (100.00%)**
- **19/19 Methods (100.00%)**

### Library Impact & Scorecard Changes

| Metric | Before (#350) | After (#351) | Change |
|---|---|---|---|
| `HttpTemplate` Physical Lines | 1,976 (Red) | **1,714** (Red) | **−262 lines (−13.3%)** |
| `HttpTemplate` WMC (JaCoCo) | 364 (Red) | **304** (Red) | **−60 complexity (−16.5%)** |
| `HttpTemplate` Missed Branches | 63 | **54** | **−9 missed branches** |
| `RetryExecutor` Physical Lines | — | **339** (Green ≤ 400) | Collaborator extracted |
| `RetryExecutor` WMC | — | **78** (Green ≤ 100) | Collaborator extracted |
| Library Total XML Tests | 1,989 | **2,016** | **+27 tests (milestone: >2,000!)** |
| Library Missed Branches | 308 | **296** | **−12 missed (new historic all-time low < 300!)** |
| Library Branch Coverage | 92.93% | **93.23%** | **+0.30% (new all-time record!)** |
| Library Complexity Coverage | 91.69% | **92.03%** | **+0.34% (new record > 92%!)** |
| Library Instruction Coverage | 98.43% | **98.53%** | **+0.10% (new record!)** |
| Library Line Coverage | 98.13% | **98.25%** | **+0.12% (new record!)** |
| Library Class Coverage | 100% (159/159) | **100% (161/161)** | **100% maintained** |
