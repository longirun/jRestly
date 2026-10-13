# ADR-0009: 0.x polish — carry-over quirks and accepted known gaps

> 2026-10-13 · Proposed · concept step of the "0.x polish — carry-over quirks and
> accepted known gaps" epic (DRAFT). This ADR is a **scope decision**: for each of
> the four documented imperfections it settles fix vs leave, the shape of the fix,
> the wire/behavior-contract impact, and the test strategy. Implementation follows
> in the subsequent DRAFT steps. Per the ADR-0003 rule, reopening a known gap
> requires a new ADR — this is that ADR for the two gaps it touches.

## Context

After the retry epic (ADR-0008) four documented imperfections remain. Two are
"accepted, non-blocking" carry-over quirks recorded in the ROADMAP (0.5.0 cycle);
two are Known gaps of ADR-0008. All four are non-blocking, which is exactly why
they were accepted — the purpose of this ADR is to decide which ones to actually
fix during 0.x and which stay on the books.

1. **PUT request-body logging skip** (ROADMAP 0.5.0 carry-over).
2. **`void.class` not checked in `processResponse`** (ROADMAP 0.5.0 carry-over).
3. **`Retry-After` HTTP-date form** (ADR-0008 Known gap: v1 honors integer
   seconds only).
4. **Payload-decode failure on the final response is not retried** (ADR-0008
   Known gap: v1 retries transport and status only).

What the code shows (all four verified against the sources on 2026-10-13, not
copied from the ROADMAP wording):

- **Candidate 1:** `HttpTemplate.logRequest` gates the entire request-body
  section on `Arrays.asList(POST, PATCH, DELETE).contains(method)`. A PUT with a
  body logs the method line and headers but neither the body text nor even the
  "No request body" fallback. GET and HEAD also skip the section. The gate is
  evaluated on every request (`logRequest` is called once per `exchange()`
  call, before the retry loop — so retries never duplicate the request log).
  The `Arrays.asList(...)` call allocates a list per request on the hot path.
- **Candidate 2:** `HttpTemplateBuilder.resolveReturnType` passes a primitive
  `void` through verbatim (`method.getGenericReturnType()` of a `void`
  controller method is `void.class`, not a `ParameterizedType`, so
  `setReturnType(void.class)`). In `processResponse` the empty-payload early
  return (204 / empty 200) fires **before** the codec check, which is why the
  only existing test — `void getNothing()` against an empty body — passes.
  A `void` method with a **non-empty** body on an expected status then misses
  the `Void.class.equals(returnType)` check and reaches
  `jsonCodec.deserialize(payload, void.class)` — the codec is asked to decode
  into the primitive `void`, a type the user never wrote. The failure mode is
  **codec-dependent** (verified against all three bundled codecs on
  2026-10-13): Jackson 2.18.2 is lenient and decodes `void.class` to `null`
  (the quirk stays silent under the default codec), while Gson 2.14.0 throws
  `IllegalArgumentException: GSON (2.14.0) cannot handle void` and Moshi
  1.15.2 throws `IllegalArgumentException: Cannot serialize abstract class
  void` — under a Gson/Moshi classpath a `void` method whose server sends a
  non-empty body breaks outright, under Jackson it silently rides on codec
  luck. `returnType` is consumed nowhere else in the library (only the
  String/Void/codec branches of `processResponse`).
- **Candidate 3:** `HttpTemplate.retryAfterSeconds` parses only
  `Long.valueOf(value.trim())`; the HTTP-date form throws
  `NumberFormatException` → `null` → the header is treated as absent and the
  wait is pure jitter.
- **Candidate 4:** both retry loops (`executeWithRetries`,
  `executeAsyncWithRetries`) wrap `executeWithRedirects` only; `processResponse`
  — and hence the codec — runs after the loop ends (sync tail, async
  `thenApply`). A codec exception on the final response propagates without a
  retry.

## Decisions

### Candidate 1: PUT request-body logging — FIX

**The body section is logged for POST, PATCH, DELETE and PUT.**

- **Why fix:** PUT is the most standard body-carrying method in this library's
  set (idempotent full replacement). Excluding it while including DELETE — whose
  body is legal but rarer — is an oversight, not a design choice; no RFC-based
  argument supports hiding PUT bodies.
- **List scope:** GET/HEAD stay out deliberately (RFC 9110 allows a GET body but
  leaves its semantics undefined; a HEAD body is surprising). Non-goal: the list
  is not an exhaustive "methods that may carry a body" — it is a logging
  convenience, and 0.x fixes the named quirk, not invents a new policy.
- **Shape of the fix:** replace the per-request `Arrays.asList(...)` with a
  `private static final Set<HttpMethod> BODY_LOGGING_METHODS = Set.of(POST,
  PATCH, DELETE, PUT)` — one behavior change (PUT) plus one allocation removal
  (the list is currently rebuilt on every request; `logRequest` is on the
  hot path of every call, and the SYSTEM PROMPT requires allocation control
  there). The logic inside the gate is method-agnostic, so PUT inherits the
  multipart parts listing + send size and the plain-body / "No request body"
  branches for free.
- **Contract impact:** logging only (INFO level). No wire change, no public
  API, no POM — the least risky of the four candidates.
- **Test strategy:** `logRequest` is `protected` and takes a `WireRequest`
  directly — unit-testable without WireMock: a capturing `System.Logger`
  (installed through the existing public `setLogger` seam) plus direct
  `WireRequest`s — PUT with JSON body (log contains the body), PUT without
  body ("No request body"), PUT multipart (parts listing + size), GET with
  body (no body section — pins the scope decision), POST/PATCH/DELETE (existing
  behavior pinned if not already). One integration test in the WireMock suite
  for end-to-end: PUT with a body is accepted and the log contains the body.

### Candidate 2: `void.class` in `processResponse` — FIX

**The check becomes `Void.class.equals(returnType) || void.class.equals
(returnType)`, in its current position after the `String` check** (order is
correctness-irrelevant — `void`/`Void` is never `String` — so the diff stays
one line).

- **Why fix:** `void foo()` is the most natural Java way of saying "this method
  returns nothing"; today its survival depends on the server happening to send
  an empty body. The primitive and the boxed form mean the same thing at this
  point: a `Void` method with a non-empty body already returns `null` (body
  silently discarded) — a `void` method should behave identically, not throw a
  codec exception.
- **Shape of the fix:** the local condition in `processResponse`. The
  alternative — normalizing `void.class` → `Void.class` in
  `resolveReturnType` — was rejected: it would change what `getReturnType`
  observes and spreads the decision to the builder, while `returnType` is
  consumed only by `processResponse`'s three branches. One fix covers both
  circuits: `processResponse` is the shared tail of sync and async
  (ADR-0008).
- **Contract impact — the one real behavior change in this ADR:** a `void`
  method + non-empty body + expected status currently depends on the codec
  (silent `null` under Jackson; `IllegalArgumentException` under Gson/Moshi)
  and afterwards always returns normally (`null`, body discarded — identical
  to the `Void` form, codec-independent). It is hard to imagine a user pinning
  a codec failure that names `void`. Reopening the "accepted quirk" is
  precisely the point: the ROADMAP called it non-blocking, not correct. The
  `@OnError` and unexpected-status branches fire before this line and are
  untouched.
- **Test strategy:** reproducer — a `void` controller method against a 200
  with a non-empty JSON body; under the default Jackson codec it passes even
  before the fix (Jackson's leniency — see Context), so the pin's value is
  that the passing is now the library's contract, not the codec's accident.
  Symmetry pins: `CompletableFuture<Void>` + non-empty body → `null` (async
  twin, the sync/async uniformity precedent) and the `Void` boxed form +
  non-empty body → `null` (a new fixture method — `getNothingBoxed` — since
  no boxed-`Void` method existed).

### Candidate 3: `Retry-After` HTTP-date form — FIX

**Both RFC 9110 forms are honored: `delta-seconds` (as today) and `HTTP-date`
(IMF-fixdate / RFC 1123).**

- **Why fix:** the v1 gap is real (a server sending the date form is silently
  ignored), the fix is local to one parser plus the seam's unit, and the blast
  radius is small — retry is off by default, so only users who opted in observe
  any change.
- **Parsing:** `DateTimeFormatter.RFC_1123_DATE_TIME` covers IMF-fixdate and
  rfc1123 (same grammar, case-insensitive day/month names); it yields an
  absolute instant. The wait is computed relative to now: `date − now`, **floored
  at 0** (a past date means "you may have retried already" — no wait beyond the
  backoff bound). This mirrors the integer form's effective semantics: a
  negative/zero bound never shrinks `Math.max(bound, …)`.
- **Shape of the fix:** the seam keeps its shape but unifies on milliseconds —
  the natural unit of the wait:
  - `HttpTemplate.retryAfterSeconds(response)` becomes
    `retryAfterDelayMillis(response)`: integer form → `seconds × 1000`
    (unchanged, still deterministic); HTTP-date → `max(0, dateInstant −
    nowInstant)`; absent / null-valued / unparseable → `null` (pure
    jitter; the null-valued corner used to NPE on `value.trim()` — now
    normalized to absent);
  - `RetryPolicy.nextDelayMillis(failedAttempt, Long retryAfterMillis,
    random)` — the body is unchanged (`Math.max(bound, retryAfterMillis)` when
    non-null), only the parameter's name, unit and Javadoc move. The seam is
    package-private → no ABI break; the existing unit tests update their
    argument (the determinism property is what they pin, not the unit).
  - The parameter stays `Long` (not `Duration`): the seam's contract is
    "header-derived lower bound or null", and `Long` milliseconds keeps the
    existing `null`-absence encoding without a new wrapper allocation.
- **Obsolete forms (RFC 850 and asctime(3)):** deliberately not accepted.
  RFC 9110 marks them obsolete; asctime carries no zone (receivers "may"
  treat it as UTC), and accepting them means a custom formatter plus a zone
  assumption for a dying form. Treated as unparseable → `null`, recorded as
  a micro-gap below. *(Superseded by ADR-0010: the micro-gap actually
  covered BOTH obsolete forms — `RFC_1123_DATE_TIME` accepts IMF-fixdate
  only — and both are now parsed; the RFC prescribes the asctime zone (UTC)
  and requires recipients to accept all three formats.)*
- **Contract impact:** a previously ignored header form now sets a
  deterministic lower bound on the wait — for users with retry enabled whose
  server sends the date form, the wait becomes `max(bound, date − now)` instead
  of pure jitter. Strictly longer, and in the direction of the server's
  intent ("do not come back before X"). Off-by-default users: zero change.
- **Test strategy:** parser units (RFC 1123 form → correct positive delta; past
  date → 0; integer form unchanged; garbage and asctime → `null`; header name
  case-insensitive and value trimmed — as today; the bound/`max` interaction
  re-pinned in `RetryPolicyTest` with the new unit). Integration: a WireMock
  response carrying `Retry-After: <IMF-fixdate ~2 s in the future>` → request
  count correct and elapsed ≥ 2 s (wall-clock precedent from the existing
  integer-seconds `RetryTest` — same flake class, kept to one test).

### Candidate 4: payload-decode retry — LEAVE (reopened by ADR-0011)

**The gap is reaffirmed, not reopened: not fixed in 0.x.**

- **Why leave:**
  1. **The transient cases are already covered by transport retry.** A
     truncated / mid-stream body surfaces as an `IOException` from
     `HttpClient` — retried by v1. What remains is "the server deliberately sent
     these bytes and the codec does not understand them" — typically a
     deterministic mismatch (codec version drift, wrong `Content-Type`), where a
     resend is unlikely to help.
  2. **It collides with "classified exactly once."** Retrying the decode would
     mean moving the codec (or the `processResponse` tail) inside the loop —
     but ADR-0008 Aspect 3 exists precisely because auth capture, logging and
     `@OnError` decoding run exactly once on the final response. To retry decode
     failures while *not* retrying a successfully decoded `HandledException`
     (the contract is already fulfilled), the loop would need a new
     failure-kind distinction — more loop state for a rarely reached path.
  3. **0.x discipline.** The gap is accepted and documented; the failure
     surface is a typed codec exception (no new exception class). If it ever
     gets reopened, it gets its own ADR per ADR-0003 — for now it simply is not
     reopened.
- **Contract impact:** none (status quo).
- **Test strategy:** no new tests; ADR-0008's Known-gaps line is updated to
  point at this decision.

## Consequences

- ROADMAP 0.5.0 line "Known carry-over quirks (accepted, non-blocking)": both
  quirks are fixed — the line is struck through (or removed) in the epic's final
  gate distillation, with a pointer here.
- ADR-0008 Known gaps: the HTTP-date line is **closed** (fixed per this ADR);
  the payload-decode line is **reaffirmed** with a pointer here (later
  reopened by ADR-0011); one new micro-gap is added (asctime — scope
  corrected and closed in ADR-0010: it covered both obsolete forms, and they
  are now parsed).
- README "Retry with backoff": the phrase "an integer-seconds `Retry-After`
  header sets a deterministic lower bound" becomes "a `Retry-After` header
  (delta-seconds or HTTP-date) sets a deterministic lower bound".
- `RetryPolicy.nextDelayMillis` Javadoc and the `RetryPolicyTest` unit tests
  move to milliseconds (package-private seam — test-only churn).
- **User-visible behavior changes, exactly three:** (a) PUT bodies appear in the
  INFO request log; (b) a `void` method with a non-empty body no longer throws
  a codec exception; (c) the HTTP-date form of `Retry-After` now bounds the
  retry wait — only for users who opted into retry. All three move in the
  direction the documentation already implied.

## Known gaps (accepted, updated)

- ~~The **asctime** form of `Retry-After` (obsolete per RFC 9110) is not
  parsed — treated as absent (pure jitter).~~ — **closed per ADR-0010:**
  both obsolete forms (RFC 850 + asctime(3) — the scope correction is
  there) are now parsed.
- A payload decode failure on the **final** response is not retried (reaffirmed
  from ADR-0008; see Candidate 4 above).
- All other ADR-0008 Known gaps stand unchanged.
