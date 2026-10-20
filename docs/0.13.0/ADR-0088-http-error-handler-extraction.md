# ADR-0088: HttpErrorHandler Extraction — Status Evaluation, Exception Factory & @OnError Dispatch

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the decomposition plan outlined in ADR-0080 / ADR-0087, we target the error handling and status evaluation slice of the #1 red class `HttpTemplate` (2,127 lines, WMC 414). Prior to this step, `handleOnError`, `createUnexpectedStatusException`, and `isExpectedStatus` were embedded directly in `HttpTemplate`, carrying 31 WMC and 6 missed branches.

---

## 1. Problem & Boundaries

`HttpTemplate` contained three interrelated mechanisms governing non-2xx responses and status evaluation:
1. **Status Code Acceptability (`isExpectedStatus`):** Evaluated whether a status was successful based on an explicit whitelist (`@ExpectStatus`), standard 2xx range, conditional 304 allowance, or optional 304/404 allowances.
2. **Unexpected Status Exception Hierarchy (`createUnexpectedStatusException`):** Built typed exception instances (`ClientErrorException` for 4xx, `ServerErrorException` for 5xx, base `UnexpectedStatusException` for others) enriched with RFC 7807 / RFC 9457 `ProblemDetail` resolved from the response.
3. **`@OnError` Dispatch Orchestration (`handleOnError`):** Matched declared error classes from `OnErrorParser`, conditionally deserialized payloads via the media-type codec, handled `ProblemDetail` fallbacks and status stamping, created `ProblemDetailException` wrappers, instantiated and rethrew typed `Throwable` hierarchies, or threw `HandledException`.

While tightly related to HTTP dispatch, none of these operations depend on HTTP transport state, streaming sockets, or client proxies. They form a cohesive, stateless error handling subsystem.

---

## 2. Architecture & Design Decisions

### (D1) Package-private Stateless Collaborator
Extracted to `ru.jrestly.http.HttpErrorHandler` (package-private, `final`, private constructor).

Public package surface:
- `isExpectedStatus(List<Integer> expectStatuses, boolean conditional, boolean optional, int statusCode) -> boolean`
- `createUnexpectedStatusException(int statusCode, String rawBody, List<Header> responseHeaders, PayloadCodec codec) -> UnexpectedStatusException`
- `handleOnError(OnErrorParser onErrorParser, Supplier<PayloadCodec> codecSupplier, int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) -> void`
- `handleOnError(OnErrorParser onErrorParser, PayloadCodec codec, int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) -> void`

### (D2) Lazy Codec Resolution
In `handleOnError`, the codec is supplied via `Supplier<PayloadCodec> codecSupplier`. When `payload == null`, `payload.isEmpty()`, or the target class is `Void.class`, the codec is never resolved. This preserves the invariant that void or bodyless errors do not incur registry lookups or media-type resolution.

### (D3) Transparent Seams in `HttpTemplate`
`HttpTemplate` retains package-private delegate methods:
```java
void handleOnError(int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) {
    HttpErrorHandler.handleOnError(
            onErrorParser, () -> responseCodec(responseHeaders), statusCode, payload, responseHeaders, payloadCharset);
}

private UnexpectedStatusException createUnexpectedStatusException(
        int statusCode, String rawBody, List<Header> responseHeaders) {
    return HttpErrorHandler.createUnexpectedStatusException(
            statusCode, rawBody, responseHeaders, responseCodecOrNull(responseHeaders));
}

private boolean isExpectedStatus(int statusCode) {
    return HttpErrorHandler.isExpectedStatus(expectStatuses, conditional, optional, statusCode);
}
```
Existing unit tests (`HttpTemplateProblemDetailResolutionTest`, `ClientMetricsWireMockTest`, etc.) continue to exercise these seams without modification.

---

## 3. Verification & Metrics

- **TDD Red-First:** `HttpErrorHandlerTest` implemented with 27 comprehensive test cases:
  - Whitelist vs 2xx default vs conditional 304 vs optional 304/404 combinations;
  - 4xx client errors, 5xx server errors, and non-standard status codes (<400 or >=600);
  - Plain DTO decoding, null/empty payloads, `Void.class` targets;
  - `ProblemDetail` decoding, explicit status retention, status <= 0 backfilling, codec failure fallback to `fromJson`, blank payload skipping;
  - `ProblemDetailException` with and without bodies;
  - Decoded `RuntimeException`, `Error`, and checked `Throwable` rethrow semantics;
  - Bodyless reflection instantiation of custom exceptions;
  - Reflection test for private constructor.

- **Collaborator Quality (ADR-0081 C11 Ratchet):**
  - **Branches:** **67 / 67 covered (100.00%, 0 missed)**
  - **Complexity:** **40 / 40 covered (100.00%, 0 missed)**
  - **Lines:** 55 / 56 covered (98.21%, single unreachable return byte after throw)
  - **Physical Lines:** 170 (green ≤ 400)
  - **WMC:** 40 (green ≤ 100)

- **Impact on `HttpTemplate`:**
  - Physical Lines: **2,127 → 2,057** (−70 lines)
  - WMC: **414 → 383** (−31 complexity)
  - Missed Branches: **78 → 69** (−9 missed)

- **Library-wide Metrics:**
  - **Branch Coverage:** 92.02% → **92.23%** (4,026 / 4,365 covered — **new all-time record**)
  - **Complexity Coverage:** 90.61% → **90.84%** (3,353 / 3,691 covered — **new all-time record**)
  - **Missed Branches:** 348 → **339** (new all-time minimum)
  - **Total Tests:** 1,928 → **1,955** (root 1,791 = 1,764 + 27; submodules 164), 0 failures/errors
  - **Javadoc:** clean across all 8 modules with `-Werror`.
