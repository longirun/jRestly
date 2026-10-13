# ADR-0011: Retry the payload-decode failure on the final response

- **Status:** proposed (concept step of the "payload-decode retry" epic)
- **Date:** 2026-10-13 (session #31)
- **Scope:** 0.x, unreleased (0.6.2)
- **Reopens:** the payload-decode known gap of ADR-0008 ("a payload decode
  failure on the final response is not retried — v1 retries transport and
  status only"), reaffirmed by ADR-0009 candidate 4
- **Supersedes:** ADR-0009 candidate 4's "LEAVE" decision (per the ADR-0003
  rule "reopening a known gap = a new ADR" — this is that ADR)

## Context

After the 0.x polish epic (ADR-0009) the known-gaps backlog drained to a single
substantive feature: **a payload decode failure on the final response is not
retried.** Everything else in ADR-0008's Known gaps is either an accepted
non-goal (no circuit breaker / metrics / per-status caps), a micro-gap
(the package-private seam, `Retry-After` status-only), or a documented
non-retried case (`InterruptedException`) — deliberate, not missing. Payload
decode is the one *missing behavior*.

ADR-0009 candidate 4 reaffirmed the gap as "LEAVE" with three arguments. This
ADR re-examines all three against the actual code and finds each resolves — or
narrows — under a design that was not available when candidate 4 was written.

The code today (verified against the sources, session #31):

- Both retry loops (`executeWithRetries`, `executeAsyncWithRetries`) wrap
  `executeWithRedirects` only and decide "final" on `last ||
  !isRetryableStatus(response)` — a **status-level** predicate. `processResponse`
  (and hence the codec) runs *after* the loop ends, so a decode failure on the
  final response propagates without a retry.
- `processResponse` runs its side-effectful tail in a fixed order: auth-details
  capture → response log → `@OnError` decode → status check → payload decode.
  ADR-0008 Aspect 3's invariant is that this tail fires **exactly once, on the
  final response** — that is the "classified exactly once" contract.
- A decode failure is the codec's own **unchecked, adapter-specific** exception
  (`JsonCodec` contract: Jackson `UncheckedIOException`, Gson
  `JsonSyntaxException`, Moshi `JsonDataException`) — not a new jRestly type.

**The strategic question (raised by the maintainer, mailbox 2026-10-13).** The
direction call was payload-decode retry vs the 0.7.0 release cycle, with no
preference expressed — "start from strategic planning, what benefits long-term
development." The two are not really competing: the 0.7.0 cycle is a
*packaging* decision (release notes, a version bump, no tag per the maintainer),
while payload-decode is the last *substantive feature*. Doing the feature first
means the 0.7.0 cycle packages a **complete** retry contract — transport,
status, *and* decode — the entire transient-failure surface — with no remaining
substantive known gap. Doing the cycle first strands payload-decode in a
patch version (0.7.x), an awkward place for a new policy flag plus a new
behavior. **This ADR takes payload-decode first** so that 0.7.0 = feature-
complete retry, no loose ends; the cycle then packages it.

## Decisions

### Aspect 1: Reopen the gap — the loop gains a third failure-kind

The loop's per-attempt outcome becomes one of **three** failure-kinds, not two:

| Failure-kind | What it is | Retried |
|---|---|---|
| transport | `IOException` from the send (timeouts, reset, DNS) | `retryTransport` (existing) |
| status | a status in the policy's retryable set | `isRetryableStatus` (existing) |
| **decode (new)** | a status-acceptable response whose body the codec cannot bind | `retryDecodeFailures` (new, Aspect 4) |

A response is **final** (stop retrying) only when it is the last attempt, OR it
is not a status-retry (existing), OR — new — its payload would decode
(Aspect 2). A decode failure is thus a *last-resort* trigger: it fires only on
a response that the status logic already accepts, which is exactly the case the
gap was about (a `200` whose body does not bind to the declared type).

### Aspect 2: "Classified exactly once" — preserved by a side-effect-free probe

This is the disputed point (DRAFT: "новый failure-kind в retry-цикле vs
«classified exactly once»"). Resolution: the loop does **not** run
`processResponse` per attempt. It runs a **decode probe** — a side-effect-free
predicate that reproduces *only* the payload-decode branch of
`processResponse`:

```
boolean probePayloadDecode(WireResponse response):     // package-private seam
    if onErrorParser.canParse(status):  return true    // @OnError classifies before the payload decode
    if !isExpectedStatus(status):       return true    // unexpected status classifies before the payload decode
    if payload == null:                 return true    // no body
    if String.class == returnType:      return true    // returned raw
    if Void/void == returnType:         return true    // body discarded (ADR-0009 candidate 2)
    jsonCodec.deserialize(payload, returnType)         // may throw the codec's unchecked exception
    return true
```

The probe touches **no** auth-consumer, **no** logger, and **no** exception
object. So:

- **"Classified exactly once" holds in its strong form.** The side-effectful
  `processResponse` (auth capture, log, `@OnError` construction, status check)
  still runs exactly once, on the *truly* final response. The probe is a
  read-ahead check, not a classification.
- **Scope falls out of the probe's shape.** It returns `true` for `@OnError`
  and unexpected statuses because those classify *before* the payload decode in
  `processResponse` — so a `@OnError` status keeps its existing terminal /
  `retryOnErrorStatuses` semantics and is *not* decode-retried. Decode retry
  applies to the **success path** (expected status, bindable payload) with no
  special case.
- **Cost:** when retry is enabled and a success response is the final one, the
  payload is decoded **twice** (probe, then `processResponse`). Bounded to the
  final response, and only when the (opt-in, Aspect 4) flag is on. Rejected
  alternative: have the probe return the decoded value and let `processResponse`
  reuse it — cheaper, but it couples the side-effectful tail to the probe's
  cache; rejected for the "probe is a pure predicate" separation.

The loop reads:

```
for attempt in 1..maxAttempts:
    try        response = executeWithRedirects(request)
    catch InterruptedException: throw                       // fail fast (unchanged)
    catch IOException e:
        if (retryTransport && attempt < maxAttempts) { log; sleep; continue }
        throw e
    if attempt == maxAttempts: return response               // exhausted
    if isRetryableStatus(response): { log "HTTP <status>"; sleep; continue }   // status (existing)
    if !probePayloadDecode(response): { log "decode (<codec ex>)"; sleep; continue }  // NEW
    return response
```

`isRetryableStatus` is reused **verbatim** — no status-logic change — so the
ADR-0008 Aspect 3 invariant ("what the loop calls a failure" cannot drift from
"what `processResponse` calls a failure") still holds for the status axis, and
the probe mirrors `processResponse`'s decode branch for the decode axis.

### Aspect 3: The final exception is unchanged — no new type

The second disputed point (DRAFT: "типизация финального исключения"). When
attempts are **exhausted** on a decode failure, the last response is a
success-status whose body still will not bind; `processResponse` runs on it and
its `jsonCodec.deserialize` throws the codec's own unchecked exception — the
*same* exception the caller would see today without retry. **No new exception
class, no wrapping.** Retry only adds a chance to not fail; it does not change
the failure contract. This is ADR-0009 candidate 4's "0.x discipline" argument
(3) holding — now as a property of the design, not a reason to leave the gap.

The per-attempt DEBUG line distinguishes the kind (ADR-0008: "the log is the
observability"): `Attempt 1/3 failed: HTTP 503 — retrying in 213 ms` (status)
vs `Attempt 1/3 failed: decode (UncheckedIOException) — retrying in 213 ms`
(decode). No metrics surface.

### Aspect 4: A distinct opt-in flag — `retryDecodeFailures`, default `false`

Decode-failure retry is a **new policy axis**, not a piggyback on the general
enable:

- **`RetryPolicy.Builder.retryDecodeFailures(boolean)`** (default `false`) and
  a **`@Retry` attribute** `retryDecodeFailures` (default `false`), self-
  contained as the rest of the annotation is.
- **Default off.** Rationale: unlike the transport/status retries (which only
  ever touch a *failed* response), the decode probe runs on every *success*
  response when retry is on — a real allocation the other two axes don't pay.
  A user who enables retry for 5xx flaps should not silently get a success-
  path double-decode. A distinct opt-in keeps "enable retry" = "zero behavior
  change on the success path." This is the strictest reading of 0.x's
  "resilience is a choice, not a default."
- **Rejected alternative:** ride the general `isEnabled()` (no new flag).
  Cheaper to configure, but it makes the general enable change the success
  path — weaker predictability, and it conflates two failure axes that the
  maintainer is likely to want to reason about independently.

### Aspect 5: Relationship to transport retry (ADR-0009 argument 1, sharpened)

ADR-0009 candidate 4's first argument was "transient decode failures are
already covered by transport retry." That is **true only for incomplete
bodies**: a body cut off mid-stream is an `IOException` from `HttpClient` and
is transport-retried today. It does **not** cover a **complete** body (full
`Content-Length` delivered, a `200`) whose bytes simply do not bind to the
declared type — e.g. a server mid-deploy returning a drift-shifted schema, or a
one-off malformed serialization that is still a well-formed HTTP body. That
residual is exactly what this feature retries. The two axes are **complementary,
not redundant**: transport = "the bytes didn't all arrive"; decode = "the bytes
arrived but don't bind."

### Aspect 6: Execution seam (for the following steps)

- Sync: `executeWithRetries` gains the decode axis per Aspect 2's loop; the
  probe is a new package-private method on `HttpTemplate` (a seam like
  `retryAfterDelayMillis`, unit-testable without WireMock).
- Async: `attemptAsync`'s `handle` step mirrors it — after the transport and
  status checks, a success response whose probe throws is scheduled for a
  `delayedExecutor` wait instead of completing the future. `exchangeAsync`'s
  final exception mapping (Aspect 3: the codec exception passes through) is
  untouched.
- The probe is a pure predicate — no new fields on the template, no new public
  API beyond the flag (Aspect 4).

### Aspect 7: Test strategy

No new test dependencies (WireMock is already on the classpath).

- **Unit — the probe** (`HttpTemplateDecodeProbeTest`, package
  `ru.jrestly.http`, no WireMock; a stub `JsonCodec` through the seam):
  expected-status decodable → `true`; expected-status unbindable (malformed /
  wrong-shape) → throws the codec exception; `@OnError` status → `true`
  (classifies before the payload decode); unexpected status → `true`; null
  payload / `String` / `Void` / `void` → `true`.
- **Integration** (WireMock, small backoffs 10–50 ms):
  1. `200` malformed body → `200` good body, `retryDecodeFailures` on, 2
     attempts → success, 2 requests.
  2. Exhaustion: `200` malformed forever, 2 attempts → the **final exception
     is the codec's own unchecked exception** (typed, unchanged), 2 requests.
  3. Default off: `200` malformed, retry enabled for status but
     `retryDecodeFailures` off → 1 request (zero behavior change).
  4. `@OnError` out of scope: `429` with a malformed error body,
     `@OnError(statuses = {429})`, `retryOnErrorStatuses` off → 1 request
     (the terminal contract holds — no decode probe for a declared status).
  5. "Classified exactly once": an auth-capture consumer is invoked **exactly
     once** across a decode-retry that eventually succeeds (consumer counter).
  6. Async twin: `200` malformed → `200` good, `CompletableFuture` method →
     `join()` returns the value, 2 requests.

## Consequences

- **New public surface:** `RetryPolicy` builder attribute + `@Retry` attribute
  `retryDecodeFailures` (default `false`), and one package-private probe seam
  (test-only).
- **Zero behavior change** for every existing user: retry is off by default,
  and even with retry on the new flag defaults off, so the success path is
  untouched unless the user opts into both.
- **The last substantive known gap closes** — after this, the 0.7.0 cycle
  packages a complete retry contract (transport + status + decode) with only
  the accepted non-goals / micro-gaps remaining.
- **Docs:** ADR-0008 Known gaps payload-decode line struck through (closed,
  pointer here); ADR-0009 candidate 4's "LEAVE" annotated "reopened by
  ADR-0011"; README "Retry with backoff" gains a decode-failure paragraph;
  `RetryPolicy` / `@Retry` / `ModuleInfo` Javadoc gain the decode axis
  (sync/async uniformity, as for the other axes).
- **Implementation roadmap (the epic, this ADR is step 1):**
  1. *(this)* concept ADR-0011.
  2. `RetryPolicy.retryDecodeFailures` + `@Retry` attribute + the
     `probePayloadDecode` seam + unit tests.
  3. Sync wiring (`executeWithRetries`) + the decode DEBUG line + integrations
     (scenarios 1–5).
  4. Async twin (`attemptAsync`) + integration 6.
  5. Javadoc contract + README + final gate + ROADMAP distillation.

## Known gaps (new, from this design)

- An `@OnError` status whose **error body** fails to decode is *not*
  decode-retried (the probe returns `true` for a declared status — the
  terminal / `retryOnErrorStatuses` contract governs it, orthogonal to the
  decode axis). Documented, non-blocking.
- The final response's payload is decoded twice (probe + `processResponse`)
  when retry is enabled and the flag is on — a bounded, final-response-only,
  opt-in cost (Aspect 2).
