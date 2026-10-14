# jRestly Roadmap

## 0.5.0 — zero-dependency core

Plan derived from ADR-0001..0004 (`docs/0.5.0/`). The 0.4.0 improvement plan is complete; its history lives in git.

Gates for every step: WireMock suite green (`./gradlew test`), IDE inspections clean.

## Step 1 — Codec SPI + Jackson adapter (ADR-0001) ✅ DONE

> Done in `80ca2fd` ("remove Jackson dependency (part1)"). Verified 2026-08-24 by code inspection + IDE inspections (clean): `ru.jrestly.json.{JsonCodec, JacksonCodec, JsonCodecResolver}`, Jackson `compileOnly` (not in POM), resolution chain in `SimpleModuleInfo.Builder` (explicit > probe > fail fast), `util/Mapper.java` deleted, `ObjectMapper` out of the public API. Covered by `JsonCodecWireTest`, `JacksonCodecPrettyPrintTest`.

- [x] Internal JSON codec SPI `ru.jrestly.json.JsonCodec` (serialize / deserialize / pretty-print)
- [x] JacksonCodec adapter in the same jar, Jackson as `compileOnly`; compact wire by default, pretty printing only for response logs
- [x] Codec resolution: explicit builder setting > classpath probe (jackson first) > fail fast with an actionable message
- [x] Explicit codec wiring on the builder / ModuleInfo surface replaces ObjectMapper
- Gate: POM contains no Jackson; suite green

## Step 2 — Small zero-dep replacements (ADR-0001) ✅ DONE

> Implemented in the working tree (uncommitted at verification 2026-08-24; HEAD `d87e98c` was a Step-1 follow-up). Verified by code inspection + IDE inspections (clean): `http/Header` record across `AuthProvider`, `AuthHeaderProvider`, `HandledException`, `UnexpectedStatusException`, `SimpleModuleInfo`, `HttpTemplate{,Builder}`; zero `Pair` / commons-lang3 / commons-io / log4j references in `src/main`; `${}` resolver is plain `String.replace` (`HttpTemplateBuilder`); `System.Logger` in `AbstractHttpClient` / `HttpTemplate{,Builder}` with `log4j-core`+`log4j-jul` as `testRuntimeOnly`. Tests updated to `Header` (`AuthTest`, fixtures).

- [x] Own header record replaces commons-lang3 `Pair` across the public API (breaking)
- [x] Own `${}` path-variable resolver replaces log4j `StrSubstitutor`
- [x] `System.Logger` replaces log4j-api; the log4j binding moves to test runtime
- [x] commons-io and commons-lang3 removed
- Gate: POM has no commons / log4j entries; only HC4 (`httpclient`, `httpmime`) remains — it is removed in Step 3; suite green — commons/log4j confirmed out of `build.gradle`; `./gradlew test` run pending on the developer side before commit

## Step 3 — java.net.http engine (ADR-0002) ✅ DONE

> Implemented in the working tree (uncommitted at verification 2026-08-24; HEAD `d7f48a5` was a Step-1 follow-up). Verified by code inspection + IDE inspections (clean, 12 files): wire model `WireRequest`/`WireResponse`/`Param`; HC4 fully gone from `build.gradle` and `src/main` (zero `org.apache.http` imports; `http/HttpDelete.java` and `util/IO.java` deleted); manual redirect loop in `HttpTemplate.executeWithRedirects` on top of `HttpClient.Redirect.NEVER` — 301/302/303 → GET, 307/308 → method+body, hop counter, final response returned (bug fix), relative `Location` via `URI.resolve`; `MultipartWriter` (RFC 7578 framing, CRLF, closing delimiter, UUID boundary) + `MultipartPart.text/file` — the old `getDeclaredField("bodyParts")` reflection hack is gone; urlencoded via `URLEncoder`; connect timeout → `HttpClient.newBuilder`, socket → per-request `.timeout()`, connection-request dropped. Tests: RedirectTest rewritten (302/301/303/307 + hop-limit with `UnexpectedStatusException(302)` surfaced), MultipartTest text-part test added, PathVariableEncodingTest present.

- [x] Internal wire model; template/builder on java.net.http; HC4 vocabulary removed
- [x] Redirect loop: 301/302/303 → GET, 307/308 → method + body, hop counter, final result returned to the caller (bug fix); RedirectTest updated
- [x] Own RFC 7578 multipart writer: file parts + text `@RequestParam` parts; text-part test added
- [x] urlencoded bodies via `URLEncoder`; timeout mapping (connect / per-request); connection-request timeout dropped
- Gate: suite green including updated redirect + multipart tests — 61/61 passed on 2026-08-24, including independent additions: RedirectNoFollowTest (no-follow without the annotation), RedirectMethodTest (307/PUT, 308/PATCH, relative Location), MultipartTest (Path param, Collection text parts)

## Step 4 — Release 0.5.0-rc1 ✅ DONE

> Implemented in the working tree (README + JacksonCodec) and two commits `bb39ada`/`756fe0c` landing Steps 2–3. Verified 2026-08-24: README rewritten for rc1 (engine = `java.net.http.HttpClient`, zero-dep core, "bring your own JSON codec — Jackson 2 autodetected", JitPack coordinate `v0.5.0-rc1` + explicit jackson-databind in all three snippet blocks, SPI note); generated `pom-default.xml` checked — version `0.5.0-rc1`, full metadata, **zero `<dependencies>`**; `JacksonCodec` gained optional jsr310 support (nested `JavaTimeSupport` + classpath probe) so a `jackson-databind`-only consumer works without `jackson-datatype-jsr310` (IDE inspections clean). Smoke project staged at `/tmp/opencode/jrestly-smoke` (mavenLocal + `ru.jrestly:jRestly:0.5.0-rc1` + jackson-databind only; JDK `HttpServer` stub; GET + POST checks; `SMOKE OK` on success) and compiled — but no captured run output; suite green + `SMOKE OK` confirmation pending on the developer/tester side.

- [x] README: single JitPack coordinate, "bring your own JSON codec — Jackson autodetected"
- [x] Version 0.5.0-rc1; POM metadata sanity check — version already bumped in `build.gradle` (commit `80ca2fd`), ahead of Steps 2–3
- [x] Smoke test: fresh Gradle project, jrestly + jackson-databind only, GET + POST against a stub — staged & compiled; `SMOKE OK` run log to be captured by developer/tester
- Gate: smoke passes — confirmed: tester suite 61/61 green on 2026-08-24; cycle closed by `310fd62` + `53f19d9`

## Cycle 0.5.0 — COMPLETE ✅ (2026-08-24)

> Tester suite 61/61 green (incl. tester-added `RedirectNoFollowTest`, `RedirectMethodTest` — 307/PUT, 308/PATCH, relative Location — and extra `MultipartTest` cases). Final commits: `bb39ada`/`756fe0c` (Steps 2–3), `310fd62` (Step 4 finalize + `JacksonCodecDefaultsTest`, `JsonCodecResolverTest`), `53f19d9` (bump 0.5.1). Tags: `v0.5.0`, `v0.5.1`.

Deviations plan → fact:

1. **Version bumped early** — `0.5.0-rc1` in `build.gradle` already at `80ca2fd` (Step 1), ahead of Steps 2–3. No harm: intermediate builds were local-only.
2. **rc1 strategy dropped at release** — instead of tagging `v0.5.0-rc1` the cycle shipped straight as `v0.5.0`, immediately followed by `v0.5.1`. Rationale: tester-green suite + smoke made an RC lap redundant.
3. **jsr310 became optional** (beyond plan) — `JacksonCodec` probes for `jackson-datatype-jsr310` and registers `JavaTimeSupport` only when present, so a `jackson-databind`-only consumer works; verified by smoke.
4. **Test coverage grew beyond plan** — tester's redirect/multipart cases + `JacksonCodecDefaultsTest`/`JsonCodecResolverTest` landed with the finalize commit.

Known carry-over quirks (accepted, non-blocking): ~~`logRequest` body logging skips PUT (POST/PATCH/DELETE only)~~ and ~~`Void.class.equals(returnType)` instead of also checking `void.class` (masked by the empty-payload early return)~~ — both fixed, see the "0.x polish" section below (ADR-0009). Post-0.5.0 shortlist unchanged (ADR-0003).

## Cycle 0.5.2 — HTTP version pinning — COMPLETE ✅ (2026-09-01)

> Documented retroactively (2026-10): the cycle ran through the usual developer/tester separation but was never recorded here. Client-wide default `httpVersion(HttpClient.Version)` on `JRestlyClient.Builder` (delegates to `SimpleModuleInfo.Builder`, `Objects.requireNonNull` fail-fast; `ModuleInfo.getHttpVersion()` defaults to `HTTP_2`), applied on `HttpClient.newBuilder().version(...)` in `AbstractHttpClient`. Per-method override `@HttpVersion(...)` resolves in `HttpTemplateBuilder` into `HttpTemplate` and is applied per-request via `builder.version(...)` when present — a method without the annotation inherits the client-level version. Commit `35f80e9`, tag `v0.5.2`. Tests: `HttpVersionConfigTest` (builder/ModuleInfo contract, NPE fail-fast, defaults), `HttpTemplateVersionResolveTest` (annotation → template resolution), `HttpVersionPinningTest` (WireMock pinning + `@HttpVersion` + redirect-with-version).

## Cycle 0.6.0 — multipart authoring & streaming — COMPLETE ✅ (2026-10-11)

> Source of truth (decisions, stages, verification gates, deviations, status): [`docs/0.6.0/ADR-0005-multipart-authoring-streaming.md`](docs/0.6.0/ADR-0005-multipart-authoring-streaming.md).

Provenance: field findings from a real consumer (2026-10) — N files per request, in-memory parts, part Content-Type.
Diagnosis correction (2026-10-08): file parts DO carry a Content-Type today — always `application/octet-stream`
via `MultipartPart.file()`; the real gap is the hardcoded type, not a missing header. Deeper finding: the whole
multipart path materializes the body in memory (`Files.readAllBytes` + one-body `byte[]`) — no streaming even for
disk files. Wire mechanics that proved correct in the field (no change needed): RFC 7578 framing incl. closing
delimiter, explicit `@Post(requestType = MULTIPART_FORM_DATA)` opt-in, wire filename = disk file name, `void`
return + 204, `HandledException.getStatusCode()` for precise status asserts.

Key decisions (ADR-0005):
- Two buckets: bare `File`/`Path`/`String` = disk sugar, new `FileContent` interface = universal value (the annotation never names a file).
- Streaming body via `BodyPublishers.concat` (exact Content-Length when all parts known-length, chunked otherwise).
- `MultipartWriter.write` removed (breaking — bypass users migrate to `FileContent`).
- Mime map + `contentType()` override for part Content-Type.

> Implemented, verified by code inspection + IDE inspections (clean) + full WireMock suite green (2026-10-11), tag `v0.6.0`.
> `MultipartBodyPublisher` over `BodyPublishers.concat` (per-part `[headers, content, CRLF]` + closing delimiter; exact Content-Length when every length is known — including a custom `ofStream(supplier, length)` filling the JDK gap; chunked otherwise; a fresh publisher per send, so a 307/308 re-send gets a live stream and keeps the boundary). `FileContent` — open SPI with four factories (`of(Path)`, `of(name, byte[])`, `of(name, supplier)`, `of(name, supplier, length)`); accepted on collections/arrays/varargs (one part per element, `byte[]` counts as one in-memory file, null argument/elements yield no parts, order = parameter then element). Part Content-Type: 20-entry mime map (lowercase `Locale.ROOT` match, unknown → `application/octet-stream`) + `contentType()` override per parameter. Framing hardened (tester finding, pre-existing since 0.5.0): RFC 2045 quoted-string escaping of `name`/`filename` (`\` → `\\`, `"` → `\"`), CR/LF rejected in the `MultipartPart` compact constructor. Deviations (2, recorded in ADR-0005): `FileContent` is an open SPI instead of sealed — pluggability over shape hiding; quoted-string escaping — landed with this cycle instead of a Known Gap. Tests: `MultipartTest` (wire byte-identical regression vs the pre-streaming writer, 4 MiB stream with exact Content-Length, chunked, escaping, CR/LF) + tester's `MultipartIndependentTest` (SPI contract, boundary format/uniqueness, mixed lengths, empty content, failing supplier, lazy disk reads, UTF-8 names, mime spot checks, ordering, null elements, empty varargs, redirect re-send).

## Cycle 0.6.1 — java.time wire contract — COMPLETE ✅ (2026-10-12)

> Follow-up to 0.6.0: `JacksonCodec` now round-trips `LocalDateTime` sub-second precision
> and accepts ISO-8601 input. No new SPI surface — the change is contained in
> `JacksonCodec.JavaTimeSupport`. Commit `f7a4c47`, tag `v0.6.1`.
>
> README (session #8, agent-loop): status line and all three install blocks now
> point at `v0.6.1`, the last tag; `0.6.2` coordinates deliberately not
> published — no tag for it yet (releases paused per maintainer agreement).

Problem (reported in `TODO.md` pre-0.6.0, fixed after release): the 0.5.0 contract
serialized `LocalDateTime` as `yyyy-MM-dd HH:mm:ss` — silently truncating nanoseconds —
and rejected the standard ISO-8601 `T` separator on input.

Decision:
- **Serializer: wire-compatible, precision-preserving.** `yyyy-MM-dd HH:mm:ss` plus an
  optional `.SSSSSSSSS` fraction (`DateTimeFormatterBuilder.appendFraction(NANO_OF_SECOND, 0, 9, true)`
  inside `optionalStart/End`) — the fraction prints only when non-zero, so second-precision
  values travel the wire byte-for-byte as before.
- **Deserializer: lenient.** Separator `T` or space (both optional), optional 1–9 digit
  fraction. Rejected: date without time, lowercase `t`, missing seconds — each covered by a test.
- Space separator kept as the wire default on purpose: byte-level backward compatibility with
  existing consumers is worth more than "ideal" ISO output. Switching output to `T` is a
  deliberate breaking decision (flagged to the maintainer in the agent mailbox).

Tests: `JacksonCodecDefaultsTest` — 11 tests (2 rewritten: they had locked in the 0.5.0
behavior, 4 new); `JsonCodecWireTest` +1 end-to-end (server sends
`2026-10-08T09:25:53.123456`, client parses it; the request side stays classic
`yyyy-MM-dd HH:mm:ss`). Suite 135/135 green.

Open question: whether the contract deserves a standalone `ADR-0006` — resolved
by the maintainer on 2026-10-12 (mailbox): not required; Javadoc + tests suffice.

## CompletableFuture return type — COMPLETE ✅ (2026-10-12, unreleased)

> From the Post-0.5.0 shortlist (ADR-0003). Session #5 (agent-loop). No version
> bump / tag — per maintainer agreement releases are paused.

Controller methods may now declare `CompletableFuture<T>` as their return type
(`T` = any wire type the synchronous twin supports, including `Void`, `String`,
raw and generic). The call is dispatched non-blockingly through
`HttpClient.sendAsync` (the client's default executor) and the future completes
with exactly what the synchronous twin would return; failures carry the same
exception types — `HandledException` / `UnexpectedStatusException` /
`RuntimeException(transport)` — as the cause of the future's exceptional
completion (standard JDK semantics: `join()` → `CompletionException` cause,
`get()` → `ExecutionException` cause).

Implementation (no new public API surface):
- `HttpTemplateBuilder.resolveReturnType` detects `CompletableFuture<T>` on the
  method's generic return type and unwraps `T` as the wire contract; the raw
  method return type is what the proxy hands the caller.
- `HttpTemplate` gained an `async` flag + `exchangeAsync`; the response tail
  (auth-details capture, logging, `@OnError`, status check, decoding) is
  extracted into a shared `processResponse` used by both paths.
- Redirect following is shared: the hop decision moved into `nextHop` (used by
  the sync loop and the async chain, recursion bounded by `@FollowRedirects`);
  `send` split into `toHttpRequest` / `toWireResponse` / `logPerHop` + a new
  `sendAsync` twin.

Gotcha recorded: on JDK 21 a `thenApply` that throws stores the raw exception
and `join()` wraps it in `CompletionException`; an `exceptionally` function
receives the failure already CE-wrapped and, if it rethrows a CE, the layer is
doubled. Hence the async tail rethrows the *raw* cause — the user sees exactly
one CE layer, whose cause is the synchronous twin's exception.

Tests: `CompletableFutureReturnTest` — 10 tests (decoded body/list/POST/raw
String/Void, non-blocking dispatch with a 750 ms delayed stub, `@OnError` and
unexpected-status failure causes, transport-fault cause, async 302 redirect).
Suite 145/145 green.

## java.time ISO-8601 wire format (option A) — COMPLETE ✅ (2026-10-12, unreleased)

> Maintainer's mailbox decision (2026-10-12): option A — *configurable* format,
> default stays classic (byte-compatible), no tag; version bumped 0.6.1 → 0.6.2
> (releases paused). Session #7 (agent-loop).

`JacksonCodec` gained a public nested enum `LocalDateTimeFormat`
(`SPACE` — the classic `yyyy-MM-dd HH:mm:ss`, the default; `ISO_8601` —
`yyyy-MM-dd'T'HH:mm:ss`) and a factory `defaults(LocalDateTimeFormat)`.
The no-arg `defaults()` delegates to `SPACE`, so existing consumers stay
byte-identical. Both modes share the same optional fractional-seconds tail
(printed only when the value carries sub-second precision) and the same
LENIENT parser — in every mode both the `T` and the space separator parse,
so a legacy space-based server keeps working in ISO mode on read.

Tests: `JacksonCodecDefaultsTest` +5 (ISO `T` output pin, ISO fractional
precision + nanosecond round-trip, classic parse in ISO mode, `SPACE` ≡
`defaults()` byte-identity, null fail-fast). Suite 150/150 green.

## Gson / Moshi codec adapters — COMPLETE ✅ (2026-10-13, unreleased)

> From the Post-0.5.0 shortlist (ADR-0003). Sessions #10–#16 (agent-loop).
> Design decisions, test strategy and the Javadoc contract:
> [`docs/0.7.0/ADR-0007-gson-moshi-codec-adapters.md`](docs/0.7.0/ADR-0007-gson-moshi-codec-adapters.md).
> No version bump / tag — releases paused per maintainer agreement (0.6.2 in `build.gradle`).

The probe chain promised by ADR-0001 is complete: `jackson-databind` **or**
`gson` **or** `moshi` on the classpath is now enough to get a working client.
Public API and POM unchanged (adapters are `compileOnly`); the explicit
`jsonCodec(...)` knob always wins over the probe.

Key decisions and reality-pins (ADR-0007):
- **Probe chain jackson → gson → moshi**, first hit wins — a fixed order for
  predictability: a mixed-classpath host always gets Jackson, the library the
  wire contract was designed around. `JsonCodecResolver.resolve()` delegates
  to a package-private overload taking a pluggable classpath probe (the
  project's only test seam for this chain); `JsonCodecResolverChainTest`
  covers the full 8-way classpath truth table plus functional "the resolved
  `defaults()` works" pins.
- **Adapter shape mirrors `JacksonCodec`**: the constructor takes the library
  instance as-is, `defaults()` factory, and each adapter is loaded only after
  its probe passed, so a Gson-free or Moshi-free classpath never loads the
  adapter class (no `NoClassDefFoundError`).
- **`GsonCodec` (pinned 2.14.0)**: plain `GsonBuilder().create()`,
  lazy-cached pretty twin. Minimum Gson **2.8.3+** — the twin relies on
  `Gson.newBuilder()`, which landed in 2.8.3 (pinned against the 2.8.0–2.8.3
  tags; the concept had assumed 2.8.0). Reality pin: Gson 2.14 froze the
  pre-2.14 reflective `java.time` output as the wire format (non-ISO
  `{"date":{...},"time":{...}}`; `Date` → locale string, second precision).
- **`MoshiCodec` (pinned 1.15.2)**: plain builder, token-for-token prettyPrint
  through `JsonReader` → indented `JsonWriter` (number literals preserved via
  `value(BufferedSource)` — Moshi's `value(String)` would quote them). The
  concept's `moshi-adapters` probe turned out to be a **phantom** — `JavaTime`
  never existed in that artifact (checked 1.0.0→1.15.2 jars + upstream); there
  is no standard java.time add-on in the Moshi 1.x world, so `defaults()`
  stays plain and `java.time`/`java.util.Date` go through an explicit `Moshi`
  with user-registered adapters.
- **Plain Moshi rejects what Gson/Jackson accept**: `java.*` platform classes
  and concrete `java.util` collection classes (`Map.of(...)`, `HashMap`) with
  a clear `IllegalArgumentException`. The codec wraps it in a
  `JsonDataException` naming the target type. Accepted known gap: a Moshi host
  that serializes collection values brings an explicit `Moshi` with an
  adapter registered for the concrete type.
- **`JsonCodec` gained its first normative interface-level Javadoc** — the
  contract every adapter (current or future, including user-supplied codecs)
  must meet: unchecked wrapping in the adapter's own exception family (Jackson
  `UncheckedIOException`, Gson `JsonSyntaxException`, Moshi
  `JsonDataException`; the message names the target type, the cause is
  preserved), `Type` honored including generics, top-level `null` → JSON
  `null` (pinned in all three codec test suites), best-effort `prettyPrint`,
  thread-safety.
- **README**: status line, the three install-snippet comments and the SPI
  note now uniformly say "Jackson 2, Gson, or Moshi is autodetected".

Tests: `GsonCodecTest` (12), `MoshiCodecTest` (13),
`JsonCodecResolverChainTest` (7), `JacksonCodecDefaultsTest` +1 (top-level null
pin). Suite **183/183** green; generated POM dependency-free (0.6.2).

## Retry with backoff — COMPLETE ✅ (2026-10-13, unreleased)

> From the Post-0.5.0 shortlist (ADR-0003) — its **last** item, the shortlist
> is now fully drained. Sessions #17–#22 (agent-loop). Design decisions,
> the test strategy and the Javadoc contract:
> [`docs/0.7.0/ADR-0008-retry-with-backoff.md`](docs/0.7.0/ADR-0008-retry-with-backoff.md).
> No version bump / tag — releases paused per maintainer agreement (0.6.2
> in `build.gradle`).

ADR-0003 names retry the *only* resilience feature in the shortlist and
notes that it "integrates with `@OnError`". A failed call is now repeated
with exponential backoff + full jitter for transport failures and the
transient status family; **off by default**, so existing users see zero
behavior change and the POM stays dependency-free.

Key decisions (ADR-0008):
- **Retry before classification** — the loop runs over raw `WireResponse`s;
  `processResponse` (auth-details capture, logging, `@OnError` decode,
  status check, payload decode) executes exactly once, on the final
  response — nothing allocates or logs on a discarded attempt.
- **`@OnError.statuses` are a terminal contract by default** (the declared
  contract wins over guessing); the escape hatch is
  `retryOnErrorStatuses`, and the final failure stays a typed
  `HandledException`, never a raw `UnexpectedStatusException`.
- **Two configuration levels, the method wins** (the `httpVersion`
  precedent): client-level `RetryPolicy` via
  `ModuleInfo.getRetryPolicy()` (default method — non-breaking; `off()` is
  a shared constant, no per-request allocation on the hot path) and
  `@Retry` per method — a **self-contained** policy that replaces the
  client's entirely (no field-by-field merge); a bare `@Retry`
  (`times = 1`) is the explicit off-switch.
- **Backoff** — exponential with a cap, **full jitter** (a fixed curve is
  the equal-boundary special case, no separate mode); `Retry-After`
  (integer seconds; the HTTP-date form added in 0.x polish, ADR-0009, and
  the obsolete date forms in ADR-0010) sets a
  **deterministic** `max(bound, retryAfter)` wait with no jitter — the
  server's hint asserts recovery time. The math is a pure package-private
  seam with an injectable `Random` (deterministic jitter in tests).
- **Idempotency per RFC 9110** — GET/PUT/DELETE retried; POST/PATCH only
  via `retryNonIdempotent` (the retry lands after the request was sent —
  a lost response on a successful POST is the classic duplicate-create);
  a transport failure is pre-response, so it is gated by `retryTransport`
  alone; `InterruptedException` is never retried (fail fast).
- **Uniform across sync/async** — one set of predicates and one backoff
  seam for both circuits; the async wait runs on
  `CompletableFuture.delayedExecutor` (no parked virtual thread per
  retry); `exchangeAsync`'s final exception mapping is untouched.

Public surface: `RetryPolicy` (immutable + builder, `defaults()`/`off()`),
`@Retry` (`ru.jrestly.annotation`), `ModuleInfo.getRetryPolicy()` (default
method), `retry(...)` on `JRestlyClient.Builder` and
`SimpleModuleInfo.Builder`. README gained the feature bullet and a "Retry
with backoff" section.

Tests: `RetryPolicyTest` (15 units — curve, cap, jitter bounds,
`Retry-After` branches, validation, copy semantics), `RetryTest` (11
WireMock integrations — sequential-status scenarios, transport fault,
`@OnError` terminality, idempotency, `Retry-After` wall-clock in both
forms — the HTTP-date pin is from 0.x polish),
`RetryAnnotationTest` (6 — off-switch vs enabled client, self-contained
replacements, POST via `nonIdempotent`, custom status sets),
`RetryAsyncTest` (5 — the async twin, typed join cause on exhaustion).
Suite **219/219** green.

## 0.x polish — carry-over quirks and accepted known gaps — COMPLETE ✅ (2026-10-13, unreleased)

> Track leftovers: the 0.5.0 "Known carry-over quirks" line above (both quirks
> now fixed) and the ADR-0008 Known gaps. Sessions #23–#26 (agent-loop). Scope
> decisions, fix shapes and the test strategy:
> [`docs/0.7.0/ADR-0009-0x-polish-carry-over-quirks-and-accepted-known-gaps.md`](docs/0.7.0/ADR-0009-0x-polish-carry-over-quirks-and-accepted-known-gaps.md).
> No version bump / tag — releases paused per maintainer agreement (0.6.2 in
> `build.gradle`).

Four candidates went in, four scope decisions came out (ADR-0009): two fixed,
one fixed with a new micro-gap, one consciously left on the books. No new
public API — every fix lands inside `HttpTemplate` / `RetryPolicy` on seams
that are already package-private or public by design.

Key decisions and reality-pins (ADR-0009):
- **PUT request-body logging skip — FIXED.** The body-log gate in
  `HttpTemplate.logRequest` is now a `private static final`
  `Set.of(POST, PATCH, DELETE, PUT)` — the per-request `Arrays.asList`
  allocation on the hot path is gone, and a PUT body (and even the "No request
  body" fallback) finally reaches the INFO log. GET/HEAD stay out on purpose:
  RFC 9110 makes a GET body legal but its semantics are undefined — fix the
  named quirk, don't invent policy. Bonus fact from the code check:
  `logRequest` runs once per `exchange()` call, *before* the retry loop, so
  retries never duplicate the request log.
- **`void.class` in `processResponse` — FIXED (the epic's only real behavior
  change).** One line, `|| void.class.equals(returnType)` after the String
  check: a `void` method with a non-empty body on an expected status now gets
  the `Void` form's semantics (null, body discarded) instead of reaching the
  codec. Reality pin: the quirk is **codec-dependent** — default Jackson
  2.18.2 is lenient (`readValue` of `void.class` → null, so the pin passes
  even pre-fix), Gson 2.14.0 throws `IllegalArgumentException: cannot handle
  void`, Moshi 1.15.2 throws `IllegalArgumentException: Cannot serialize
  abstract class void`. The pin's value is making the pass a library
  contract, not catching a red.
- **`Retry-After` HTTP-date — FIXED.** `HttpTemplate.retryAfterSeconds`
  became `retryAfterDelayMillis` (package-private, the backoff-seam pattern —
  unit-testable without WireMock): delta-seconds → `seconds × 1000`; HTTP-date
  (`DateTimeFormatter.RFC_1123_DATE_TIME`, IMF-fixdate) → `max(0, date −
  now)`, a past date floored at 0; absent / unparseable → null (pure jitter).
  The seam is unified on milliseconds — `RetryPolicy.nextDelayMillis` now
  takes `retryAfterMillis` (package-private, no ABI break; the body is a
  plain `Math.max(bound, retryAfterMillis)`). **asctime** (obsolete per RFC
  9110, no zone) is deliberately unparsed — new micro-gap. (Session #28
  corrected the micro-gap's scope — `RFC_1123_DATE_TIME` accepts
  IMF-fixdate only, so BOTH obsolete forms, RFC 850 and asctime(3), were
  unparsed — and closed it per ADR-0010: both are now parsed, as RFC 9110
  requires, with the asctime zone (UTC) prescribed by the RFC itself.) 8th
  "ADR vs
  reality" fix: the concept's "null-valued → null (unchanged)" was actually an
  uncaught NPE (`value.trim()` under `catch NumberFormatException`) —
  normalized to absent.
- **Payload-decode retry — LEAVE (reaffirmed with its three arguments):**
  transient truncation already surfaces as `IOException` and is
  transport-retried; a decode-failure retry collides with ADR-0008's
  "classified exactly once" (the loop would need a new failure kind to keep a
  successful `HandledException` terminal); and 0.x discipline keeps the typed
  codec exception. Reopening still requires a new ADR (ADR-0003 rule).
  **Reopened by ADR-0011** (session #31; section below) — the design there
  resolves all three arguments (a side-effect-free probe keeps "classified
  exactly once"; the transport argument is narrowed to *incomplete* bodies;
  the codec's own exception holds as a property of the design).

User-visible changes, exactly three, all in the direction the documentation
already implied: PUT bodies appear in the INFO request log; a `void` method
with a non-empty body no longer throws a codec exception; the HTTP-date form
of `Retry-After` now bounds the retry wait (only for users who opted into
retry).

Tests: `HttpTemplateLoggingTest` (7 units — log capture through the public
`setLogger` seam; on this JDK `System.Logger` is an interface),
`HttpTemplateRetryAfterTest` (11 units — delta-seconds, all three
HTTP-date forms (IMF-fixdate, RFC 850, asctime(3)), past-date floor,
garbage / absent → null; the obsolete RFC 850 and asctime(3) forms were
added by ADR-0010), `PutMethodTest` end-to-end log capture
(+1), `GetMethodTest` +2 (`void` / `Void` with a non-empty 200 body → null),
`CompletableFutureReturnTest` +1 (async pin), `RetryTest` +1 wall-clock
integration (IMF-fixdate 3 s in the future → elapsed ≥ 2 s),
`RetryPolicyTest` re-pinned on milliseconds. Suite **242/242** green
(the epic's own gate was 239/239; ADR-0010 / session #28 replaced the
single obsolete-form pin with four, one per form); generated POM
dependency-free (0.6.2).

## Payload-decode retry — COMPLETE ✅ (2026-10-13, unreleased)

> The last substantive feature of the known-gaps backlog — the 0.7.0 cycle
> now packages a **complete** retry contract (transport + status + decode).
> Sessions #31–#35 (agent-loop). Design decisions and the test strategy:
> [`docs/0.7.0/ADR-0011-retry-payload-decode-failure.md`](docs/0.7.0/ADR-0011-retry-payload-decode-failure.md).
> No version bump / tag — releases paused per maintainer agreement (0.6.2
> in `build.gradle`).

The payload-decode known gap (ADR-0008, reaffirmed by ADR-0009 candidate 4)
is reopened by ADR-0011 and closed: a status-acceptable response whose body
the codec cannot bind — the bytes arrived in full but do not fit the
declared type — is now the retry loop's **third failure-kind** (transport /
status / decode), retried under a separate opt-in.

Key decisions (ADR-0011):
- **A distinct opt-in, `retryDecodeFailures` (default `false`)** — a
  `RetryPolicy` builder attribute + an `@Retry` attribute, self-contained as
  the rest of the annotation is. The decode probe runs on every *success*
  response (the other axes only ever touch a failed one), so the axis does
  not ride the general enable: "enable retry" stays a zero behavior change
  on the success path.
- **"Classified exactly once" preserved by a side-effect-free probe** — the
  loop runs `probePayloadDecode` (a package-private seam that reproduces
  *only* the payload-decode branch of `processResponse` — no auth-consumer,
  no logger, no exception object), not `processResponse` itself; the
  side-effectful tail still fires exactly once, on the final response. The
  scope (success path only — `@OnError` and unexpected statuses classify
  before the payload decode) falls out of the probe's shape, with no
  special case.
- **The final exception is the codec's own unchecked exception** — no new
  class, no wrapping: when attempts are exhausted on an unbindable body,
  `processResponse` throws exactly what the caller would see without retry.
  ADR-0009 candidate 4's "0.x discipline" argument now holds as a property
  of the design, not a reason to leave the gap.
- **ADR-0009's transport argument sharpened, not contradicted** — transport
  retry covers *incomplete* bodies (a mid-stream cutoff is an
  `IOException`); decode retry covers the *complete-but-unbindable*
  residual. Complementary, not redundant.
- **Uniform across sync/async** — the async circuit mirrors the sync branch
  in `attemptAsync` (the probe is a plain synchronous call in the handle
  callback; the wait runs on `CompletableFuture.delayedExecutor` as for the
  other axes); `exchangeAsync`'s final exception mapping is untouched.

Public surface: `RetryPolicy`'s `retryDecodeFailures` builder attribute +
`isRetryDecodeFailures()`, `@Retry.retryDecodeFailures`, and one
package-private probe seam (test-only). README "Retry with backoff" gained
the decode-failure paragraph and the feature-bullet mention; the
`RetryPolicy` / `@Retry` / `ModuleInfo` Javadoc state the decode axis
inside the sync/async uniformity contract.

Tests: `HttpTemplateDecodeProbeTest` (7 units — the probe's branches with a
stub `JsonCodec`; `assertSame` on the codec's exception instance: no
wrapping, no new type, pinned at unit level; the fixture is deliberately
bare — no logger, no auth consumer — so "side-effect-free" is pinned by the
fixture's shape), `RetryPolicyTest` +1 (the flag defaults off in every
static and the builder setter works), `RetryDecodeTest` (6 WireMock
integrations — malformed-then-good `200` on the sync and async circuits,
exhaustion typing (the codec's own `UncheckedIOException`), default-off
zero change, `@OnError` out of scope, "classified exactly once" through an
auth-capture counter). Suite **256/256** green; generated POM
dependency-free (0.6.2).

## Cycle 0.7.0 — COMPLETE ✅ (2026-10-13, unreleased)

> Source of truth (cycle shape, versioning decisions, release-notes form, gates, status):
> [`docs/0.7.0/ADR-0012-release-0.7.0-cycle.md`](docs/0.7.0/ADR-0012-release-0.7.0-cycle.md).
> Sessions #36–#39 (agent-loop). The cycle packaged the six unreleased feature
> sets accumulated since `v0.6.1` — scope is packaging, not features
> (ADR-0012 Decision 4): no new feature, no refactor, no README change (the
> install blocks and the status line stay at `v0.6.1`, the last tag).

Contents — the six `COMPLETE ✅ (unreleased)` sections above; each is the
source of truth for its set (linked, not restated):

1. [CompletableFuture return type](#completablefuture-return-type--complete--2026-10-12-unreleased)
2. [java.time ISO-8601 wire format (option A)](#java-time-iso-8601-wire-format-option-a--complete--2026-10-12-unreleased)
3. [Gson / Moshi codec adapters](#gson--moshi-codec-adapters--complete--2026-10-13-unreleased)
4. [Retry with backoff](#retry-with-backoff--complete--2026-10-13-unreleased)
5. [0.x polish — carry-over quirks and accepted known gaps](#0x-polish--carry-over-quirks-and-accepted-known-gaps--complete--2026-10-13-unreleased)
6. [Payload-decode retry](#payload-decode-retry--complete--2026-10-13-unreleased)

Versioning (ADR-0012 Decisions 1–2): `0.6.2` → **0.7.0** — a semver *minor*:
new additive public surface (the codec adapters, `RetryPolicy` / `@Retry`,
`JacksonCodec.LocalDateTimeFormat`, `CompletableFuture<T>` return types) with
no breaking or behavior change (retry off by default, ISO opt-in). Mechanics
per the maintainer: the bump in `build.gradle` on the gate step (session
#38), the version named in the release commit message, **no git tag** — the
0.6.2 "unpublished version" pattern; the tag-based JitPack coordinates stay
at `v0.6.1` until the maintainer tags.

Release notes (ADR-0012 Decision 3): the project's first
[`CHANGELOG.md`](CHANGELOG.md) at the root — the `0.7.0` entry (unpublished)
derived from the six sections (user-facing changes, not design rationale);
pre-0.7.0 history stays in this ROADMAP (changelog-header note, no backfill).

Cycle shape (ADR-0012 Decision 5): one epic, four steps (sessions #36–#39):
concept (ADR-0012 + the DRAFT epic) → `CHANGELOG.md` (session #37) → bump
`0.7.0` + gate (session #38) → distillation (this section) + DRAFT cleanup
(session #39). Gates (session #38): `./gradlew test --rerun` **256/256**
(36 classes); generated POM **0.7.0**, 0 `<dependency>` (zero-dep preserved).

After the cycle: the known-gaps backlog is exhausted — ADR-0011's "feature
first, cycle after" closed the last substantive gap, so 0.7.0 ships a
complete retry contract (transport + status + decode) with no residual
substantive gap. Next candidates: the warnings / refactor audit (fallback
#1, deferred since session #30) — done, section below; ADR number
**0013** — the warnings epic's ADR (`docs/0.8.0/`), **0014** — the 0.8.0
cycle's ADR (`docs/0.8.0/`), **0015** free.

## Warnings and modernization (ADR-0013) — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (audit, findings, scope decisions D1–D4, the step plan):
> [`docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md`](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md).
> Sessions #41–#58 (agent-loop). Cleanup without new public surface (ADR-0013
> Nature): every fix lands on existing classes, seams and the build file. No
> version bump / tag — releases paused per maintainer agreement (0.7.0 in
> `build.gradle`).

Scope (ADR-0013 D1–D4, as executed): all 12 compiler warnings fixed
(9 main / 3 test; suppression targeted, never blanket, each carrying a
one-line rationale), all 237 javadoc warnings fixed — the whole normative
main surface documented (the audit's "100" was the javadoc tool's per-run
cap, session #44), `options.release = 21`, hot path re-checked with no new
findings, Java 21 idioms: no legacy fragments exist (a finding, not a fix).

The gate (step 6, session #58): `build.gradle` runs javadoc with
`-Xdoclint:all -Werror` — `javadocJar` is in every build, so a public/
protected member without a comment fails the build instead of decaying back
into noise. Full re-audit: javac `-Xlint:all` 0/0 (main + test), javadoc 0
(gate negative-tested with a probe class), suite 256/256 (36 classes),
generated POM 0.7.0, 0 `<dependency>`.

After the epic: the `-Xlint` one-line follow-up (ADR-0013 Consequences —
the deliberately separate decision) is done — session #59: `-Xlint:all
-Werror` on both compile tasks, the seven audit suppressions forgiven by
design, gate negative-tested on both sides. ADR number **0014** — the 0.8.0
cycle's ADR (`docs/0.8.0/`), **0015** free.

## Post-0.5.0 shortlist (not scheduled — see ADR-0003)

- ~~Retry with backoff (integrates with `@OnError` semantics)~~ — done 2026-10-13, section above
- ~~`CompletableFuture` return type~~ — done 2026-10-12, section above
- ~~Gson / Moshi codec adapters (classpath probe chain extension)~~ — done 2026-10-13, section above

Carry-over decision (maintainer, 2026-10-12): `JacksonCodec` serializer output
should also learn ISO-8601 `T`. Resolved as option A (configurable,
non-breaking) — done, section above.
