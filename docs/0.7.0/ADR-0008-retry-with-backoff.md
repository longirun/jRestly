# ADR-0008: Retry with backoff

> 2026-10-13 · Proposed · concept step of the "Retry with backoff" epic (DRAFT, Post-0.5.0 shortlist per ADR-0003). Implementation follows in the subsequent steps; this ADR is the source of truth for the design decisions and the test strategy.

## Context

ADR-0003 shortlists retry with backoff as jRestly's **only** resilience feature
(circuit breakers, caching, load balancing stay non-goals) and notes that it
"integrates with `@OnError`". Today a call is exactly one attempt:
`HttpTemplate.exchange()` sends (following the configured redirects) and runs
`processResponse`; any failure is user-visible immediately, and there is no
configuration surface for "try again later".

The failure classes that exist today:

1. **Transport failures** — `IOException` from `HttpClient.send`
   (connect timeout: `HttpConnectTimeoutException`; request timeout:
   `HttpTimeoutException`; connection reset; DNS blips; …) and
   `InterruptedException`. Both are wrapped in a plain `RuntimeException`
   (sync path) or become the future's exceptional cause (async path).
2. **Unexpected statuses** — `UnexpectedStatusException` (a
   `HandledException` subclass): the status is not in `@ExpectStatus` (or 2xx
   when absent) and not declared in `@OnError`.
3. **Handled errors** — `HandledException`: the status **is** declared in
   `@OnError.statuses`; the body is deserialized into the typed
   `errorObject`.

Goal: make a flapping backend a non-event for transient failures, without
changing the error contract of a *final* failure and without adding a
dependency.

## Decisions

### Aspect 1: What gets retried

Per failed attempt:

| Failure | Retried by default | Notes |
|---|---|---|
| `IOException` (transport) | **yes** | includes connect/request timeouts — both are `IOException` subtypes |
| `InterruptedException` | **no** | the caller's thread was interrupted: re-interrupt and fail fast (today's semantics preserved) |
| status ∈ policy set (default `{408, 429, 500, 502, 503, 504}`) | **yes** | the transient server family; 501/505 excluded (deterministic) |
| any other unexpected status | **no** | bare 4xx are deterministic client errors by default |
| status declared in `@OnError.statuses` | **no** (by default) | the contract wins — Aspect 3 |
| expected status (`@ExpectStatus` / 2xx) | — | success, the loop ends |

Rationale for retrying transport by default: these are the classic transient
cases (deploy windows, connection churn). The existing socket-timeout knob
already expresses "how long one attempt may last", and retry composes with it:
`maxAttempts × timeout` is the worst-case wall time, and it is a number the
user has chosen twice, explicitly.

### Aspect 2: Configuration surface — client-level policy + per-method `@Retry` override

**Both levels; the method level wins.** Precedent in the codebase:
`httpVersion` is a `SimpleModuleInfo.Builder` default plus a per-method
`@HttpVersion` override, and the per-method value replaces the default
entirely. Retry follows the same shape:

- **Client level:** `JRestlyClient.Builder.retry(RetryPolicy)` /
  `SimpleModuleInfo.Builder.retry(RetryPolicy)`, carried on `ModuleInfo` as a
  **default method**:
  `default RetryPolicy getRetryPolicy() { return RetryPolicy.off(); }`.
  Default-method form ⇒ no break for existing `ModuleInfo` implementors
  (public API is not broken without extreme need).
- **Per method:** `@Retry` annotation (`ru.jrestly.annotation`,
  `@Target(METHOD)`), attributes mirroring the policy:

  ```java
  @Retry(times = 3, initialBackoffMs = 200, maxBackoffMs = 5_000,
         statuses = {408, 429, 500, 502, 503, 504},
         transport = true, onErrorStatuses = false, nonIdempotent = false)
  ```

  The annotation is a **self-contained policy** that fully replaces the
  client-level one when present (no field-by-field merging — predictability
  over cleverness). `@Retry(times = 1)` is the explicit off-switch on a
  method of a client that has retry enabled.
- **The `RetryPolicy` value class** in `ru.jrestly.http` (immutable, nested
  builder in the house style):
  - `maxAttempts` — total attempts including the first, `≥ 1`
    (validated in the builder);
  - `initialBackoff`, `maxBackoff` — `Duration`, `initialBackoff ≤
    maxBackoff` (validated);
  - `retryableStatuses` — unmodifiable `Set<Integer>`, default
    `{408, 429, 500, 502, 503, 504}`;
  - `retryTransport` — default `true`;
  - `retryOnErrorStatuses` — default `false` (Aspect 3);
  - `retryNonIdempotent` — default `false` (Aspect 5).
  Statics: `RetryPolicy.defaults()` (3 attempts, 200 ms → 5 s cap, default
  statuses) and `RetryPolicy.off()` (`maxAttempts = 1`).

**Default: off.** 0.x behavior-change discipline: an existing user's failure
timing must not change unless they opt in. Resilience is a choice, not a
default — the same reason circuit breakers ship behind a flag everywhere.

### Aspect 3: Order with `@OnError` — retry first, classification last

The retry loop operates on the **raw response** (`WireResponse`: status,
headers, byte[] body) *before* `processResponse` runs. `processResponse`
(auth-details capture, response logging, `@OnError` deserialization, status
check, payload decode) executes **exactly once, on the final response**:

- the `@OnError` body is deserialized exactly once — no per-attempt
  allocation on the hot path, no typed error object to discard when a later
  attempt succeeds;
- auth-details capture and the response log stay per-final-response —
  today's user-visible behavior is preserved;
- the loop's failure predicates are the exact ones `processResponse` already
  uses (`onErrorParser.canParse(status)`, `isExpectedStatus(status)`), so
  "what the loop calls a failure" and "what `processResponse` calls a
  failure" cannot drift apart.

**The conflict rule** (the direct answer to ADR-0003's "integrates with
`@OnError`"): a status declared in `@OnError.statuses` is a *terminal
contract* — the developer has declared what this status means, so by default
the loop stops and the typed exception is produced. The escape hatch is
`retryOnErrorStatuses = true` (on either policy level): declared statuses
then participate in the retry decision like any other status, and the
**final** failure is still classified by `@OnError` — the terminal result
remains the typed `HandledException`, never a raw `UnexpectedStatusException`.

`UnexpectedStatusException` is *not* an `@OnError` status: it is retried per
the status set, and its raw-body shape (`getRawBody()`) is preserved on the
final attempt.

### Aspect 4: Backoff curve — exponential with cap and full jitter

- `delay(n) = min(maxBackoff, initialBackoff · 2^(n-1))`, where `n` is the
  1-based attempt number that just failed; the actual sleep is
  `random(0, delay(n))` — **full jitter** (the AWS architecture-blog
  recipe). Without jitter, all clients of a flapping server retry on the
  same beat and re-break it.
- A **fixed** curve is expressible, not a new mode: `initialBackoff ==
  maxBackoff` ⇒ every delay is that constant. No `BackoffStrategy` enum in
  0.x.
- **`Retry-After`:** when the failed response carries a parseable
  integer-seconds `Retry-After` header, the delay is
  `max(delay(n), retryAfter)` — the server's explicit signal beats the
  client's guess. (HTTP-date form: known gap, "Known gaps" below.)
- The backoff computation is a **pure function** on `RetryPolicy`
  (package-private seam, injectable `Random` for deterministic jitter in
  tests) — the hot path allocates nothing beyond the log line.

Defaults: `initialBackoff = 200 ms`, `maxBackoff = 5 s`.

### Aspect 5: Idempotency — safe methods by default, POST/PATCH opt-in

Per RFC 9110, jRestly's `HttpMethod` set splits cleanly:

| Method | Idempotent | Retried by default |
|---|---|---|
| GET | yes | **yes** |
| PUT | yes | **yes** |
| DELETE | yes | **yes** |
| POST | no | no |
| PATCH | no | no |

`retryNonIdempotent = true` (either policy level) extends retrying to
POST/PATCH for methods whose author knows the endpoint tolerates duplication
(idempotency keys). The default stays conservative because the retry happens
*after* the request has reached the server: a lost response on a successful
POST is the classic duplicate-create bug.

### Aspect 6: Execution path (implementation seam for the following steps)

Sync: `exchange()` keeps its shape; `executeWithRedirects` gets wrapped by a
new `executeWithRetries(request)`:

```
for attempt in 1..maxAttempts:
    try        response = executeWithRedirects(request)
    catch IOException e:
        if (retryTransport && attempt < maxAttempts) { log DEBUG; sleep(delay(attempt)); continue }
        throw e
    catch InterruptedException: throw        // never retried
    if (attempt < maxAttempts && statusRetryable(response)) { log DEBUG; sleep(delay(attempt)); continue }
    return response
```

- Re-sending the same `WireRequest` is safe today: `toHttpRequest` builds a
  fresh `HttpRequest` per send and `bodyPublisher` is per-send by design
  ("stream suppliers are single-use"), so the body (byte[] or multipart
  parts + boundary) is re-readable on every attempt.
- `executeWithRedirects` re-runs from hop zero on each attempt (a mid-chain
  redirect failure is that hop's transport failure → a fresh chain).
- One DEBUG line per attempt:
  `Attempt 1/3 failed: HTTP 503 (or: connection reset) — retrying in 213 ms`.
  No metrics surface in 0.x — the log is the observability.
- Async twin: the same loop chained over `sendAsync`; the backoff wait runs
  on `CompletableFuture.delayedExecutor` (non-blocking — no parked virtual
  thread per retry), and `exchangeAsync`'s final exception mapping is
  untouched.
- `processResponse` is not modified — single pass on the final response.

### Aspect 7: Test strategy

No new test dependencies — WireMock (already on the test classpath) provides
both status scenarios (sequential responses per scenario state) and transport
faults.

- **Unit — the backoff curve** (`RetryPolicyTest`): the pure delay function
  through the package-private seam — exponential shape, cap,
  fixed-via-equal-boundaries, the `Retry-After` max() rule, deterministic
  jitter via the injected `Random`; builder validation (`maxAttempts ≥ 1`,
  `initialBackoff ≤ maxBackoff`); `defaults()`/`off()` semantics.
- **Integration** (`RetryTest extends BaseWireMockTest`; small real backoffs,
  10–50 ms, so CI stays fast):
  1. 503 → 503 → 200 scenario, client policy 3 attempts → success, 3
     requests in the journal.
  2. Exhaustion: 503 forever, 3 attempts → final `UnexpectedStatusException`,
     3 requests.
  3. Transport: `withFault(CONNECTION_RESET_BY_PEER)` then 200 → success,
     2 requests.
  4. Non-retryable status: 404 forever → 1 request.
  5. `@OnError` terminal by default: 429 forever,
     `@OnError(statuses = {429})` → 1 request, typed `HandledException`.
  6. `@OnError` opt-in: 429 → 429 → 200 with `onErrorStatuses = true` →
     success, 3 requests; and 429 forever → typed exception after the
     attempts.
  7. Idempotency: POST under a client policy → 1 request; the same method
     with `@Retry(nonIdempotent = true)` → retried.
  8. `@Retry(times = 1)` overrides an enabled client policy → 1 request.
  9. Async: `CompletableFuture` method, 503 → 200 → `join()` returns the
     value, 2 requests.
  10. `Retry-After: 1` on a 429 with a small computed backoff → the wall
      clock honors the header (elapsed lower bound).

## Consequences

- New public surface: `RetryPolicy` + builder, `@Retry`,
  `ModuleInfo.getRetryPolicy()` (default method — non-breaking),
  `JRestlyClient.Builder.retry` + `SimpleModuleInfo.Builder.retry`.
- Zero behavior change for existing users (off by default); the POM stays
  dependency-free.
- Retry semantics are uniform across sync/async, redirect chains, and
  multipart bodies (each attempt rebuilds the publisher; the boundary is
  stable per call).
- README: a "Retry with backoff" feature bullet + a short usage section, due
  in a later step (the README step is the last step of the epic, house
  pattern).

## Known gaps (accepted)

- `Retry-After` HTTP-date form (RFC 9110 allows it) — v1 honors integer
  seconds only.
- A payload decode failure on the **final** response (codec exception inside
  `processResponse`) is not retried — v1 retries transport and status only.
- `InterruptedException` is never retried, even with
  `retryTransport = true` (documented in Javadoc).
- No circuit breaker, no per-status attempt caps, no metrics —
  ADR-0003's non-goals stand.
- The deterministic-jitter seam is package-private (test-only).
- `Retry-After` applies to status retries only (transport failures have no
  response headers).
