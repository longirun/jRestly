# ADR-0012: Retry Policy with Exponential Backoff & Jitter

- **Status:** accepted
- **Date:** 2026-11-07
- **Scope:** FEAT-09 of the 0.10.0 cycle (TODO.md §2), step 1 of the retry contract (FEAT-09 → 10 → 11)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0008 (retry-with-backoff concept) — reference implementation extracted from the final state (`RetryPolicy`, `@Retry`) and adapted to `main`'s architecture

## Context

Today a call is exactly one attempt: `HttpTemplate.exchange()` sends (following the configured redirects) and runs `processResponse`; any failure is user-visible immediately, and there is no configuration surface for "try again later". The failure classes that exist today:

1. **Transport failures** — `IOException` from `HttpClient.send` (connect timeout: `HttpConnectTimeoutException`; request timeout: `HttpTimeoutException`; connection reset; DNS blips) and `InterruptedException`. Both surface as a plain `RuntimeException` (sync) or the future's exceptional cause (async).
2. **Unexpected statuses** — `UnexpectedStatusException`: the status is not in `@ExpectStatus` (or 2xx when absent) and not declared in `@OnError`.
3. **Handled errors** — `HandledException`: the status is declared in `@OnError.statuses`; the body is deserialized into the typed `errorObject`.

Goal: make a flapping backend a non-event for transient failures, without changing the error contract of a *final* failure and without adding a dependency. Retry is **off by default** — an existing user's failure timing must not change unless they opt in (0.x behavior-change discipline).

---

## Decisions

### 1. What gets retried

Per failed attempt:

| Failure | Retried by default | Notes |
|---|---|---|
| `IOException` (transport) | **yes** | includes connect/request timeouts — both are `IOException` subtypes; gated by `retryTransport` alone (pre-response: the request may never have reached the server, so no idempotency gate) |
| `InterruptedException` | **no** | the caller's thread was interrupted: re-interrupt and fail fast (semantics preserved) |
| status ∈ policy set (default `{408, 429, 500, 502, 503, 504}`) | **yes** | the transient server family; 501/505 excluded (deterministic) |
| any other unexpected status | **no** | bare 4xx are deterministic client errors by default |
| status declared in `@OnError.statuses` | **no** (default) | the contract wins |
| expected status (`@ExpectStatus` / 2xx) | — | success, the loop ends |

Idempotency (RFC 9110): GET/PUT/DELETE are retried by default; POST/PATCH only with `retryNonIdempotent = true` (a retry happens *after* the request was sent — a lost response on a successful POST is the classic duplicate-create bug). The idempotency gate applies to **status** retries only.

### 2. Configuration surface — client-level policy + per-method `@Retry` override

**Both levels; the method level wins.** Precedent: `httpVersion` is a `SimpleModuleInfo.Builder` default plus a per-method `@HttpVersion` override whose value replaces the default entirely.

- **Client level:** `JRestlyClient.Builder.retry(RetryPolicy)` / `SimpleModuleInfo.Builder.retry(RetryPolicy)` (`Objects.requireNonNull` fail-fast), carried on `ModuleInfo` as a **default method** `default RetryPolicy getRetryPolicy() { return RetryPolicy.off(); }` — no break for existing `ModuleInfo` implementors. `SimpleModuleInfo` overrides with the built policy (unset → `off()`).
- **Per method:** `@Retry` (`ru.jrestly.annotation`, `@Target(METHOD)`) — a **self-contained policy** that fully replaces the client-level one when present (no field-by-field merging — predictability over cleverness). `@Retry(times = 1)` is the explicit off-switch on a method of a retry-enabled client.
- **The `RetryPolicy` value class** (`ru.jrestly.http`, immutable, nested builder in the house style):
  - `maxAttempts` — total attempts including the first, `≥ 1` (validated in the builder);
  - `initialBackoff`, `maxBackoff` — `Duration`, non-negative, `initialBackoff ≤ maxBackoff` (validated);
  - `retryableStatuses` — unmodifiable `Set<Integer>` owned by the policy (the builder copies), default `{408, 429, 500, 502, 503, 504}`, values validated to `[100, 599]`;
  - `retryTransport` — default `true`; `retryOnErrorStatuses` — default `false`; `retryNonIdempotent` — default `false`.
  - Statics: `RetryPolicy.defaults()` (3 attempts, 200 ms → 5 s cap, default statuses) and `RetryPolicy.off()` (`maxAttempts = 1`, a shared immutable instance — the client-level default sits on a per-request path).

### 3. Order with `@OnError` — retry first, classification last

The retry loop operates on the **raw response** (`WireResponse`) *before* `processResponse` runs. `processResponse` (auth-details capture, response logging, `@OnError` deserialization, status check, payload decode) executes **exactly once, on the final response**:

- the `@OnError` body is deserialized exactly once — no per-attempt allocation on the hot path, no typed error object to discard when a later attempt succeeds;
- the loop's failure predicates are the exact ones `processResponse` already uses (`isExpectedStatus`, `onErrorParser.canParse`), so "what the loop calls a failure" and "what `processResponse` calls a failure" cannot drift apart (`isRetryableStatus` on `HttpTemplate`).

**The conflict rule:** a status declared in `@OnError.statuses` is a *terminal contract* by default; the escape hatch is `retryOnErrorStatuses = true` — the status then participates in the retry decision, and the final failure is still classified by `@OnError` (a typed `HandledException`, never a raw `UnexpectedStatusException`).

### 4. Backoff curve — exponential with cap and full jitter

- `bound(n) = min(maxBackoff, initialBackoff · 2^(n-1))` for the 1-based attempt that just failed; the actual sleep is `random(0, bound)` — **full jitter** (the AWS architecture-blog recipe; without jitter, all clients of a flapping server retry on the same beat and re-break it).
- A **fixed** curve is expressible, not a new mode: `initialBackoff == maxBackoff` ⇒ every delay is that constant. No jitter strategy enum in 0.x (the reference's later `Jitter`/multiplier surface is deliberately not ported — see Deviations).
- **`Retry-After`:** when the failed response carries a parseable `Retry-After` header, the wait is exactly `max(bound, retryAfter)` — the server's explicit signal beats the client's guess, and the wait is **deterministic (no jitter)** in that case: jittering below the header risks the same 429 again. FEAT-09 honors the live forms (delta-seconds, IMF-fixdate via `RFC_1123_DATE_TIME`; `max(0, date − now)` for the date form); the obsolete HTTP-date forms and wait bounds land with FEAT-10.
- The backoff computation is a **pure function** on `RetryPolicy` (`nextDelayMillis`, package-private seam, injectable `Random` for deterministic jitter in tests) — the hot path allocates nothing beyond the DEBUG log line.

Defaults: `initialBackoff = 200 ms`, `maxBackoff = 5 s`.

### 5. Execution path

Sync: `exchange()` keeps its shape; the send is wrapped by `executeWithRetries(request)`:

```
for attempt in 1..maxAttempts:
    try        response = executeWithRedirects(request)
    catch IOException e:
        if (retryTransport && attempt < maxAttempts) { log DEBUG; sleep(delay(attempt)); continue }
        throw e
    catch InterruptedException: throw        // never retried
    if (attempt < maxAttempts && isRetryableStatus(response)) { log DEBUG; sleep(delay(attempt)); continue }
    return response
```

- Re-sending the same `WireRequest` is safe: `toHttpRequest` builds a fresh `HttpRequest` per send and `bodyPublisher` is per-send by design (stream suppliers are single-use), so the body (byte[] or multipart parts + boundary) is re-readable on every attempt.
- `executeWithRedirects` re-runs from hop zero on each attempt (a mid-chain redirect failure is that hop's transport failure → a fresh chain).
- One DEBUG line per attempt: `Attempt 1/3 failed: HTTP 503 (or: IOException simple name) — retrying in 213 ms`. No metrics surface in 0.x — the log is the observability.

Async: the same loop chained over `sendAsync` (`executeAsyncWithRetries` → `attemptAsync`); the backoff wait runs on `CompletableFuture.delayedExecutor` (non-blocking — no parked thread per retry); `exchangeAsync`'s final exception mapping is untouched. `processResponse` is not modified — single pass on the final response.

### 6. Eager `@Retry` validation (`ControllerValidator`)

An invalid `@Retry` declaration (e.g. `times = 0`, `initialBackoffMs > maxBackoffMs`, a status outside `[100, 599]`) is rejected at proxy creation with an aggregated, actionable `ControllerValidationException` — the same rules as `RetryPolicy.Builder.build()`, mirrored in `ControllerValidator.validateRetry` and invoked from `validateMethod`, like every other declarative annotation whose values carry invariants. Without this, an invalid policy would pass validation and only fail lazily at first invocation, deep inside template resolution.

---

## Deviations from the reference (`infinite`)

1. **No `Jitter` enum / no `backoffMultiplier`.** The reference later grew a 4-strategy `Jitter` (FULL/EQUAL/DECORRELATED/NONE) and a configurable multiplier (its ADR-0040). The 0.10.0 port keeps ADR-0008's original stance — full jitter only, multiplier fixed at 2.0, "no `BackoffStrategy` enum in 0.x". The advanced surface is a separate backlog candidate (TODO.md), not silently dropped.
2. **No bounds/opt-out for `Retry-After` yet** (`maxRetryAfter`, `respectRetryAfter`, the default-cap fallback of the reference's Amendment #184) — FEAT-10 lands them with the full header surface.
3. **The loop lives on `HttpTemplate`.** The reference extracted `RetryExecutor`/`ResilienceComposer` (its ADR-0091/0085 god-class decomposition, FEAT-35/36). `main` keeps ADR-0008 Aspect 6's original shape: the loop and the predicates on `HttpTemplate`, no premature extraction.
4. **Eager validation targets `ControllerValidator`.** The reference's Amendment #207 introduced `ControllerValidator.validateRetry` against an evolved validator; `main` already has the same eager aggregated-problems architecture, so the port is direct (no adaptation needed).

---

## Verification

1. `RetryPolicyTest` (unit, no WireMock): `defaults()`/`off()` shapes; builder validation (`maxAttempts ≥ 1`, non-negative backoffs, `initialBackoff ≤ maxBackoff` with the equal case allowed, null collection/element, `[100, 599]` range, copy + unmodifiable set); exponential shape with cap (200/400/800/1600/3200 → pinned at 5 s); full jitter within `[0, bound]`; fixed curve; the `Retry-After` deterministic `max(bound, retryAfter)` rule independent of the jitter draw; negative `Retry-After` clamped to the bound.
2. `RetryTest` (sync, WireMock, 10–50 ms real backoffs): off by default; 503→503→200 recovery (3 requests); exhaustion keeps `UnexpectedStatusException`; transport fault retried; bare 404 not retried; `@OnError` terminal by default (typed `HandledException`, 1 request); `@OnError` opt-in retried; POST not retried by default; POST retried with `retryNonIdempotent`; multipart POST retried with the body re-sent complete and the boundary stable per call (both wire attempts byte-identical); redirect chain re-runs from hop zero per attempt; `Retry-After: 1` raises the wall-clock wait (lower bound 1000 ms); IMF-fixdate form raises the wait (lower bound 2000 ms).
3. `RetryAsyncTest`: the async twin retried for status and transport; exhaustion carries the sync failure shape through `join()`; 404 not retried; bare `@Retry` off-switch on the async circuit.
4. `RetryAnnotationTest`: bare `@Retry` overrides an enabled client policy; the annotation alone retries while the client is off; `nonIdempotent` and `onErrorStatuses` attributes; `@OnError` exhaustion stays typed; custom status set replaces the default family; auth capture (`@SetAuthDetails`) fires exactly once, on the final response.
5. `ControllerValidationTest`: `rejectsInvalidRetryConfigurationEagerly` — 4 aggregated problems at proxy creation (`times = 0`, inverted backoff, statuses 99/600) + a valid-policy no-false-positive guard.
6. Gates: full suite 296 tests green (`./gradlew test`), IDE inspections clean on all touched files, POM with zero runtime `<dependency>`.
