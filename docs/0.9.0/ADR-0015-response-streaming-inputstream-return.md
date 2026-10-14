# ADR-0015: Response streaming — the `InputStream` return type

> 2026-10-14 · Proposed · concept step of the "Response streaming: the
> `InputStream` return type" epic (DRAFT, 6 steps). Implementation follows in
> Steps 2–5; this ADR is the source of truth for the design decisions, the
> test strategy, and the Javadoc contract.

## Context

jRestly's first ergonomics epic for modern APIs — the opening slice of the
maintainer's 2026-10-14 mailbox direction 3 ("request parameter flexibility,
data streaming, LLM, request pipeline tuning"), narrowed to the byte-stream
slice: the LLM/line slice (SSE) is deferred to ADR-0016 and built on this
seam; parameter flexibility and pipeline hooks stay in later cycles. The
`docs/0.9.0/` folder carries the **provisional** 0.9.0 label (the ADR-0014
precedent: the label precedes the cycle ADR that fixes the version); the
minor rationale — a new accepted return type is new public surface
(ADR-0012 D1's artifact-level rule). ADR-0015 is a **feature ADR**: the
release-cycle ADR (packaging) comes after the cycle's features close, per
the ADR-0012 precedent.

Why streaming is the opening slice (rather than parameters or the pipeline):

- **It is the engine's gap.** Every response is materialized in heap today:
  `send()` / `sendAsync()` are pinned to `BodyHandlers.ofByteArray()`, and
  `WireResponse` is a `byte[]`. A 2 GB download occupies 2 GB of heap before
  the method returns; even a `String` return travels `byte[]` → `String`.
- **It is a clean new surface**: a new accepted return type, zero behavior
  change for existing calls — a classic minor.
- **It makes the implicit JSON dependency visible** (the maintainer's
  2026-10-14 caveat "an implicit one [on JSON serialization] is still
  there"): today every non-`String`/non-`void` return type silently binds
  the call to the JSON codec (`processResponse` →
  `jsonCodec.deserialize`). The codec is already a clean SPI surface
  (ADR-0001/0007); streaming makes the dependency **declared by the return
  type**: a POJO declares codec binding, `InputStream` declares byte
  binding.

Frame constraints from direction 1 (limit, not feature): zero-dep — the
stream comes from the JDK's own `BodyHandlers`, no new SPI surface;
transparency (ADR-0004) — the method still returns "the thing itself", now
a byte stream; anti-combination (ADR-0003) — no wrapper type, no
interceptor combinator.

Facts verified 2026-10-14 against the JDK 21 javadoc (Oracle docs):

- `BodyHandlers.ofInputStream()`: "the response headers will have been
  completely read, but the body may not have been fully received yet"; the
  body is read from the returned `InputStream` "as it is received".
- `BodySubscribers.ofInputStream()` API note: the caller must either read
  until EOF or call `close()`; "calling `close` before exhausting the
  stream may cause the underlying HTTP connection to be closed and prevent
  it from being reused". Implementation note: a `read` on an interrupted
  thread throws `IOException` (interrupt status set), the request is
  cancelled, and the stream is closed.
- `HttpRequest.Builder.timeout`: fires "if the response is not received
  within the specified timeout" — for `ofInputStream` the response exists
  once the headers are read, so the timeout (the template's socket timeout)
  bounds **time to headers**; the body read is outside its scope.

## Decisions

### Aspect 1: Surface — the return type is the contract

- `InputStream` as a controller method's return type means "return the
  response body as a byte stream". No new annotation: the return type is
  the contract (ADR-0004), with the `String`, `void`, and
  `CompletableFuture<T>` precedents.
- The async twin is `CompletableFuture<InputStream>` — the established
  carrier; the future completes after the headers are read, with the
  stream.
- Rejected — a marker annotation (`@StreamBody`): a new annotation is a
  new validation surface for a distinction the return type already makes.
- Rejected — a custom wrapper type (OkHttp-`ResponseBody` style):
  "Response wrappers — permanently out" (ADR-0003). `InputStream` is the
  JDK's own payload abstraction — the body itself, not a wrapper around it.
- Bytes only in this epic: no line events (`Stream<String>` / SSE) — that
  is ADR-0016.

### Aspect 2: Engine — `BodyHandlers.ofInputStream()`, no `byte[]` copy

- The send path branches on the effective return type (the `CompletableFuture`
  type argument unwrapped, as the async detection already does):
  `InputStream` gets `BodyHandlers.ofInputStream()`, everything else stays
  on `ofByteArray()`. No new dependency, no hot-path allocation growth (the
  per-response record allocation is unchanged).
- Wire model: `WireResponse` gains a fourth component `InputStream stream` —
  **exactly one of `body` / `stream` is non-null**, the existing
  `WireRequest` nullable-component style ("unused for multipart requests").
- **ABI-neutral**: a secondary 3-argument constructor
  `WireResponse(int, List<Header>, byte[])` delegating to the 4-argument
  canonical constructor. The existing call sites (one in main, three in
  tests, verified) and pre-compiled callers keep working; the added accessor
  is binary-compatible. The record keeps its house label — internal wire
  model.
- **Intermediate hops close their stream**: a redirect hop's
  (301/302/303/307/308) and a retried attempt's stream body is single-use —
  closed before the next hop / attempt (connection hygiene, no leak). Only
  the final response's stream is handed to the caller or consumed by the
  error path (Aspect 3).
- Redirect and retry logic are otherwise shape-unchanged: the loop
  classifies on the status, which is known once the headers are read — no
  body read before classification.

### Aspect 3: Contract — the codec is a property of the return type

- Success path: an `InputStream` return **bypasses the JSON codec** — no
  `deserialize`, no `prettyPrint`. The documented stance: **JSON by return
  type** — a POJO return declares codec binding, an `InputStream` return
  declares byte binding.
- The error path keeps the codec (uniformity, ADR-0004 "errors travel as
  exceptions"): an `@OnError` status on a stream method **consumes the
  stream fully**, decodes the error object, and throws `HandledException`
  (object + status + headers, as today). An unexpected status consumes the
  stream and carries the **full** raw payload in `UnexpectedStatusException`
  — uniform with the `byte[]` path; error bodies are small in practice
  (a payload cap is a revisit-on-demand, not a 0.x default).
- Decode-retry (ADR-0011) is a no-op for stream methods:
  `probePayloadDecode` treats an `InputStream` return as "the payload decode
  cannot fail" (mirroring the `String` return) — nothing is decoded,
  nothing is retried on the decode axis.
- Known gap (named, not solved): codec resolution stays **eager and
  fail-fast at build time** — a client that only does streaming / `void` /
  `String` calls still needs a codec on the classpath or an explicit
  `jsonCodec(...)`. Lazy codec resolution is a future epic.

### Aspect 4: Lifecycle — the JDK contract is inherited and documented

- The caller owns the stream: read until EOF or `close()`; an early
  `close()` may end the connection (no reuse) — the JDK 21 API note.
  jRestly adds nothing on top: no finalizer, no wrapper (Aspect 1).
- The template's `HttpRequest.timeout` (the socket timeout) bounds time to
  headers; the body read is outside the timeout (the verified javadoc pair)
  — a stalled body blocks the reading thread until the network resolves
  (OS TCP timeouts). Accepted JDK behavior, documented in the Javadoc
  contract, not a jRestly defect.
- Interrupts: a `read` on an interrupted thread throws `IOException`
  (interrupt status set) and cancels the request (the JDK implementation
  note) — the failure surfaces at the caller's read site as the plain JDK
  `IOException`, not wrapped by the template's dispatch-time
  `RuntimeException` wrap (that wrap covers dispatch; read-time failure is
  `InputStream` contract territory).
- The engine has no HEAD (`HttpMethod` = GET/POST/PUT/PATCH/DELETE,
  verified) — no empty-stream special case.
- `@SetAuthDetails` capture, `@FollowRedirects`, and `@HttpVersion` all
  operate on the request side and the response headers — unchanged.

### Aspect 5: Logging

- The request side is unchanged. The response side: `logResponse` (status,
  headers, elapsed) runs as-is; the **payload line is skipped** for stream
  responses — logging must not consume the body. When a `Content-Length`
  response header is present it is logged ("streaming, Content-Length: N"),
  otherwise "streaming (length unknown)". No per-byte logging.

### Aspect 6: Retry (interaction with ADR-0008/0010/0011)

- Transport retry (`IOException` at dispatch): unchanged — the loop retries
  before the body matters.
- Status retry: the status is known once the headers are read; the retried
  attempt's stream is closed before the next attempt (Aspect 2).
  `Retry-After` parsing (ADR-0009/0010) is unchanged — it reads headers.
- The decode axis: no-op (Aspect 3). The `@OnError` terminal-status scope
  (`retryOnErrorStatuses`) is unchanged — classification precedes the body.

## Shape (seams the implementation touches)

- `WireResponse`: the `stream` component + the secondary 3-arg constructor
  (Aspect 2).
- `HttpTemplate`: a `streamBody` flag (set by the builder from the effective
  return type); `send()` / `sendAsync()` pick the body handler;
  `toWireResponse` fills the stream component; `executeWithRedirects` /
  `executeWithRetries` and their async twins close intermediate streams;
  `processResponse` gains the stream branch (after auth capture and
  `logResponse`; before the `String` check) — the error paths consume via
  `readAllBytes()`; `probePayloadDecode` gains the stream predicate; the
  response payload log line gains the streaming variant (Aspect 5).
- `HttpTemplateBuilder`: the effective-return-type detection for the flag.
- `ControllerValidator` / Javadoc / README: Step 5.

## Test strategy (WireMock suite, house gates)

1. Basic: byte-exact download, including a 4 MiB body (the 0.6.0 streaming
   test precedent); the caller reads to EOF.
2. The async twin: a `CompletableFuture<InputStream>` call, the same bytes.
3. Redirect: a 307 with a stream — the intermediate body is closed, the
   final stream is intact.
4. Error paths: an `@OnError` status on a stream method — the stream is
   consumed, the error object is decoded, `HandledException` carries object
   + status + headers; an unexpected status carries the full raw payload.
5. Retry: a retryable status on a stream method — the attempt's stream is
   closed, the retry succeeds.
6. Codec bypass: a client with a probe codec that throws from
   `deserialize` — a stream call succeeds (the success path never touches
   the codec).
7. Read failure: a server disconnect mid-body — `IOException` at the read
   site.
8. Validation + docs: a controller with an `InputStream` return validates
   cleanly; the Javadoc contract on the touched public surface (house
   style, concise).

## Consequences

- The first ergonomics slice opens the 0.9.0 cycle; the cycle continues
  with ADR-0016 (SSE / line events for LLMs, built on this seam) and,
  later, parameter- and pipeline-flexibility slices — direction 3 stays in
  the DRAFT active zone.
- `WireResponse` (internal wire model) gains a component and a secondary
  constructor — ABI-neutral, source-compatible across the tree (three
  call sites verified).
- The `InputStream` return joins the documented return-type contract:
  `String` (raw text), `void`/`Void` (none), `CompletableFuture<T>` (async),
  POJO (codec-bound), `InputStream` (byte stream, codec-free).
- `build.gradle` stays at 0.8.0 until the release-cycle gate step
  (ADR-0012 D2); both 0.7.0/0.8.0 changelog entries remain unpublished.
