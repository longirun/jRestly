# ADR-0036: Client Metrics & Observability SPI (Micrometer / OpenTelemetry Readiness)

## Status
Accepted

## Context
When adopting a declarative HTTP client in production microservices and enterprise integration pipelines, observability is a primary non-functional requirement. Engineering teams must track:
1. **Request latencies & call rates** (e.g. Micrometer `Timer` or OpenTelemetry HTTP client semantic conventions: `http.client.request.duration`);
2. **Active in-flight requests** (Micrometer `Gauge`: incremented on request initiation, decremented upon completion);
3. **Retry attempts & failure causes** (counters indicating how often `@Retry` is triggered and why);
4. **Per-attempt wire latencies and data volumes** (request body bytes uploaded, response body bytes downloaded);
5. **Distributed tracing integration** (starting and concluding tracing spans linked to the HTTP client call).

Previous versions of jRestly introduced `RequestInterceptor` and `ResponseInterceptor` (ADR-0018, ADR-0029). While valuable for request decoration and logging, `ResponseInterceptor` has fundamental limitations for observability:
- It only triggers once the entire logical call completes. It cannot track when a request begins, making in-flight gauges and tracing span lifecycle management awkward.
- Intermediate retry attempts (under `@Retry`) are invisible to `ResponseInterceptor` because it strictly observes the "classified exactly once" principle (ADR-0008).
- It carries no information about attempt counts, retry delays, or wire payload byte counts.

At the same time, jRestly maintains its foundational architectural principle:
**Zero-dependency core** (ADR-0001, ADR-0003, Java 21 stdlib only). The library must not depend on `io.micrometer:micrometer-core` or `io.opentelemetry:opentelemetry-api` in `implementation` dependencies.

## Decision
Introduce a zero-dependency, high-performance Client Metrics & Observability SPI in `ru.jrestly.metrics`:

1. **`ClientMetricsListener` Interface (`ru.jrestly.metrics.ClientMetricsListener`):**
   - `@FunctionalInterface` where the primary method is `void onCallComplete(CallMetrics metrics)`.
   - Default lifecycle methods:
     - `default void onCallStart(RequestContext context, WireRequest request)`: invoked before placing the first request on the wire.
     - `default void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay)`: invoked when an attempt fails and a retry backoff is scheduled.
     - `default void onAttemptComplete(RequestContext context, WireRequest request, int attempt, Optional<WireResponse> response, Optional<Throwable> failure, Duration duration)`: invoked when an individual transmission attempt completes across the wire.
   - Static factory `static ClientMetricsListener composite(ClientMetricsListener... listeners)`.

2. **`CallMetrics` Model (`ru.jrestly.metrics.CallMetrics`):**
   - Immutable view of the completed HTTP call:
     - `RequestContext context()`: module name, controller class, method, args, and custom attributes.
     - `WireRequest request()`: initial wire request.
     - `Optional<WireResponse> response()`: final wire response, if any.
     - `Optional<Throwable> failure()`: final failure exception, if any.
     - `Duration duration()`: total elapsed call time.
     - `int attempts()`: total attempts executed.
     - `long requestBytes()`: uploaded body byte size (-1 if unknown).
     - `long responseBytes()`: downloaded body byte size (-1 if unknown).
     - Convenience helper methods: `statusCode()`, `isSuccess()`, `isRetried()`, `method()`, `uri()`.

3. **Declarative Annotation `@WithMetrics` (`ru.jrestly.annotation.WithMetrics`):**
   - Applicable to controller interfaces (`ElementType.TYPE`) and methods (`ElementType.METHOD`).
   - Binds a `Class<? extends ClientMetricsListener>` with an accessible no-argument constructor.
   - Merges with client-level listener using `ClientMetricsListener.composite(...)`.

4. **Client & Module Configuration:**
   - `ModuleInfo.getMetricsListener()` default returns `null`.
   - `SimpleModuleInfo.Builder.metricsListener(ClientMetricsListener listener)` (NPE fail-fast, composite chaining).
   - `JRestlyClient.Builder.metricsListener(ClientMetricsListener listener)` (NPE fail-fast, composite chaining).

5. **Upfront Validation (`ControllerValidator`):**
   - Validates that classes referenced in `@WithMetrics` implement `ClientMetricsListener`, are concrete (not interface/abstract), and have an accessible no-arg constructor.

6. **Safety & Fault-Tolerance:**
   - Exceptions thrown by `ClientMetricsListener` callbacks are caught and logged at `WARNING` level, never disrupting the business flow of the HTTP call.

## Consequences
- Full production observability readiness for Micrometer, OpenTelemetry, Prometheus, and custom telemetry systems with zero external dependencies.
- Precise monitoring of retries, in-flight gauges, and wire byte sizes.
- 100% backward compatibility: existing configurations with no listener incur zero overhead.

## Amendment #193

2026-10-17 · Session #193 (audit slice: ADR-0036 × ADR-0035, streaming request
bodies under an active metrics listener).

Section 2 declares `requestBytes()` as «uploaded body byte size (-1 if
unknown)». The original byte-accounting in `HttpTemplate.notifyCallComplete`
probed a supplier-rendered body by invoking
`request.bodyPublisherSupplier().get()` and reading the resulting publisher's
`contentLength()`. For the ADR-0035 first-class streaming bodies
(`Supplier<? extends InputStream>`, `Supplier<? extends Flow.Publisher>`,
`Supplier<? extends BodyPublisher>`) the supplier wraps the **user's
producer**, so the probe ran the user's code one extra time per logical call
(at call completion, after the real send), swallowed its exceptions
(`catch (Throwable ignored)`), and — for `Supplier<InputStream>` — still
reported `-1`, since `BodyPublishers.ofInputStream(...).contentLength()` is
unknown by definition. The established contract for such bodies is
«invoked once per transmission» (guarded by
`RequestBodyStreamingWireMockTest.testUploadSupplierAcrossRetry`), and
ADR-0035 §3 already requires the hot path not to consume live streams
(`logRequest` logs `<streaming body>` without touching the supplier).
With a metrics listener active the Supplier body was thus materialized
**twice** — a hidden side effect and double production cost, in tension with
the «no listener → zero overhead» spirit of the SPI.

Resolution: `computeRequestBytes` no longer invokes the supplier —
supplier-rendered (streaming) bodies are reported as unknown (`-1`), the same
treatment multipart bodies already had. Buffered `byte[]` bodies keep the
exact measurement; response accounting is unchanged (buffered body length,
then `Content-Length`, else `-1`). The seam is package-private and unit-gated
(`HttpTemplateMetricsBytesTest`); the behavioral contract is e2e-gated
(`RequestBodyStreamingWireMockTest.testUploadSupplierWithMetricsListener`).
Known refinement (non-goal here): file-backed `Path`/`File` bodies report
`-1` instead of the file size while a listener is active; the size is only
known to the engine at send time (candidate for engine-reported byte
accounting).

## Amendment #194

2026-10-17 · Session #194 (audit slice: ADR-0036 × ADR-0046, the
transparent 401-refresh flow under an active metrics listener).

Section 2 declares `attempts()` as «total attempts executed». The 401
refresh flow of ADR-0046 performs **two** transmission phases: the initial
`executeWithRetries`/`executeAsyncWithRetries` phase and — when the response
is 401 and `retryOnUnauthorized` is on — a second phase after
`tokenManager.invalidate()`. Both phases shared the same attempt counter
(`int[] attemptsHolder` on the sync circuit, the `AtomicInteger attemptsRef`
on the async circuit), and the second phase **overwrote** the first phase's
count. A listener thus saw `attempts() == 1` (and `isRetried() == false`)
for a call that physically went to the wire twice — and, with `@Retry`,
reported only the refresh phase's count instead of the sum
(e.g. 503 → 401 → 200 reported 1 instead of 3).

Resolution: both circuits now sum the phases — the sync path captures the
first phase's count before the second `executeWithRetries` and adds it to
the holder afterwards; the async path captures the first phase's count in
the 401 branch and, on the second phase's completion (success **or**
failure), adds it to `attemptsRef` before the terminal stages read it.
`CallMetrics.attempts()` javadoc now states the total explicitly includes
the transparent 401-refresh phase. E2E-gated:
`ClientMetricsWireMockTest` `refresh401SumsAttemptsAcrossPhases` (sync),
`refresh401AfterRetriesSumsBothPhases` (sync, retry loop + refresh = 3),
`refresh401SumsAttemptsAcrossPhasesAsync` (async). Known refinement
(non-goal here): the per-transmission `onAttemptComplete`/`onRetry`
numbers restart at 1 in the refresh phase (per-phase, not per-call) —
resolved in Amendment #196.

## Amendment #195

2026-10-17 · Session #195 (audit slice: ADR-0036 × ADR-0047, the
short-circuit path under an active metrics listener).

ADR-0047's Network Bypass clause declares «Number of network attempts is
tracked as `0`» for a short-circuited call — true on the internal counters
(`attemptsHolder[0] = 0` / `attemptsRef.set(0)`) — yet the listener-visible
`attempts()` reported **1**: `HttpTemplate.notifyCallComplete` clamped the
count with `Math.max(1, attempts)` to survive the `SimpleCallMetrics`
compact constructor, which rejected `0`. The SPI contradicted itself inside
one logical call: `attempts() == 1` while `onAttemptComplete` fired **zero**
times (no transmission occurred), and `onCallStart` — documented
«immediately before the initial request is initiated» — fired although the
request never reached the wire.

Resolution: the true transmission count is now reported — `attempts()` is
`0` for a short-circuited call (matching ADR-0047 and the number of
`onAttemptComplete` callbacks) and at least 1 otherwise. `SimpleCallMetrics`
accepts `attempts >= 0` (relaxed validation, backward compatible); the
`onCallStart` javadoc states it fires once per logical call, also when the
call turns out short-circuited, pairing 1:1 with `onCallComplete` (in-flight
gauges stay balanced). E2E-gated:
`ShortCircuitExecutionTest.shortCircuitMetricsLifecycle` (sync + async:
onCallStart x1, onAttemptComplete x0, onCallComplete x1, `attempts() == 0`,
`isRetried() == false`) and the updated
`shortCircuitInvokesResponseInterceptorAndMetrics`; unit gate:
`ClientMetricsValidationTest` (attempts = 0 accepted, -1 rejected).

## Amendment #196

2026-10-17 · Session #196 (audit slice: ADR-0036 × ADR-0046, closing the
Amendment #194 non-goal).

Since Amendment #194, `CallMetrics.attempts()` reports the total across both
transmission phases of the transparent 401-refresh flow — but the
per-transmission numbers carried by `onAttemptComplete`/`onRetry` still
**restarted at 1 in the refresh phase** (per-phase, not per-call). The SPI
was inconsistent inside one logical call: for 503 → 401 → 200 a listener
saw `attempts() == 3` while the third `onAttemptComplete` was numbered **1**
again — the last reported number no longer equaled `attempts()`, and attempt
indexes were not unique within a call.

Resolution: an `attemptOffset` (the first phase's transmission count) now
flows through both circuits — `executeWithRetries`/`scheduleRetry` on the
sync path, `executeAsyncWithRetries`/`attemptAsync`/`scheduleAsyncRetry` on
the async path — and the listener-facing numbers are `attempt + offset`
(per-call, continuous). The retry-policy window, backoff computation and the
DEBUG `Attempt N/M` log line stay phase-local: `maxAttempts` bounds each
phase, and the refresh phase starts a fresh backoff. The `attempts`
parameter of `onAttemptComplete`/`onRetry` javadoc now states the per-call
numbering and the invariant «the last value reported for a call equals
`CallMetrics.attempts()`». E2E-gated: `ClientMetricsWireMockTest`
`refresh401SumsAttemptsAcrossPhases` (sync: 1, 2),
`refresh401AfterRetriesSumsBothPhases` (sync 503 → 401 → 200: 1, 2, 3;
onRetry numbers the phase-1 failure),
`refresh401SumsAttemptsAcrossPhasesAsync` (async: 1, 2) and the new
`refresh401AfterRetriesContinuousNumbersAsync` (async 503 → 401 → 200:
1, 2, 3).

## Amendment #258

2026-10-18 · Session #258 (free-audit slice: ADR-0036 × the pre-wire
request-interceptor failure path of `HttpTemplate.exchange` / `exchangeAsync`).

Amendment #195 fixed the short-circuit path, but the sibling pre-wire failure
path — a `RequestInterceptor` that **throws** before the request is placed on
the wire — still broke the SPI inside one logical call. In the unified
dispatch (`HttpTemplate.exchange`), the interceptor-failure `catch` fired
`notifyCallComplete` with `attempts = 1`, yet `notifyCallStart` — declared
«pairs 1:1 with `onCallComplete` (in-flight gauges stay balanced)» and
«also invoked when a request interceptor short-circuits the call» — sat further
down, past the point where the `catch` already rethrows (sync) or returns a
failed future (async). A listener thus saw `onCallComplete` **without** a paired
`onCallStart` (the in-flight gauge underflowed) while `attempts()` reported
**1** for a call that never touched the wire (`onAttemptComplete` fired zero
times) — the same self-contradiction #195 eliminated for short-circuited calls,
one branch over.

Resolution: the interceptor-failure `catch` now fires `notifyCallStart`
immediately before `notifyCallComplete` and passes the true transmission count,
`0` (nothing reached the engine). `onCallStart`/`onCallComplete` are paired 1:1
on every pre-wire failure, `attempts()` equals the number of
`onAttemptComplete` callbacks, and the `onCallStart` javadoc now names the
failing-interceptor case alongside short-circuiting. The normal (non-throwing)
path is untouched: `notifyCallStart` still fires exactly once, at its original
site, before the wire attempt. Unit-gated (sync + async):
`RequestPipelineTest.requestInterceptorFailurePairedMetricsLifecycle` and
`RequestPipelineTest.asyncTwinRequestInterceptorFailurePairedMetricsLifecycle`
(onCallStart x1 before onCallComplete x1, `attempts() == 0`, the interceptor's
own exception preserved). Known refinement (non-goal here): the async circuit
completes the future with a *checked* interceptor exception as-is, while the
sync circuit wraps it in `RuntimeException`; in practice
`RequestInterceptor.intercept` declares no checked exceptions, so only unchecked
throwers reach this path today.

## Amendment #259

2026-10-18 · Session #259 (free-audit slice: ADR-0036 × the pre-wire
rate-limiter / circuit-breaker rejection paths of `HttpTemplate.exchange` /
`exchangeAsync`).

Amendments #195 and #258 established the invariant «`attempts()` equals the
number of `onAttemptComplete` callbacks» for the short-circuit and
interceptor-failure paths, but two pre-wire rejection paths still reported
`attempts() = 1` for a call that never touched the wire: a rate-limiter timeout
(`RateLimiter.acquire()` throwing / `tryAcquire` false) and an open circuit
breaker (`acquirePermission()` throwing / `tryAcquirePermission` false). Both
circuits fired `onCallStart` (1:1, already paired) and `onCallComplete` with a
stale count of `1` (the sync `attemptsHolder` placeholder and the async
`notifyCallComplete(..., 1)` literal) while `onAttemptComplete` fired zero
times — the same self-contradiction #195/#258 eliminated, on the last two
uncovered pre-wire branches.

Resolution: on both circuits the true transmission count `0` is now reported.
Sync: the two pre-wire `acquire` calls are wrapped so that a pre-wire failure
resets `attemptsHolder[0] = 0` before the `finally` reads it. Async: the two
`notifyCallComplete` rejections pass `0`. The `CallMetrics.attempts()` javadoc
now states the full contract — `0` for any call that never reached the wire
(short-circuit, failing interceptor, pre-wire rate-limiter / circuit-breaker
rejection), at least 1 otherwise, and equal to the number of
`onAttemptComplete` callbacks. Circuit-breaker recording is untouched (pre-wire
rejections were already excluded from `recordFailure` by exception type on both
circuits). Unit-gated (sync + async, 4 tests):
`RequestPipelineTest.rateLimiterRejectionPairedMetricsLifecycle`,
`RequestPipelineTest.circuitBreakerOpenRejectionPairedMetricsLifecycle`, and
their async twins (onCallStart x1 before onCallComplete x1, no
`onAttemptComplete`, `attempts() == 0`, no wire request captured).

## Amendment #298

2026-10-19 · Session #298 (backlog tail from Amendment #193:
engine-reported byte accounting for Path/File streaming bodies and
fully-known multipart bodies).

Amendment #193 settled the supplier-rendered bodies (`-1`, no supplier
invocation) but explicitly left a known refinement: file-backed
`Path`/`File` streaming bodies still reported `-1` although the engine
knows the exact size at build time, and fully-known multipart bodies
(every part with a determined length) were also reported `-1` although
the rendered publisher's `contentLength()` is exact by construction.

Resolution:

1. `WireRequest` carries a new, nullable ninth component
   `knownBodySize` (Long) — the body size in bytes when the engine knows
   it at build time. The builder populates it for `Path`/`File`
   `@RequestBody` payloads via `Files.size(path)`; a vanished file degrades
   to unknown (`null`) and the send fails at send time as before. A
   backward-compatible eight-argument constructor is retained (canonical
   constructor is now the nine-argument one; public API addition only).
   Wither semantics are payload-aware: transport withers
   (`withHeader`/`withoutHeader`/`withUri`/`withMethod`/`withContentType`)
   preserve the value; payload withers (`withBody` ×2,
   `withBodyPublisher`, `withBodyPublisherSupplier`) clear it, because the
   replaced payload is not the measured one; `withParts` clears it only
   when non-`null` parts are set. `Redirects.nextHop` preserves it on
   307/308 method-preserving hops and drops it on the GET downgrade,
   mirroring the body/parts/supplier triple.
2. `MetricsNotifier.requestBytes` (the #193 seam, relocated by ADR-0066
   step 6) now resolves, in order: buffered `byte[]` → exact length;
   `knownBodySize` → the engine-known size; multipart (`parts` and
   `boundary` present) → the rendered
   `MultipartBodyPublisher.of(boundary, parts).contentLength()` (the sum
   of part framing plus content, exact when every part's length is
   known); otherwise `-1`. A rendering failure (e.g. a missing multipart
   file) never fails the probe — it degrades to `-1`, matching the
   fault-tolerance rule of the SPI.

Supplier-rendered (`Supplier<…>`-backed) bodies remain `-1`: their size
is only known after materialization, and #193's «do not invoke the
supplier» contract is unchanged. The multipart probe renders the
publisher once per completed call, so it allocates — but only with an
active listener (opt-in), the same cost class as the INFO-level request
log. Caveat: `knownBodySize` is measured at build time; a file modified
between build and send reports the pre-send size (best-effort
accounting, consistent with «size the engine knows»).

Gates: unit — `MetricsNotifierBytesTest` (+5: known size without
supplier invocation, buffered precedence, multipart exact / unknown-part
/ rendering-failure) and `WireRequestTest` (+4: constructors, wither
matrix, parts semantics, builder round-trip); e2e —
`RequestBodyStreamingWireMockTest` (+3: Path body and File body report
the real size to the listener, supplier body stays `-1`).

## Amendment #300

2026-10-19 · Session #300 (free-audit slice: ADR-0036 × the transparent
401-refresh flow, failure leg of the second transmission phase).

Amendment #194 declared «the sync path captures the first phase's count
before the second `executeWithRetries` and adds it to the holder
afterwards; the async path … adds it to `attemptsRef` on the second
phase's completion (success **or** failure)». The two legs were written
asymmetrically: the async accumulation rides a `whenComplete` (success or
failure), but the sync accumulation sat **after** the second
`executeWithRetries` call body. When the refresh phase itself died on the
wire — a transport `IOException` after retry exhaustion or an
`InterruptedException` — the addition was skipped and the `finally`
read the holder at the refresh phase's local count. For a call that
physically went to the wire twice (401 → refresh → connection reset) a
listener saw `attempts() == 1` and `isRetried() == false`, and the
Amendment #196 invariant «the last reported `onAttemptComplete` number
equals `CallMetrics.attempts()`» broke on the failure leg (the second
`onAttemptComplete` was numbered 2, `attempts()` said 1) — the same
self-contradiction #194/#196 eliminated, one exception path over. The
async twin already summed correctly (the `whenComplete` fires on
failure too).

Resolution: the sync path wraps the second `executeWithRetries` in a
`try/finally` so the first-phase count is added to the holder on
completion, success or failure — the exact `whenComplete` semantics of
the async twin. The success leg is value-identical to the old code (the
addition happens before any reader on that path); the pre-wire,
short-circuit and cache-hit legs are untouched. E2E-gated (sync red-first
plus async twin parity): `ClientMetricsWireMockTest`
`refresh401WireFailureStillSumsBothPhases` (sync: 401 → refresh →
connection reset, two `onAttemptComplete` numbered 1, 2,
`attempts() == 2`, `isRetried() == true`, transport failure kept as the
cause) and `refresh401WireFailureStillSumsBothPhasesAsync` (async: the
same scenario sums to 2 — already correct, now pinned).
