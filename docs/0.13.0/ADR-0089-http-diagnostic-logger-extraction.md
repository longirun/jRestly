# ADR-0089: HttpDiagnosticLogger Extraction — Request/Response Wire Formatting, Diagnostic Per-Hop & Retry Logging

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the ongoing decomposition of the #1 red class `HttpTemplate` (2,057 lines, WMC 383, missed branches 69), we isolate the diagnostic logging and wire-formatting slice (`logRequest`, `logResponse`, `logPerHop`, `logRetry`, `multipartSendSize`, and body-method gating).

---

## 1. Problem & Boundaries

Prior to this extraction, `HttpTemplate` mixed core transport and dispatch orchestration with wire payload diagnostics and log string formatting:
1. **Request Diagnostics (`logRequest`):** Gated by `INFO`, formatted the HTTP method, URI, headers, and body according to `BODY_LOGGING_METHODS` (POST, PUT, PATCH, DELETE; RFC 9110 GET exclusions). For multipart payloads, formatted parts (`name`, optional `filename`) and computed wire send sizes via `MultipartBodyPublisher.contentLength`. For streaming bodies, formatted the safe `<streaming body>` marker without touching live publishers.
2. **Response Diagnostics (`logResponse`):** Gated by `INFO`, rendered protocol version (`HTTP/2` vs `HTTP/1.1`), HTTP status code, total elapsed duration in milliseconds, and response headers.
3. **Per-Hop Diagnostics (`logPerHop`):** Gated by `DEBUG`, rendered per-hop method, target URI, status code, and latency in milliseconds across redirect and retry hops.
4. **Retry Scheduling Diagnostics:** Formatted scheduled retry attempt messages (`"Attempt X/Y failed: <reason> — retrying in Z ms"`), duplicated between sync `scheduleRetry` and async `scheduleRetryAsync`.

Embedded within `HttpTemplate`, these formatting routines contributed 19 WMC, 6 missed branches, and ~95 physical lines of code.

---

## 2. Architecture & Design Decisions

### (D1) Package-private Stateless Collaborator
Extracted to `ru.jrestly.http.HttpDiagnosticLogger` (package-private, `final`, private constructor).

Public package surface:
- `static void logRequest(System.Logger logger, WireRequest request)`
- `static void logResponse(System.Logger logger, WireResponse response, long elapsedMs)`
- `static void logPerHop(System.Logger logger, WireRequest request, int statusCode, long startNanos)`
- `static void logRetry(System.Logger logger, int attempt, int maxAttempts, String failure, long delayMillis)`
- `static String multipartSendSize(WireRequest request)`

### (D2) Preserved Protected Hook Seam
`HttpTemplate` retains its `protected void logRequest(WireRequest request)` hook to maintain full binary and source compatibility for any subclasses or extensions:
```java
protected void logRequest(WireRequest request) {
    HttpDiagnosticLogger.logRequest(logger, request);
}
```
Existing logging tests (`HttpTemplateLoggingTest`) continue to test `template.logRequest(...)` without disruption.

### (D3) Unified Retry Logging Across Sync and Async Paths
Both `scheduleRetry` and `scheduleRetryAsync` now delegate their `DEBUG` retry notice to `HttpDiagnosticLogger.logRetry(...)`, eliminating redundant log string construction logic.

### (D4) Encapsulation of Diagnostics State
The `BODY_LOGGING_METHODS` set and `multipartSendSize` helper are removed from `HttpTemplate` and encapsulated entirely within `HttpDiagnosticLogger`.

---

## 3. Verification & Metrics

- **TDD Red-First:** `HttpDiagnosticLoggerTest` authored prior to class implementation (failing with missing symbol errors), with 14 comprehensive test cases covering:
  - Private constructor reflection accessibility;
  - `null` and disabled log-level gates for all log methods (no-op, no exceptions, no allocations);
  - RFC 9110 GET exclusions (no body logging even if payload present);
  - All body-logging methods (POST, PUT, PATCH, DELETE);
  - Missing body (`"No request body"`) vs UTF-8 body bytes vs `<streaming body>` supplier safety;
  - Multipart parts formatting (with and without filename);
  - Multipart send size calculation (known size vs unknown chunked size vs null parts/request);
  - HTTP/1.1 and HTTP/2 response line rendering;
  - Per-hop elapsed time and status rendering;
  - Retry attempt and backoff delay rendering.

- **Collaborator Quality (ADR-0081 C11 Ratchet):**
  - **Branches:** **46 / 46 covered (100.00%, 0 missed)**
  - **Complexity:** **31 / 31 covered (100.00%, 0 missed)**
  - **Lines:** **55 / 55 covered (100.00%, 0 missed)**
  - **Instructions:** **245 / 245 covered (100.00%, 0 missed)**
  - **Physical Lines:** 145 (green ≤ 400)
  - **WMC:** 31 (green ≤ 100)

- **Impact on `HttpTemplate`:**
  - Physical Lines: **2,057 → 1,976** (−81 lines — **broke below 2,000 lines!**)
  - WMC: **383 → 364** (−19 complexity)
  - Missed Branches: **69 → 63** (−6 missed)

- **Library-wide Metrics:**
  - **Branch Coverage:** 92.23% → **92.33%** (4,043 / 4,379 covered — **new all-time record**)
  - **Complexity Coverage:** 90.84% → **90.95%** (3,368 / 3,703 covered — **new all-time record**)
  - **Missed Branches:** 339 → **336** (new all-time minimum)
  - **Total Tests:** 1,955 → **1,969** (root 1,805 = 1,791 + 14; submodules 164), 0 failures/errors
  - **Javadoc:** clean across all 8 modules under `-Werror`.
