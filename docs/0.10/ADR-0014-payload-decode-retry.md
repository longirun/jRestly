# ADR-0014: Payload-Decode Retry

- **Status:** accepted
- **Date:** 2026-11-07
- **Scope:** FEAT-11 of the 0.10.0 cycle (TODO.md §2), step 3 of the retry contract — the last substantive axis
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0011 / commit `81cafc5`

## Context

After FEAT-09/10 the loop retries transport and status failures, and honors `Retry-After` — but a **payload decode failure on the final response is not retried**: a `200` whose bytes arrived in full yet do not bind to the declared type (a server mid-deploy returning a drift-shifted schema, a one-off malformed serialization) propagates the codec's exception immediately.

Transport retry does **not** cover this: an incomplete body is an `IOException` and is transport-retried already; a *complete* body that fails to bind is a distinct residual. The two axes are complementary: transport = "the bytes didn't all arrive"; decode = "the bytes arrived but don't bind".

## Decisions

### 1. The loop gains a third failure-kind

| Failure-kind | What it is | Retried |
|---|---|---|
| transport | `IOException` from the send | `retryTransport` |
| status | a status in the policy's retryable set | the status predicate |
| **decode (new)** | a status-acceptable response whose body the codec cannot bind | `retryDecodeFailures` (new, distinct opt-in) |

A response is final only when it is the last attempt, or not a status-retry, or — new — its payload decodes. Decode is a *last-resort* trigger: it fires only on a response the status logic already accepts.

### 2. "Classified exactly once" — preserved by a side-effect-free probe

The loop does **not** run `processResponse` per attempt. It runs a `probePayloadDecode(WireResponse)` predicate — a package-private seam on `HttpTemplate` reproducing *only* the payload-decode branch of `processResponse`:

1. a declared `@OnError` status → returns normally (it classifies before the payload decode; the terminal / `retryOnErrorStatuses` contract governs it, orthogonal to the decode axis);
2. an unexpected status → returns normally (the status check classifies before the decode);
3. a null/empty body → returns normally;
4. a `String` return type → returns normally (returned raw);
5. `Void`/`void` → returns normally (the body is discarded);
6. otherwise `jsonCodec.deserialize(payload, returnType)` — the codec's own unchecked exception is the retry trigger.

The probe touches no auth-consumer, no logger, no exception object — `processResponse`'s side-effectful tail (auth capture, response log, `@OnError` construction, status check) still fires exactly once, on the *truly* final response. Cost: when retry is enabled and the flag is on, a final success response is decoded twice (probe, then the real decode) — bounded, final-response-only, opt-in. The rejected alternative (probe returns the decoded value and `processResponse` reuses it) was declined in the reference for the same reason: it couples the side-effectful tail to the probe's cache.

### 3. The final exception is unchanged — no new type

When attempts are exhausted on an unbindable body, `processResponse` runs on the last response and its `jsonCodec.deserialize` throws the codec's own unchecked exception (`JacksonCodec` → `UncheckedIOException`) — the *same* exception the caller sees without retry. No new exception class, no wrapping: retry only adds a chance to not fail.

### 4. A distinct opt-in flag — `retryDecodeFailures`, default `false`

`RetryPolicy.Builder.retryDecodeFailures(boolean)` and a `@Retry` attribute, self-contained as the rest of the annotation is. Default off: unlike transport/status (which only ever touch a *failed* response), the probe runs on every *success* response — a real cost the other axes don't pay. A user enabling retry for 5xx flaps must not silently get a success-path double-decode; "enable retry" stays a zero change on the success path unless both flags are on. Wiring: both loops (`executeWithRetries` sync, `attemptAsync` async) probe only when `!last`; the per-attempt DEBUG line distinguishes the kind (`Attempt 1/2 failed: decode (UncheckedIOException) — retrying in 10 ms`).

## Deviations from the reference (`infinite` `81cafc5`)

None substantive. The probe's branch set is narrower than the reference's `RetryExecutor.probePayloadDecode` because `main` lacks the later streaming (`InputStream` returns, FEAT-14), `Optional<T>` returns (FEAT-27), and multi-codec (`PayloadCodec` resolution, FEAT-41) surfaces — the probe mirrors exactly the branches `processResponse` has today, which is the invariant ("the probe mirrors the decode branch"), not a loss.

## Known gaps (accepted, non-blocking)

- A declared `@OnError` status whose **error body** fails to decode is not decode-retried (the probe returns normally for a declared status; the `@OnError` deserialization then throws on the only attempt). Documented and pinned by `RetryDecodeTest.malformedErrorBodyOnDeclaredStatusIsTerminal`.
- The final response's payload is decoded twice (probe + `processResponse`) when retry is enabled and the flag is on — a bounded, final-response-only, opt-in cost.

## Verification

1. `HttpTemplateDecodeProbeTest` (unit, stub `JsonCodec`, no WireMock): bindable body passes; unbindable body throws the codec's own exception; declared `@OnError` status → out of scope; unexpected status → out of scope; null payload / `String` / `Void` / `void` → pass.
2. `RetryDecodeTest` (WireMock, 10–50 ms backoffs): 200 malformed → 200 good under the flag (2 attempts, success); exhaustion keeps the codec's own `UncheckedIOException` (2 attempts, no new type); default off with generic retry enabled → 1 request, untouched success path; declared `@OnError` 429 (well-formed error body) → 1 request, typed `HandledException`; the malformed-error-body gap pinned; "classified exactly once" via the auth-capture counter across a decode-retry that eventually succeeds; async twin (recovery + exhaustion through `join()`).
3. `RetryPolicyTest`: `retryDecodeFailures` default off in all statics; the builder sets it.
4. Gates: full suite 331 tests green (`./gradlew test`), IDE inspections clean on all touched files, POM with zero runtime `<dependency>`.

With this step the retry contract is complete: transport + status + `Retry-After` + decode, no remaining substantive known gap.
