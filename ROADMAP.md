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
> version bump / tag — releases paused per maintainer agreement (the
> version in `build.gradle` — 0.8.0, still untagged).

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

## Cycle 0.8.0 — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (cycle shape, versioning decisions, release-notes form, gates, status):
> [`docs/0.8.0/ADR-0014-release-0.8.0-cycle.md`](docs/0.8.0/ADR-0014-release-0.8.0-cycle.md).
> Sessions #60–#63 (agent-loop). The cycle packaged the one unreleased set
> accumulated since 0.7.0 — the warnings & modernization epic (ADR-0013,
> sessions #41–#58) plus its deliberate `-Xlint` follow-up (session #59);
> scope is packaging, not features (ADR-0014 Decision 4).

Contents — the single section above; it is the source of truth for the
shipped set (linked, not restated):

1. [Warnings and modernization (ADR-0013)](#warnings-and-modernization-adr-0013--complete--2026-10-14-unreleased)

Versioning (ADR-0014 Decisions 1–2): `0.7.0` → **0.8.0** — a semver *minor*:
the shipped `javadocJar` gains normative completeness (a user-visible
artifact change, not a bug fix), the build pins the Java 21 API surface
with `--release 21`, and the three exception classes gain an explicit
`@Serial` UID (a serialization-compatibility guarantee). No breaking or
behavior change — the four explicit no-arg constructors are ABI-neutral, so
ADR-0013's "no new public surface" nature holds. Mechanics per ADR-0012
Decision 2, unchanged: the bump in `build.gradle` on the gate step (session
#62), the version named in the release commit message, **no git tag** — the
tag-based JitPack coordinates stay at `v0.6.1` until the maintainer tags.

Release notes (ADR-0014 Decision 3): the `0.8.0` entry in
[`CHANGELOG.md`](CHANGELOG.md) (unpublished), written from the start in the
navigator form (1–2 lines per change + a pointer to the source of truth);
the `0.7.0` entry (unpublished) stays untouched — both entries await the
maintainer's tag.

Cycle shape (ADR-0014 Decision 5): one epic, four steps (sessions #60–#63):
concept (ADR-0014 + the DRAFT epic) → `CHANGELOG.md` entry (session #61) →
bump `0.8.0` + the gates (session #62) → distillation (this section) + DRAFT
cleanup (session #63). The gates are already permanent (sessions #58/#59),
so the cycle gate verifies, it does not install — session #62, clean
rebuild: `compileJava` / `compileTestJava` 0 warnings under the permanent
`-Xlint:all -Werror`, javadoc 0 under the permanent `-Xdoclint:all -Werror`,
`./gradlew test --rerun` **256/256** (36 classes), generated POM **0.8.0**,
0 `<dependency>` (zero-dep preserved).

After the cycle: the DRAFT active zone held two candidates — the
ergonomics-for-modern-APIs direction (the maintainer's 2026-10-14 mailbox,
direction 3 — declared the main next course) — done, section below;
and test-javadoc (ADR-0013 Non-goals). ADR number **0015** — the response
streaming epic (`docs/0.9.0/`), **0016** free.

## Response streaming — `InputStream` return type (ADR-0015) — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (concept, decisions, test strategy, Javadoc contract, status):
> [`docs/0.9.0/ADR-0015-response-streaming-inputstream-return.md`](docs/0.9.0/ADR-0015-response-streaming-inputstream-return.md).
> Sessions #64–#71 (agent-loop). The first ergonomics slice of direction 3 (maintainer's 2026-10-14 mailbox: "request parameter flexibility, data streaming, LLM, request pipeline tuning"), opening the provisional 0.9.0 cycle. Scope is byte-stream streaming; line/SSE streaming for LLMs is deferred to ADR-0016. No version bump / tag — releases paused per maintainer agreement (0.8.0 in `build.gradle`).

Key decisions (ADR-0015 Aspects 1–6):
- **Surface (Aspect 1):** Return type is the contract (ADR-0004) — `InputStream` and `CompletableFuture<InputStream>` declare byte-stream reception. No marker annotations, no wrapper types (ADR-0003).
- **Engine (Aspect 2):** Send path branches on effective return type to `BodyHandlers.ofInputStream()` without body buffering. `WireResponse` gains an `InputStream stream` component with an ABI-neutral 3-arg constructor. Intermediate redirect and retry streams are closed before the next hop.
- **Contract (Aspect 3):** JSON by return type — success path bypasses JSON codec entirely (`deserialize` and `prettyPrint` no-ops). Error paths (`@OnError`, unexpected status) consume the stream and decode JSON / provide raw payload, keeping errors uniform (ADR-0004). Decode-retry (ADR-0011) is a no-op on streams.
- **Lifecycle & Logging (Aspects 4–5):** Caller owns stream lifecycle (read to EOF or close). Socket timeout bounds time-to-headers per JDK 21 spec. Stream payload logging skips materializing the body ("streaming, Content-Length: N" / "(length unknown)").
- **Validation & End-to-end (Aspect 6):** `ControllerValidator` validates stream return types cleanly. End-to-end WireMock suite covers sync/async byte-exact downloads (1 MiB), redirect/retry stream disposal, error path consumption, and connection fault handling.

Gates (session #71, clean rebuild):
- `./gradlew test --rerun` **282/282** (40 classes, 0 failures/errors; +26 tests in the 0.9.0 cycle across `HttpTemplateStreamWireTest`, `HttpTemplateStreamContractTest`, `HttpTemplateStreamAsyncTest`, `ResponseStreamingTest`, `ControllerValidationTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- `build.gradle` untouched (0.8.0 unreleased).

After the epic: direction 3 continues with SSE / line events for LLMs (ADR-0016, building on this stream seam) — done, section below; followed by request parameter flexibility and pipeline hooks. ADR number **0016** — the Server-Sent Events & line streaming epic (`docs/0.9.0/`), **0017** free.

## Server-Sent Events & line streaming (ADR-0016) — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (concept, decisions, WHATWG SSE parser, test strategy, Javadoc contract, status):
> [`docs/0.9.0/ADR-0016-sse-line-events.md`](docs/0.9.0/ADR-0016-sse-line-events.md).
> Sessions #72–#77 (agent-loop). The second ergonomics slice of direction 3 (maintainer's 2026-10-14 mailbox: "request parameter flexibility, data streaming, LLM, request pipeline tuning"). Builds directly on top of ADR-0015's stream seam (`WireResponse.stream`, `ofInputStream`). Return types: `Stream<String>` and `Stream<ServerSentEvent>`, plus their async twins `CompletableFuture<Stream<...>>`. No version bump / tag — releases paused per maintainer agreement (0.8.0 in `build.gradle`).

Key decisions (ADR-0016 Aspects 1–6):
- **Surface (Aspect 1):** Return types `Stream<String>` (line streaming, NDJSON) and `Stream<ServerSentEvent>` (WHATWG Server-Sent Events, LLM tokens), plus `CompletableFuture<Stream<String>>` and `CompletableFuture<Stream<ServerSentEvent>>`. Zero-dep, no custom wrapper classes (ADR-0003, ADR-0004).
- **SSE parser & model (Aspect 2):** Zero-dep immutable `ServerSentEvent` record (`id`, `event`, `data`, `retry`, `rawComment`). Streaming parser per WHATWG SSE specification: multi-line data joined with `\n`, leading space stripping after `:` delimiter, comments ignored by default. `event.isDone()` helper recognizing the standard LLM stream terminal marker `[DONE]`. Lazy parsing via custom `Spliterator` / `Iterator`.
- **Engine & Wire Seam (Aspect 3):** Reuses the ADR-0015 `WireResponse.stream()` seam (`BodyHandlers.ofInputStream()`) without buffering whole response bodies. `HttpTemplate.processResponse` decodes line/SSE streams using the response `Content-Type` charset (defaulting to UTF-8). Guarantees network stream closure via `stream.onClose(...)` lifecycle hook. Payload logging indicates `"streaming lines"` and `"streaming events"` without consuming the stream.
- **Error contract & Async twins (Aspect 4):** Error statuses (4xx/5xx) read and close the stream, decoding `@OnError` into `HandledException` or preserving raw body in `UnexpectedStatusException` (identical error behavior to byte bodies). Async twins complete normally with a live, lazily evaluated stream on 2xx, or exceptionally on HTTP/transport errors. Decode probe is decode-proof without invoking codecs.
- **Validation & End-to-end (Aspect 5):** `ControllerValidator` validates line and SSE stream signatures. WireMock suite tests real LLM chat completions with `[DONE]`, NDJSON streaming, custom charset decoding (`windows-1251`), `@OnError` JSON error decoding, unexpected status handling, and connection reset faults. README updated with a dedicated section and usage examples.

Gates (session #77, clean rebuild):
- `./gradlew test --rerun` **326/326** (44 classes, 0 failures/errors; +44 tests in ADR-0016 across `ServerSentEventTest`, `HttpTemplateLineAndEventStreamTest`, `HttpTemplateLineAndEventStreamErrorAndAsyncTest`, `ServerSentEventsAndLineStreamingTest`, `ControllerValidationTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- `build.gradle` untouched (0.8.0 unreleased).

After the epic: candidate #1 — request parameter flexibility (class-level headers, nullable query params, catch-all) — done, section below; candidate #2 — request pipeline tuning (hooks/interceptors), candidate #3 — test-javadoc. ADR number **0017** — the request parameter & header flexibility epic (`docs/0.9.0/`), **0018** free.

## Request parameter & header flexibility (ADR-0017) — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (concept, decisions, test strategy, Javadoc contract, status):
> [`docs/0.9.0/ADR-0017-request-parameter-flexibility.md`](docs/0.9.0/ADR-0017-request-parameter-flexibility.md).
> Sessions #78–#83 (agent-loop). The third ergonomics slice of direction 3 (maintainer's 2026-10-14 mailbox: "request parameter flexibility, data streaming, LLM, request pipeline tuning"). Nullable parameters & headers, dynamic `Map<?, ?>` arguments, class-level/method-level static `@RequestHeader` and `@RequestDefaultParam` with `@Repeatable` containers, override/merge semantics. No version bump / tag — releases paused per maintainer agreement (0.8.0 in `build.gradle`).

Key decisions (ADR-0017 Aspects 1–5):
- **Nullable parameters & graceful omission (Aspect 1):** Null values in `@RequestHeader` omit the header without throwing NPE. Null-safe collections in `@RequestParam` (query, form-urlencoded, multipart) and `@RequestHeader` skip `null` elements. `@PathVariable` strictly rejects `null` values with clear, actionable `IllegalArgumentException("Path variable '" + name + "' value cannot be null")`.
- **Dynamic Map parameters (Aspect 2):** Method arguments of type `Map<?, ?>` annotated with `@RequestParam` or `@RequestHeader` expand dynamically. Supports nested collections, skips null keys and values. When annotation specifies a non-empty name, keys are prefixed (`name[key]`). `@RequestHeader.name()` defaults to `""` allowing bare `@RequestHeader Map<String, String>`.
- **Class-level & method-level static annotations (Aspects 3–4):** Extended `@Target` of `@RequestHeader` to `TYPE` and `METHOD`, introduced `@RequestDefaultParam` for query/form defaults. Container annotations `@RequestHeaders` and `@RequestDefaultParams` with `@Repeatable` support multiple declarations per class or method. Interface inheritance walks the hierarchy so sub-interfaces inherit static headers and defaults.
- **Merge and override precedence (Aspect 5):** Case-insensitive header overrides and name-exact param overrides: method parameter > method static annotation > class static annotation (sub-interface > super-interface). Duplicate static headers with distinct values multi-bind. `ControllerValidator` verifies static declarations have non-empty names.
- **Validation & End-to-end (Aspect 5 / Suite):** Full WireMock end-to-end suite (`RequestParameterFlexibilityTest`, 5 tests) exercises interface inheritance, nested dynamic map filters, nullable headers, default parameter overrides, form-urlencoded encoding, strict path variable validation, and async twin `CompletableFuture`.

Gates (session #83, clean rebuild):
- `./gradlew test compileJava compileTestJava javadoc --rerun-tasks` **375/375** (46 classes, 0 failures/errors; +49 tests in ADR-0017 across `RequestHeadersTest`, `PathVariableEncodingTest`, `GetMethodTest`, `PostMethodTest`, `MultipartTest`, `StaticHeadersAndParamsTest`, `ControllerValidationTest`, `RequestParameterFlexibilityTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- `build.gradle` untouched (0.8.0 unreleased).

After the epic: candidate #1 — request pipeline tuning (request/response interceptors / hooks, header customization, metrics — ADR-0018), candidate #2 — test-javadoc. ADR number **0018** free.

## Request pipeline: Interceptors & hooks (ADR-0018) — COMPLETE ✅ (2026-10-14, unreleased)

> Source of truth (concept, decisions, invocation lifecycles, Javadoc contract, status):
> [`docs/0.9.0/ADR-0018-request-pipeline-interceptors.md`](docs/0.9.0/ADR-0018-request-pipeline-interceptors.md).
> Sessions #84–#89 (agent-loop). The fourth ergonomics slice of direction 3 (maintainer's 2026-10-14 mailbox: "request parameter flexibility, data streaming, LLM, request pipeline tuning"). Pre-execution request transformation and post-execution observability hooks with zero-cost default path. No version bump / tag — releases paused per maintainer agreement (0.8.0 in `build.gradle`).

Key decisions (ADR-0018):
- **Surface:** `RequestInterceptor` (pre-execution request transformation/decoration, with `of(UnaryOperator)` adapter for functional style), `ResponseInterceptor` (post-execution observability: metrics, logging, audit). Zero new annotations, zero wrapper types (ADR-0003, ADR-0004).
- **Context model:** `RequestContext` (method, controller class, args, thread-safe attributes for pre→post state propagation) and `ResponseContext` (delegates to RequestContext + status, duration, response/failure, `isSuccess()` helper). `SimpleRequestContext` / `SimpleResponseContext` implementations with defensive copying and strict null validation.
- **WireRequest wither methods:** `withHeader`, `withHeaders`, `withoutHeader` (case-insensitive), `withUri`, `withMethod`, `withBody`, `withContentType`, `withParts`, plus fluent `Builder` (`builder()`, `toBuilder()`).
- **Configuration:** `ModuleInfo.getRequestInterceptors()` / `getResponseInterceptors()` with `List.of()` defaults (non-breaking). Builder methods on `SimpleModuleInfo.Builder` and `JRestlyClient.Builder` with defensive copying and non-null validation.
- **Execution:** Interceptor chains run in `HttpTemplate` — request interceptors before the call (with null-guard, last-valid-request preservation), response interceptors in `finally`/`whenComplete` (fault-isolated: exception in one hook logs warning, doesn't break the result or other hooks). Zero-cost path when lists are empty. Stream-safe for `InputStream` / SSE.
- **Symmetry:** Uniform across sync `exchange()` and async `exchangeAsync()` twins — identical interceptor semantics in both circuits.

Gates (session #89, clean rebuild):
- `./gradlew test --rerun` **420/420** (51 classes, 0 failures/errors; +22 tests in ADR-0018 across `WireRequestTest`, `RequestContextTest`, `InterceptorConfigurationTest`, `RequestPipelineTest`, `RequestPipelineWireMockTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- `build.gradle` untouched (0.8.0 unreleased).

After the epic: the four ergonomics slices of direction 3 are complete (ADR-0015 response streaming, ADR-0016 SSE/LLM, ADR-0017 params/headers flexibility, ADR-0018 request pipeline). Release cycle 0.9.0 (packaging, ADR-0019) — done, section below. ADR number **0019** — cycle ADR, **0020** free.

## Cycle 0.9.0 — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (cycle shape, versioning decisions, release-notes form, gates, status):
> [`docs/0.9.0/ADR-0019-release-0.9.0-cycle.md`](docs/0.9.0/ADR-0019-release-0.9.0-cycle.md).
> Sessions #93–#96 (agent-loop). The cycle packaged the four ergonomic epics
> accumulated under Direction 3 (maintainer's 2026-10-14 mailbox) — response
> streaming (ADR-0015), SSE & line streaming (ADR-0016), request parameter & header
> flexibility (ADR-0017), and request pipeline interceptors & hooks (ADR-0018);
> scope is packaging, not features (ADR-0019 Decision 4).

Contents — the four sections above; they are the sources of truth for the
shipped set (linked, not restated):

1. [Response streaming — `InputStream` return type (ADR-0015)](#response-streaming--inputstream-return-type-adr-0015--complete--2026-10-14-unreleased)
2. [Server-Sent Events & line streaming (ADR-0016)](#server-sent-events--line-streaming-adr-0016--complete--2026-10-14-unreleased)
3. [Request parameter & header flexibility (ADR-0017)](#request-parameter--header-flexibility-adr-0017--complete--2026-10-14-unreleased)
4. [Request pipeline: Interceptors & hooks (ADR-0018)](#request-pipeline-interceptors--hooks-adr-0018--complete--2026-10-14-unreleased)

Versioning (ADR-0019 Decisions 1–2): `0.8.0` → **0.9.0** — a semver *minor*:
the release ships major ergonomic capabilities (byte/line/SSE streaming, dynamic maps,
static headers, request/response interceptor pipeline) with zero breaking changes or mandatory
migrations for existing callers. Mechanics per ADR-0012/ADR-0014: the bump in `build.gradle`
on the gate step (session #95), the version named in the release commit message, **no git tag** —
the tag-based JitPack coordinates stay at `v0.6.1` until the maintainer tags.

Release notes (ADR-0019 Decision 3): the `0.9.0` entry in [`CHANGELOG.md`](CHANGELOG.md)
(unpublished), written from the start in navigator form (1–2 lines per epic + pointer to source of
truth: ADR-0015..0018, README sections, Javadoc); the unpublished `0.7.0` and `0.8.0` entries stay
untouched — all three await the maintainer's tag.

Cycle shape (ADR-0019 Decision 5): one epic, four steps (sessions #93–#96):
concept (ADR-0019 + DRAFT epic, session #93) → `CHANGELOG.md` entry (session #94) →
bump `0.9.0` + gates (session #95) → distillation (this section) + DRAFT cleanup (session #96).
Gates verified on clean rebuild (session #95):
`compileJava` / `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`,
`javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`,
`./gradlew test --rerun` **420/420** (51 classes),
generated POM **0.9.0** with 0 `<dependency>` (zero-dep preserved).

After the cycle: candidate #1 — SSL / custom certificates (mailbox #94: `SSLContext` configuration on builder, zero-dep, enterprise CAs / mTLS) — done, section below; candidate #2 — transparent GZIP / Deflate decompression (mailbox #94: `GZIPInputStream`/`InflaterInputStream` zero-dep core); candidate #3 — response headers & metadata access (mailbox #94: `Link` pagination, `ETag`, `Location`, rate limits while preserving ADR-0004 transparent methods); candidate #4 — test-javadoc (ADR-0013 Non-goals). ADR number **0020** — SSL epic, **0021** free.

## SSL/TLS configuration, custom certificates & mTLS (ADR-0020) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, SPI contract, status):
> [`docs/0.10.0/ADR-0020-ssl-custom-certificates-mtls.md`](docs/0.10.0/ADR-0020-ssl-custom-certificates-mtls.md).
> Sessions #97–#98 (agent-loop). Library maturity vector #1 (maintainer's 2026-10-14 23:25 mailbox:
> "донастройка ssl (например, кастомные серты)"). Custom `SSLContext` and `SSLParameters` configuration
> for enterprise private PKI, self-signed test fixtures, and mutual TLS (mTLS). Zero external dependencies,
> 100% backward compatible (null defaults fall back to `SSLContext.getDefault()`).

Key decisions (ADR-0020):
- **Zero-dep core:** Uses strictly Java 21 standard library types `javax.net.ssl.SSLContext` and `javax.net.ssl.SSLParameters` from `java.base`.
- **SPI surface (`ModuleInfo`):** Added `default SSLContext getSslContext() { return null; }` and `default SSLParameters getSslParameters() { return null; }`.
- **Builder ergonomics:** Added fluent `sslContext(SSLContext)` and `sslParameters(SSLParameters)` to `SimpleModuleInfo.Builder` and `JRestlyClient.Builder` with fail-fast `NullPointerException` validation.
- **Transport wiring:** `AbstractHttpClient.createConnection()` forwards non-null `sslContext` and `sslParameters` directly to `java.net.http.HttpClient.Builder`.
- **Full test coverage:** 17 tests across `SslConfigTest` (unit configuration & fail-fast) and `HttpsSslTest` (WireMock HTTPS integration, negative untrusted cert rejection, positive custom `SSLContext`, `SSLParameters` TLSv1.3 pinning & cipher suite validation, async `CompletableFuture` and streaming `InputStream` over HTTPS).

Gates (session #98, clean rebuild):
- `./gradlew test` **437/437** (53 classes, 0 failures/errors; +17 tests in ADR-0020 across `SslConfigTest` and `HttpsSslTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved.

After the epic: candidate #2 — transparent GZIP / Deflate decompression (mailbox #94: `GZIPInputStream`/`InflaterInputStream` zero-dep core) — done, section below; candidate #3 — response headers & metadata access (mailbox #94: `Link` pagination, `ETag`, `Location`, rate limits while preserving ADR-0004 transparent methods); candidate #4 — test-javadoc (ADR-0013 Non-goals). ADR number **0021** — decompression epic, **0022** free.

## Transparent response decompression (GZIP & Deflate, ADR-0021) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, SPI contract, status):
> [`docs/0.10.0/ADR-0021-transparent-decompression-gzip-deflate.md`](docs/0.10.0/ADR-0021-transparent-decompression-gzip-deflate.md).
> Session #99 (agent-loop). Library maturity vector #2 (maintainer's 2026-10-14 23:25 mailbox:
> "прозрачный GZIP / Deflate"). Transparent response decompression for `gzip`, `x-gzip`, and `deflate`
> (RFC 1950 zlib and RFC 1951 raw deflate fallback) using standard `java.util.zip` from `java.base` (100% zero-dep).
> Automatically negotiates `Accept-Encoding: gzip, deflate` (when enabled and not explicitly specified by the caller).
> Header hygiene: `Content-Encoding` and `Content-Length` are stripped from `WireResponse` so downstream codecs
> and interceptors receive clean payloads. Transparent across sync, async (`CompletableFuture`), streaming (`InputStream`),
> line streaming (`Stream<String>`), SSE (`Stream<ServerSentEvent>`), and error payloads (`@OnError` & `UnexpectedStatusException`).

Key decisions (ADR-0021):
- **Zero-dep core:** Uses strictly Java 21 standard library types from `java.util.zip` (`GZIPInputStream`, `InflaterInputStream`, `Inflater`) from `java.base`.
- **SPI surface (`ModuleInfo`):** Added `default boolean isDecompressionEnabled() { return true; }`.
- **Builder ergonomics:** Added fluent `decompression(boolean)` to `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`.
- **Automatic header negotiation:** `HttpTemplateBuilder.createHeaders()` appends `Accept-Encoding: gzip, deflate` when decompression is enabled and no explicit `Accept-Encoding` was provided by caller or auth provider.
- **Engine decompression (`HttpTemplate.handleDecompression`):** Decompresses raw `byte[]` and wraps live streaming `InputStream`; strips `Content-Encoding` and `Content-Length`.
- **Full test coverage:** 28 tests across `DecompressionConfigTest` (17 tests: unit defaults, builder settings, explicit header preservation, gzip, x-gzip, zlib deflate, raw deflate fallback, streaming, empty body, corrupted stream error handling) and `GzipDeflateDecompressionTest` (11 tests: WireMock E2E for DTO deserialization, x-gzip, zlib, raw deflate, String return, binary InputStream streaming, line streaming, async CompletableFuture, @OnError error DTO, UnexpectedStatusException raw payload, and opt-out).

Gates (session #99, clean rebuild):
- `./gradlew test` **465/465** (55 classes, 0 failures/errors; +28 tests in ADR-0021 across `DecompressionConfigTest` and `GzipDeflateDecompressionTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved.

After the epic: candidate #1 — response headers & metadata access (mailbox #94: `Link` pagination, `ETag`, `Location`, rate limits while preserving ADR-0004 transparent methods) — done, section below; candidate #2 — test-javadoc (ADR-0013 Non-goals). ADR number **0022** — response headers epic, **0023** free.

## Response headers and metadata access (ADR-0022) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, holder model, test strategy, status):
> [`docs/0.10.0/ADR-0022-response-headers-and-metadata-access.md`](docs/0.10.0/ADR-0022-response-headers-and-metadata-access.md).
> Session #100 (agent-loop). Library maturity vector #3 (maintainer's 2026-10-14 23:25 mailbox:
> "элегантный доступ к метаданных заголовков (для пагинации, ETag, Location и рейт-лимитов, и т.д.)").
> Provides elegant, zero-wrapper access to HTTP response headers and call metadata while strictly
> preserving the transparent return type invariant (ADR-0004: methods return domain types, never `Response<T>`).
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0022):
- **`ResponseHeaders` holder container:** Encapsulates status code, duration, raw headers, case-insensitive map, `firstValue`, `allValues`, `firstLongValue` (rate limits, sizes), `etag()`, `location()`, `locationUri()`, `contentType()`, `link(rel)`, `links()`, `retryAfter()`, `isSuccess()`, and `isPopulated()`.
- **Method parameter binding:** Controller interface methods can declare an unannotated `ResponseHeaders` parameter. `ControllerValidator` validates it as an intrinsic hook without requiring annotations. `HttpTemplateBuilder` records the holder without serializing it into the request. `HttpTemplate.processResponse` populates it before payload deserialization and before error dispatch.
- **Capture pattern (`ResponseHeaders.capture`):** Synchronous calls can capture response metadata without modifying controller interface signatures via `CapturedResponse<T> response = ResponseHeaders.capture(() -> client.call())`. Operates via scoped, reentrant `ThreadLocal`.
- **RFC 8288 Web Linking parser:** Robust, zero-dependency parser for `Link` headers supporting comma-separated multi-links, quoted/unquoted `rel` attributes, and space-separated multi-relations.
- **Full test coverage:** 17 tests (+18 cases, total suite 483/483 across 57 classes) spanning `ResponseHeadersTest` (8 unit tests) and `ResponseHeadersWireMockTest` (9 E2E integration tests: pagination, capture, Location, ETag, rate limits, async CompletableFuture, streaming InputStream, @OnError, UnexpectedStatusException, and null-safety).

Gates (session #100, clean rebuild):
- `./gradlew test` **483/483** (57 classes, 0 failures/errors; +18 tests in ADR-0022 across `ResponseHeadersTest` and `ResponseHeadersWireMockTest`).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved.

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — conditional requests & caching headers (ETag validation, 304 Not Modified) — done, section below. ADR number **0023** — conditional requests epic, **0024** free.

## Conditional requests, 304 Not Modified & Optional returns (ADR-0023) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, Optional unpacking, 304 handling, test strategy, status):
> [`docs/0.10.0/ADR-0023-conditional-requests-and-optional-returns.md`](docs/0.10.0/ADR-0023-conditional-requests-and-optional-returns.md).
> Session #101 (agent-loop). Library maturity & ergonomics (candidate #2 from DRAFT.md).
> Native support for HTTP conditional requests (RFC 9110 / RFC 7232: `If-None-Match`, `If-Modified-Since`, `If-Match`, `If-Unmodified-Since`),
> automatic recognition and acceptance of `304 Not Modified`, native `Optional<T>` return type support across sync and async
> (`CompletableFuture<Optional<T>>`) pipelines, and HTTP caching/condition helpers in `ResponseHeaders`.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0023):
- **Protocol-aware 304 Not Modified acceptance:** Requests carrying conditional headers (`If-None-Match`, `If-Modified-Since`, etc.) or methods returning `Optional<T>` automatically treat `304 Not Modified` as an expected status code without requiring manual `@ExpectStatus(statuses = {200, 304})` annotations. Strict precedence of explicit `@ExpectStatus` and `@OnError` annotations is preserved.
- **Domain vs Optional return on 304:** For domain returns `T` (e.g. `Item`), 304 returns `null` (since body is 0 bytes) and exposes metadata via `ResponseHeaders` (`headers.isNotModified() == true`). For `Optional<T>`, 304 returns `Optional.empty()`.
- **Native `Optional<T>` container unwrapping:** Methods returning `Optional<T>` or `CompletableFuture<Optional<T>>` have the generic argument `T` extracted by `HttpTemplateBuilder`. Successful responses deserialize payload into `T` and wrap into `Optional.ofNullable(...)` without requiring Jackson `jackson-datatype-jdk8` or external modules.
- **404/204/empty handling for Optional:** For `Optional<T>` methods, HTTP `404 Not Found`, `204 No Content`, and empty responses naturally evaluate to `Optional.empty()` without throwing `UnexpectedStatusException(404)`, while explicit `@OnError` continues to take precedence.
- **`ResponseHeaders` caching helpers:** Added `isNotModified()` (304), `isNotFound()` (404), `lastModified()`, `lastModifiedInstant()` (parses RFC 1123 HTTP-date into `java.time.Instant`), and `cacheControl()`.
- **Full test coverage:** 18 new tests (+18 cases, total suite 501/501 across 59 classes) spanning `ResponseHeadersTest` (+1 test: caching headers, RFC 1123 date parsing, status checks), `OptionalReturnTest` (11 tests: 200, 404, 204, 304, empty body, raw String, Void, generic List collections, async CompletableFuture, @OnError precedence, @ExpectStatus precedence), and `ConditionalRequestWireMockTest` (6 tests: conditional GET on 200 with new ETag, 304 Not Modified, If-Modified-Since with RFC 1123 date, Optional return on 304, ResponseHeaders.capture pattern, and async CompletableFuture twin).

Gates (session #101, clean rebuild):
- `./gradlew test` **501/501** (59 classes, 0 failures/errors; +18 tests in ADR-0023).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Form-data & multipart flexibility (nullable part values, explicit charset per part) — done, section below. ADR number **0024** — form-data epic, **0025** free.

## Form-data & multipart flexibility (ADR-0024) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, content-type and charset resolution, test strategy, status):
> [`docs/0.10.0/ADR-0024-form-data-and-multipart-flexibility.md`](docs/0.10.0/ADR-0024-form-data-and-multipart-flexibility.md).
> Session #102 (agent-loop). Real-world REST API ergonomics (candidate #2 from DRAFT.md).
> Support for explicit Content-Type, custom charsets (e.g. windows-1251, ISO-8859-1), JSON DTO parts,
> and null-safe form handling across multipart/form-data and application/x-www-form-urlencoded requests.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0024):
- **Explicit `contentType` in `@RequestParam`:** Added `String contentType() default ""` to `@RequestParam`. When specified in multipart requests, sets the part's `Content-Type` header. For JSON MIME types (e.g. `application/json`), complex DTO arguments and collections are automatically serialized to JSON using the client's configured `JsonCodec`.
- **Charset negotiation per part:** If `contentType` specifies a charset (e.g. `text/plain; charset=windows-1251` or `text/plain; charset="ISO-8859-1"`), text values are encoded using the designated `Charset`, defaulting to UTF-8.
- **`MultipartPart` factories & null-safety:** Extended `MultipartPart` with `text(name, value, charset)`, `part(name, contentType, bytes)`, and `part(name, filename, contentType, bytes)`. `text(name, null)` is hardened to be null-safe (produces 0-length byte array instead of throwing NPE).
- **Form-urlencoded null safety:** In `HttpTemplateBuilder.createUrlEncodedBody`, parameters with null values serialize as `name=` without throwing `NullPointerException`.
- **Full test coverage:** 16 new tests (+16 cases, total suite 517/517 across 61 classes) spanning `MultipartPartFlexibilityTest` (9 unit tests: charsets, null-safety, part factories, CRLF validation) and `MultipartFlexibilityWireMockTest` (7 WireMock E2E tests: JSON DTO entity part + file part, JSON String part, windows-1251 charset bytes, quoted ISO-8859-1 charset, raw byte[] with MIME type, collection of JSON DTOs, form-urlencoded empty/null parameter).

Gates (session #102, clean rebuild):
- `./gradlew test` **517/517** (61 classes, 0 failures/errors; +16 tests in ADR-0024).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — reactive/virtual thread ergonomics or custom URI template expansions — done, section below. ADR number **0025** — URI templates epic, **0026** free.

## URI template expansion, path encoding controls & array parameters (ADR-0025) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, path encoding controls, array parameters, test strategy, status):
> [`docs/0.10.0/ADR-0025-uri-template-expansion-and-parameter-arrays.md`](docs/0.10.0/ADR-0025-uri-template-expansion-and-parameter-arrays.md).
> Session #103 (agent-loop). Real-world REST API ergonomics and protocol compliance (candidate #2 from DRAFT.md).
> Support for unencoded / multi-segment path variables (`@PathVariable(encoded = true)`), universal array parameter handling
> across query parameters, headers, and form-urlencoded bodies, RFC 3986 compliant `#fragment` handling, and null-safe query params.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0025):
- **Path variable encoding control (`@PathVariable(encoded = true)`):** Added `boolean encoded() default false` to `@PathVariable`. By default (`encoded = false`), preserves percent-encoding UTF-8 (`/` -> `%2F`, space -> `%20`). When `encoded = true`, substitutes values directly as-is, supporting multi-segment paths (e.g. `files/${path}` -> `files/docs/2026/spec.pdf`) and avoiding double-encoding already percent-encoded strings. Fail-fast validation on null path variables is strictly preserved.
- **Universal array handling across parameters and headers:** Extended `createRequestParams`, `createUrlEncodedBody`, and `createHeaders` (alongside existing multipart array support from ADR-0024) to support Java arrays (`String[]`, `Object[]`, and primitive arrays `int[]`, `long[]`, etc. via reflection, excluding `byte[]` which serves as binary body). Empty arrays omit parameters/headers; null elements are skipped. Dynamic `Map<String, ?>` values containing arrays are also unpacked into repeated parameters/headers.
- **RFC 3986 URI fragment placement:** Implemented `HttpTemplate.appendQuery(String url, String query)` ensuring that when a URL carries a `#fragment`, query parameters are inserted before `#` (`base + separator + query + fragment`), maintaining valid HTTP request URI semantics.
- **Null-safe query parameter serialization:** In `createUri()`, `param.value() == null` is formatted safely as `encode(param.name())` without throwing `NullPointerException`.
- **Full test coverage:** 19 new tests (+19 cases, total suite 536/536 across 64 classes) spanning `HttpTemplateUriTest` (5 unit tests: query appending with/without fragments and existing query strings), `PathVariableEncodedTest` (4 tests: multi-segment paths with slashes, pre-encoded values, default percent-encoding, null fail-fast), and `ArrayParameterWireMockTest` (10 tests: String[] and int[] query params, empty arrays, null elements, Map array values, String[] headers, empty header arrays, Map array headers, form-urlencoded arrays, #fragment URI query execution).

Gates (session #103, clean rebuild):
- `./gradlew test` **536/536** (64 classes, 0 failures/errors; +19 tests in ADR-0025).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — reactive / virtual thread executor ergonomics — done, section below. ADR number **0026** — virtual threads epic, **0027** free.

## Virtual Threads & Executor Customization (ADR-0026) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, executor lifecycle, async dispatch, test strategy, status):
> [`docs/0.10.0/ADR-0026-virtual-threads-and-executor-customization.md`](docs/0.10.0/ADR-0026-virtual-threads-and-executor-customization.md).
> Session #104 (agent-loop). Real-world REST API ergonomics and modern Java 21 support (candidate #2 from DRAFT.md).
> Support for Java 21 Virtual Threads (`useVirtualThreads()`) and custom `Executor` configuration across asynchronous
> `CompletableFuture<T>` return types, response pipeline stages, interceptors, and retry backoff schedulers.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0026):
- **SPI configuration in `ModuleInfo`:** Added `default Executor getExecutor() { return null; }` and `default boolean isVirtualThreads() { return false; }`. Existing implementations preserve default platform thread pool.
- **Builder ergonomics:** Added fluent `executor(Executor)` (fail-fast NPE on null), `virtualThreads(boolean)`, and `useVirtualThreads()` to `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`. An explicit `executor` takes precedence over the `virtualThreads` flag.
- **Client transport wiring & lifecycle:** In `AbstractHttpClient`, if `moduleInfo.getExecutor()` is configured, wires it to `HttpClient.Builder.executor(...)`. If `moduleInfo.isVirtualThreads()` is enabled, instantiates a managed `Executors.newVirtualThreadPerTaskExecutor()`. On `client.close()`, the internally managed virtual thread executor is cleanly shut down (`managedExecutorService.close()`), while external executors are preserved.
- **Async pipeline execution & retry scheduling:** In `HttpTemplate`, when an executor is configured, `sendAsync`, `exchangeAsync` (JSON parsing, response interceptors), and `scheduleAsyncRetry` (via `CompletableFuture.delayedExecutor(delay, unit, executor)`) execute stages asynchronously on the configured executor or virtual threads (`thenApplyAsync` / `whenCompleteAsync`), ensuring thread context consistency.
- **Full test coverage:** 13 new tests (+13 cases, total suite 549/549 across 66 classes) spanning `ExecutorConfigTest` (8 unit tests: defaults, builder forwarding, null-guards, managed virtual thread executor shutdown on close, external executor preservation) and `VirtualThreadAndExecutorWireMockTest` (5 WireMock integration tests: async GET on virtual threads, custom named executor threads, negative test for default client, async retry backoff on virtual threads, async SSE stream on virtual threads).

Gates (session #104, clean rebuild):
- `./gradlew test` **549/549** (66 classes, 0 failures/errors; +13 tests in ADR-0026).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — per-method timeout controls (@Timeout) — done, section below. ADR number **0027** — timeout controls epic, **0028** free.

## Per-Method & Interface-Level Timeout Controls (@Timeout, ADR-0027) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, precedence model, test strategy, status):
> [`docs/0.10.0/ADR-0027-per-method-and-interface-timeout-controls.md`](docs/0.10.0/ADR-0027-per-method-and-interface-timeout-controls.md).
> Session #105 (agent-loop). Real-world REST API ergonomics and resilience (candidate #2 from DRAFT.md).
> Declarative request (socket) timeout overrides at method and controller interface levels (`@Timeout`),
> Duration overloads in client builders (`socketTimeout(Duration)` and `connectTimeout(Duration)`),
> upfront validation in `ControllerValidator`, and full integration across synchronous, asynchronous
> (`CompletableFuture<T>`), streaming, and `@Retry` transport pipelines.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0027):
- **Declarative `@Timeout` annotation:** Added `@Target({ElementType.METHOD, ElementType.TYPE})` annotation `ru.jrestly.annotation.Timeout` with `long value()` and `TimeUnit unit() default TimeUnit.MILLISECONDS`. Supports `@Timeout(500)` (500 ms) and `@Timeout(value = 5, unit = TimeUnit.SECONDS)` (5 seconds).
- **Precedence model:** Method-level `@Timeout` > Sub-interface `@Timeout` > Super-interface `@Timeout` > Client-level `socketTimeout`. Defaults preserve `moduleInfo.getSocketTimeout()` (30 000 ms).
- **Builder ergonomics:** Added idiomatic `Duration` overloads `connectTimeout(Duration)` and `socketTimeout(Duration)` to `SimpleModuleInfo.Builder` and `JRestlyClient.Builder` with fail-fast `NullPointerException` and non-negative `IllegalArgumentException` validation.
- **Fail-fast validation:** `ControllerValidator` validates that `@Timeout.value() > 0` on interfaces and methods at proxy creation time, raising `ControllerValidationException`. `HttpTemplateBuilder.resolveTimeout()` performs runtime validation as a backstop.
- **Transport & pipeline wiring:** `HttpTemplate.toHttpRequest(WireRequest)` configures `HttpRequest.Builder.timeout(effectiveTimeout)`. Sync calls throw `RuntimeException(HttpTimeoutException)` on expiration; async calls complete exceptionally with nested `HttpTimeoutException`; `@Retry` transport retry recovers transparently from timeouts.
- **Full test coverage:** 13 new tests (+13 cases, total suite 562/562 across 68 classes) spanning `TimeoutConfigTest` (6 unit tests: defaults, Duration builder overloads, null/negative guards, ControllerValidator rules, precedence resolution across method/interface hierarchy) and `TimeoutWireMockTest` (7 WireMock integration tests: 100ms timeout rejection on 500ms delay, 2s timeout success, interface-level inheritance, method-level override, async CompletableFuture exceptional completion, retry policy recovery from transport timeout, streaming response within budget).

Gates (session #105, clean rebuild):
- `./gradlew test` **562/562** (68 classes, 0 failures/errors; +13 tests in ADR-0027).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — custom error decoders / typed error hierarchy refinement — done, section below. ADR number **0028** — repeatable @OnError & typed exceptions epic, **0029** free.

## Repeatable @OnError, Type-Level Error Handlers & Refined Exception Hierarchy (ADR-0028) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, precedence model, test strategy, status):
> [`docs/0.10.0/ADR-0028-repeatable-on-error-and-typed-exceptions.md`](docs/0.10.0/ADR-0028-repeatable-on-error-and-typed-exceptions.md).
> Session #106 (agent-loop). Real-world REST API error handling maturity and developer ergonomics (candidate #2 from DRAFT.md).
> Repeatable `@OnError` declarations on methods and interfaces (`ElementType.METHOD`, `ElementType.TYPE`),
> multi-status mapping resolution in `OnErrorParser` and `HttpTemplateBuilder`, direct throwing of custom
> `RuntimeException` / `Throwable` exception classes without manual unwrapping, refined `ClientErrorException` (HTTP 4xx)
> and `ServerErrorException` (HTTP 5xx) extending `UnexpectedStatusException`, and upfront validation in `ControllerValidator`.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0028):
- **Repeatable `@OnError` annotation with type-level scope:** Updated `ru.jrestly.annotation.OnError` to support `@Target({ElementType.METHOD, ElementType.TYPE})`, `@Repeatable(OnError.List.class)`, and container annotation `ru.jrestly.annotation.OnError.List`. Enables declaring distinct `@OnError` mappings for different HTTP error codes on the same method or interface.
- **Hierarchical precedence resolution:** Method-level `@OnError` > Sub-interface `@OnError` > Super-interface `@OnError`. Method rules take precedence for declared status codes, while unoverridden status codes are inherited from interface declarations.
- **Multi-status mapping in `OnErrorParser`:** Refactored `OnErrorParser` to maintain a status-to-errorClass mapping with `register(int, Class<?>)`, `registerDefault(Class<?>)`, `canParse(int)`, and `getErrorClass(int)`. 100% backwards compatible with legacy getters and setters.
- **Direct exception throwing:** When an `@OnError` rule specifies an `errorObject` assignable to `Throwable`:
  - Deserializes into the target class and throws directly if `RuntimeException` (or wraps checked `Throwable` in `RuntimeException`).
  - Empty error payloads instantiate the target exception via reflection fallback: tries `(details, status, headers)`, `(message, status)`, `(message)`, or no-arg constructors, with fallback to `HandledException`.
  - Non-throwable DTO classes continue to be wrapped in `HandledException(details, status, headers)`.
  - Propagates seamlessly through `exchange()` and async `exchangeAsync()` (`CompletableFuture<T>`).
- **Refined exception hierarchy:** Added `ClientErrorException` (HTTP 4xx) and `ServerErrorException` (HTTP 5xx) extending `UnexpectedStatusException`. Callers can catch specific error classes (`catch (ClientErrorException e)`) while existing code catching `UnexpectedStatusException` or `HandledException` functions without change.
- **Fail-fast validation:** `ControllerValidator` validates status ranges (`100..599`) and detects conflicting mappings of the same status code to different classes at the same declaration level.
- **Full test coverage:** 17 new tests (+17 cases, total suite 579/579 across 70 classes) spanning `OnErrorConfigTest` (7 unit tests: parser multi-status, legacy setters, exception hierarchy, validator invalid status & duplicate conflict detection, proxy creation) and `OnErrorWireMockTest` (10 WireMock integration tests: repeatable @OnError matching distinct DTOs, interface-level inheritance, method override, direct custom RuntimeException throwing, reflection fallback on empty body, HandledException subclass throwing, undeclared 4xx ClientErrorException, undeclared 5xx ServerErrorException, async CompletableFuture completion, streaming InputStream error consumption).

Gates (session #106, clean rebuild):
- `./gradlew test` **579/579** (70 classes, 0 failures/errors; +17 tests in ADR-0028).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — declarative `@WithInterceptor` per controller/method — done, section below. ADR number **0029** — declarative interceptors epic, **0030** free.

## Declarative Interceptors (@WithInterceptor) per Controller & Method (ADR-0029) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, pipeline ordering, validation, test strategy, status):
> [`docs/0.10.0/ADR-0029-declarative-interceptors-with-interceptor.md`](docs/0.10.0/ADR-0029-declarative-interceptors-with-interceptor.md).
> Session #107 (agent-loop). Real-world API gateway & microservice ergonomics (candidate #2 from DRAFT.md).
> Declarative binding of `RequestInterceptor` and `ResponseInterceptor` classes via `@WithInterceptor` on controller
> interfaces (`ElementType.TYPE`) and individual methods (`ElementType.METHOD`), with `@Repeatable(WithInterceptor.List.class)` support.
> Zero-dep hot-path zero-allocation caching in `AbstractHttpClient` / `HttpTemplateBuilder`, deterministic pipeline ordering
> (Global &rarr; Super-interface &rarr; Sub-interface &rarr; Method), deduplication, and upfront fail-fast validation in `ControllerValidator`.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0029):
- **`@WithInterceptor` annotation with repeatable & array support:** Created `ru.jrestly.annotation.WithInterceptor` supporting `@Target({ElementType.TYPE, ElementType.METHOD})`, `@Repeatable(WithInterceptor.List.class)`, and container annotation `ru.jrestly.annotation.WithInterceptor.List`. Accepts single or multiple interceptor classes (`@WithInterceptor(MyInterceptor.class)`, `@WithInterceptor({A.class, B.class})`).
- **Dual interceptor support:** Declared classes may implement `RequestInterceptor`, `ResponseInterceptor`, or both simultaneously (e.g. stateful auditing/metrics).
- **Deterministic pipeline ordering:**
  - `RequestInterceptor`: Global Client &rarr; Super-interface &rarr; Sub-interface &rarr; Method.
  - `ResponseInterceptor`: Global Client &rarr; Super-interface &rarr; Sub-interface &rarr; Method.
- **Hot-path zero-allocation & caching:** Interceptor instances are instantiated once via accessible no-arg constructor and cached per client (`ConcurrentHashMap<Class<?>, Object>`), avoiding allocations on hot execution paths and supporting stateful singletons.
- **Deduplication:** Repeated declarations of the same interceptor class across hierarchy levels execute only once.
- **Upfront fail-fast validation:** `ControllerValidator` verifies that declared classes implement `RequestInterceptor` or `ResponseInterceptor`, are not interfaces or abstract classes, and declare a no-arg constructor. Violations throw `ControllerValidationException` at client initialization.
- **Full test coverage:** 17 new tests (+17 cases, total suite 596/596 across 72 classes) spanning `InterceptorAnnotationTest` (7 unit tests: validator invalid class, abstract class, missing no-arg constructor, interface-level failure, proxy initialization exception, pipeline hierarchy ordering, deduplication) and `DeclarativeInterceptorWireMockTest` (10 WireMock integration tests: interface-level interceptor applied to all methods, method-level interceptor applied only to targeted method, multi-level hierarchy ordering with global interceptor, multi-class array execution, repeatable annotation execution, dual request/response auditing, status code observation, async CompletableFuture twin, streaming InputStream twin, stateful singleton instance reuse).

Gates (session #107, clean rebuild):
- `./gradlew test` **596/596** (72 classes, 0 failures/errors; +17 tests in ADR-0029).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — multipart streaming / upload progress listener — done, section below. ADR number **0030** — upload progress listener epic, **0031** free.

## Upload Progress Tracking (UploadProgressListener, ADR-0030) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, reactive streams integration, validation, test strategy, status):
> [`docs/0.10.0/ADR-0030-upload-progress-listener.md`](docs/0.10.0/ADR-0030-upload-progress-listener.md).
> Session #108 (agent-loop). Real-world API file upload ergonomics (candidate #2 from DRAFT.md).
> Functional `UploadProgressListener` callback for tracking request body upload progress as bytes are transmitted
> to the network transport. Built on standard Java 21 `java.net.http.HttpRequest.BodyPublisher` and `java.util.concurrent.Flow`
> reactive streams. Declared as an unannotated intrinsic method parameter on controller interfaces without polluting payload parameters.
> Supports multipart file uploads (Path, FileContent, streams), raw binary payloads, JSON DTOs, async CompletableFuture methods, and retry resets.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0030):
- **Functional interface `UploadProgressListener`:** Added `ru.jrestly.http.UploadProgressListener` with single method `void onProgress(long bytesSent, long totalBytes)`. Reports monotonic bytes sent for the current attempt and total body length in bytes (or `-1` if unknown/chunked).
- **Intrinsic parameter pattern (ADR-0022 alignment):** Declared directly on declarative controller methods without special annotations. Excluded from payload parameter count and not serialized into query params, headers, or body parts. Passing `null` is safe and introduces zero overhead.
- **Reactive streams interception via `ProgressBodyPublisher`:** Package-private `HttpRequest.BodyPublisher` decorator wraps the delegate publisher. Intercepts subscriber `onNext(ByteBuffer)` calls, reads `item.remaining()` before passing downstream, updates running totals, and dispatches callbacks.
- **Retry & redirect independence:** Each send or retry attempt creates a fresh publisher via `HttpTemplate.bodyPublisher(WireRequest)`, naturally resetting `bytesSent` to 0 per attempt.
- **Upfront fail-fast validation:** `ControllerValidator` ensures `UploadProgressListener` parameters are not mistakenly annotated with `@RequestParam`, `@PathVariable`, `@RequestHeader`, or `@MultipartFormFile` (`ControllerValidationException`).
- **Full test coverage:** 24 new tests (+24 cases, total suite 620/620 across 75 classes) spanning:
  - `ProgressBodyPublisherTest` (5 unit tests: exact contentLength delegation, null fail-fast, Flow.Subscriber sequential progress tracking, unknown length -1 tracking, independent subscriber reset for retry);
  - `UploadProgressValidationTest` (8 unit tests: validator rejection of annotated progress listener, multipart parameter count validation, valid controller acceptance, proxy initialization validation exception, HttpTemplateBuilder extraction and null-safety);
  - `UploadProgressWireMockTest` (11 WireMock integration tests: single multipart file upload tracking to 100%, disk Path upload, multi-file upload tracking, stream content unknown length -1, raw bytes upload, JSON DTO upload, async CompletableFuture twin, null-safety, unannotated method regression safety, retry attempt reset, large 128 KiB chunked payload).

Gates (session #108, clean rebuild):
- `./gradlew test` **620/620** (75 classes, 0 failures/errors; +24 tests in ADR-0030).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — declarative `@Accept` / `@ContentType` short-cuts or response body streaming progress listener — done, section below. ADR number **0031** — declarative @Accept / @ContentType epic, **0032** free.

## Declarative @Accept & @ContentType and MediaType Constants (ADR-0031) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, media type negotiation, validation, test strategy, status):
> [`docs/0.10.0/ADR-0031-declarative-accept-and-content-type.md`](docs/0.10.0/ADR-0031-declarative-accept-and-content-type.md).
> Session #109 (agent-loop). Real-world REST media type negotiation & payload ergonomics (candidate #2 from DRAFT.md).
> Declarative `@Accept` (single and multi-value media types joined via comma per RFC 9110) and `@ContentType` annotations
> for controller interfaces (`ElementType.TYPE`) and methods (`ElementType.METHOD`), standard `ru.jrestly.http.MediaType` constants,
> resolution pipeline (`Method > Sub-interface > Super-interface > Client default`, with runtime `@RequestHeader` priority override),
> zero-duplicate `Content-Type` guarantee in `toHttpRequest()` (`setHeader("Content-Type", ...)`), and direct byte array / charset-aware string body delivery.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0031):
- **Declarative `@Accept` annotation:** Added `ru.jrestly.annotation.Accept` (`@Target({ElementType.TYPE, ElementType.METHOD})`) with `String[] value()`. Concatenates multiple media types into a single standard comma-separated `Accept` header.
- **Declarative `@ContentType` annotation:** Added `ru.jrestly.annotation.ContentType` (`@Target({ElementType.TYPE, ElementType.METHOD})`) with `String value()`. Overrides the default MIME type of the verb's `requestType`.
- **Standard `MediaType` constants:** Added `ru.jrestly.http.MediaType` with canonical constants: `APPLICATION_JSON`, `APPLICATION_XML`, `APPLICATION_OCTET_STREAM`, `APPLICATION_FORM_URLENCODED`, `MULTIPART_FORM_DATA`, `TEXT_PLAIN`, `TEXT_HTML`, `TEXT_XML`, `TEXT_EVENT_STREAM`, `APPLICATION_NDJSON`, `ALL`.
- **Strict precedence and runtime override:**
  - `Accept`: Method `@Accept` > Sub-interface `@Accept` > Super-interface `@Accept`. Runtime `@RequestHeader(name = "Accept")` takes ultimate precedence.
  - `ContentType`: Method `@ContentType` > Sub-interface `@ContentType` > Super-interface `@ContentType` > `requestType.mime()`. Runtime `@RequestHeader(name = "Content-Type")` takes ultimate precedence.
  - Multipart methods preserve boundary generation (`boundary=jrestly-...`).
- **Zero-duplicate `Content-Type` header guarantee:** In `HttpTemplate.toHttpRequest()`, `Content-Type` is set via `builder.setHeader("Content-Type", request.contentType())` preventing duplicate headers when both headers map and content type slot are populated.
- **Direct payload passthrough & charset awareness:**
  - `byte[]` parameters passed to `@RequestBody` are sent directly as raw binary bytes without unwanted JSON stringification.
  - `String` parameters are encoded into bytes using the charset declared in `ContentType` (or UTF-8 default).
  - Arbitrary DTOs are serialized via configured `JsonCodec`.
- **Upfront fail-fast validation:** `ControllerValidator` verifies non-empty and non-blank values, and forbids CRLF injection characters (`\r`, `\n`) on both `@Accept` and `@ContentType` (`ControllerValidationException`).
- **Full test coverage:** 25 new tests (+25 cases, total suite 646/646 across 77 classes) spanning:
  - `AcceptContentTypeValidationTest` (12 unit tests: interface/method empty array, blank string, CRLF injection, client proxy initialization exception, and clean validation of valid contracts);
  - `AcceptContentTypeWireMockTest` (13 WireMock integration tests: interface-level Accept propagation, method-level Accept override, comma-separated multi-media-type Accept, runtime RequestHeader Accept override and null fallback, interface-level Content-Type propagation, method-level Content-Type override, custom charset body encoding like windows-1251, raw binary bytes passthrough, strictly one Content-Type header in request without duplicates, runtime Content-Type override, interface inheritance hierarchy resolution, async CompletableFuture twin, multipart with custom Content-Type boundary preservation).

Gates (session #109, clean rebuild):
- `./gradlew test` **646/646** (77 classes, 0 failures/errors; +26 tests across test suites).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Cookie handling & session management (RFC 6265, `CookieHandler` zero-dep integration) — done, section below. ADR number **0032** — cookie handling and declarative @Cookie epic, **0033** free.

## Cookie Handling, Session Management and Declarative @Cookie (ADR-0032) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, session management, validation, test strategy, status):
> [`docs/0.10.0/ADR-0032-cookie-handling-and-session-management.md`](docs/0.10.0/ADR-0032-cookie-handling-and-session-management.md).
> Session #110 (agent-loop). Real-world web API & session continuity ergonomics (candidate #1 from DRAFT.md).
> Client-level automatic cookie and session persistence via standard `java.net.CookieHandler` / `CookieManager`,
> declarative `@Cookie` (`ElementType.TYPE`, `ElementType.METHOD`, `ElementType.PARAMETER`, repeatable `@Cookies`),
> precedence hierarchy (`Parameter > Method > Sub-interface > Super-interface`), `Map<String, ?>` expansion,
> direct `java.net.HttpCookie` parameter support, null-safety override/removal, and first-class response cookie inspection
> in `ResponseHeaders` (`cookies()`, `cookie(name)`, `cookieValue(name)`, `cookieMap()`) with `client.getCookieStore()`.
> 100% zero-dependency on standard Java 21 platform classes (`java.net.*`).

Key decisions (ADR-0032):
- **Client-level Cookie & Session Management:**
  - `ModuleInfo`: added default method `default CookieHandler getCookieHandler() { return null; }`.
  - `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`: added `cookieHandler(CookieHandler)`, `cookieManager()` (shortcut enabling `new CookieManager()`), and `cookiePolicy(CookiePolicy)`.
  - `AbstractHttpClient`: connects `moduleInfo.getCookieHandler()` to `HttpClient.Builder.cookieHandler(...)` on `createConnection()`; exposes `getCookieHandler()` and `Optional<CookieStore> getCookieStore()`.
- **Declarative `@Cookie` and `@Cookies` annotations:**
  - Added `ru.jrestly.annotation.Cookie` (`@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})`) with attributes `name` and `value`, repeatable via `ru.jrestly.annotation.Cookies`.
  - Interface-level and method-level static cookies are serialized into the standard RFC 6265 `Cookie: name=value; name2=value2` request header.
  - Method arguments annotated with `@Cookie` provide dynamic values. An unsupplied name defaults to the Java parameter name. Passing `null` as an argument removes or suppresses that specific cookie.
  - Supports `Map<String, ?>` parameters for multi-cookie dictionaries and `HttpCookie` objects directly.
- **Precedence and merge rules:**
  - `Parameter > Method > Sub-interface > Super-interface`.
  - Merges cleanly with any static or runtime `@RequestHeader(name = "Cookie")` declaration (`Cookie: header_val; declarative_val`).
- **Response Cookie Inspection (`ResponseHeaders`):**
  - Added `cookies()` (returns parsed `List<HttpCookie>`), `cookie(name)` (case-insensitive lookup returning `Optional<HttpCookie>`), `cookieValue(name)` (shortcut for `cookie(name).map(HttpCookie::getValue)`), and `cookieMap()` (case-insensitive `Map<String, String>`).
  - Robust against malformed `Set-Cookie` headers.
- **Upfront fail-fast validation (`ControllerValidator`):**
  - Forbids blank names and CRLF injection characters (`\r`, `\n`) on interface, method, and parameter level (`ControllerValidationException`).
  - Ensures `@Cookie` parameters are not mistakenly combined with conflicting HTTP parameter annotations (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@MultipartFormFile`).
  - Excludes `@Cookie` parameters from multipart/form-urlencoded body payload calculations.
- **Full test coverage:** 29 new tests (+29 cases, total suite 675/675 across 80 classes) spanning:
  - `CookieConfigAndResponseTest` (13 unit tests: ModuleInfo/SimpleModuleInfo/JRestlyClient default nulls, custom CookieHandler wiring, cookieManager and cookiePolicy shortcuts, fail-fast NPE checks, getCookieStore inspection, ResponseHeaders unpopulated empty cookies, single Set-Cookie parse, multiple Set-Cookie & Set-Cookie2 parse, case-insensitive cookieValue lookup, cookieMap case-insensitivity, malformed Set-Cookie resilience, null checks);
  - `CookieValidationTest` (7 unit tests: blank interface cookie name, CRLF in interface cookie name, CRLF in interface cookie value, method-level cookie problems, parameter-level cookie problems, valid contract pass, client proxy validation exception);
  - `CookieSessionWireMockTest` (9 WireMock integration tests: automatic session management across requests with Set-Cookie -> Cookie continuity, session clearing via getCookieStore().removeAll(), interface-level Cookie header propagation, method-level Cookie combination, repeatable Cookies on method, parameter-level dynamic Cookie and null omission, parameter-level override of static cookie and null suppression, Map<String, Object> cookie expansion, HttpCookie parameter passing, async CompletableFuture twin with cookies).

Gates (session #110, clean rebuild):
- `./gradlew test` **675/675** (80 classes, 0 failures/errors; +29 tests in ADR-0032).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Response download progress tracking (`DownloadProgressListener`, symmetric to ADR-0030 for streaming downloads) — done, section below. ADR number **0033** — download progress listener epic, **0034** free.

## Response Download Progress Tracking & Direct Byte Array Returns (ADR-0033) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, reactive BodySubscriber interception, validation, test strategy, status):
> [`docs/0.10.0/ADR-0033-download-progress-listener.md`](docs/0.10.0/ADR-0033-download-progress-listener.md).
> Session #111 (agent-loop). Real-world REST download observability & binary ergonomics (candidate #1 from DRAFT.md).
> Symmetrical companion to `UploadProgressListener` (ADR-0030): unannotated intrinsic method parameter
> `DownloadProgressListener` (`void onProgress(long bytesRead, long totalBytes)`), reactive `ProgressBodyHandler` &
> `ProgressBodySubscriber` decorators for Java 21 `java.net.http.HttpResponse.BodySubscriber<List<ByteBuffer>>`,
> automatic `Content-Length` extraction or `-1` for chunked streams, independent counter reset across `@Retry` loops,
> zero-overhead null safety, upfront fail-fast `ControllerValidator` rules (`ControllerValidationException`),
> and direct `byte[]` return type support bypassing `JsonCodec` for high-performance raw binary downloads.
> 100% zero-dependency on standard Java 21 platform classes.

Key decisions (ADR-0033):
- **Functional Interface (`ru.jrestly.http.DownloadProgressListener`):**
  - Declares `@FunctionalInterface` with method `void onProgress(long bytesRead, long totalBytes)`.
  - May be placed as an unannotated parameter on any controller method without interfering with query parameters or request headers.
- **Reactive Streams Interception (`ProgressBodyHandler` & `ProgressBodySubscriber`):**
  - Package-private decorating `BodyHandler<T>` that inspects `ResponseInfo.headers()` for `Content-Length`.
  - Nested `ProgressBodySubscriber<T>` decorates `Flow.Subscriber<List<ByteBuffer>>`, accumulating `remaining()` bytes without mutating buffer positions before delegating to downstream.
  - Zero-byte body support: emits `onProgress(0, 0)` on completion if body was empty.
  - Symmetrical to ADR-0030: independent counter state reset per attempt during `@Retry` or redirect resends.
- **Direct `byte[]` & `Optional<byte[]>` Returns:**
  - `HttpTemplate.processResponse()` recognizes `byte[].class.equals(returnType)` and returns `response.body()` directly as `byte[]` (or `Optional.of(bytes)`), removing unwanted JSON deserialization overhead on binary endpoints.
- **Upfront fail-fast validation (`ControllerValidator`):**
  - Forbids HTTP parameter annotations (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@MultipartFormFile`) on `DownloadProgressListener` parameters (`ControllerValidationException`).
  - Excludes `DownloadProgressListener` parameters from multipart/form-urlencoded body payload calculations.
- **Full test coverage:** 28 new tests (+28 cases, total suite 703/703 across 83 classes) spanning:
  - `ProgressBodySubscriberTest` (7 unit tests: constructor/apply fail-fast NPE checks, sequential buffer interception, chunked unknown length `-1`, empty body handling with total 0, onError delegation, buffer position preservation, ResponseInfo length extraction);
  - `DownloadProgressValidationTest` (9 unit tests: rejection of @RequestParam, @PathVariable, @RequestHeader, @Cookie, @MultipartFormFile annotations on listener, rejection of only-listener multipart methods, valid contract passing, client proxy creation exception, HttpTemplateBuilder resolution & null safety);
  - `DownloadProgressWireMockTest` (12 WireMock integration tests: byte[] download progress, text String download progress, JSON DTO download progress, live streaming InputStream download, chunked transfer encoding with total -1, async CompletableFuture download, retry attempt counter reset, simultaneous UploadProgressListener + DownloadProgressListener on single method, null listener safety, parameterless method regression freedom, large download > 128 KiB with multiple progress chunks, transparent decompression wire bytes tracking).

Gates (session #111, clean rebuild):
- `./gradlew test` **703/703** (83 classes, 0 failures/errors; +28 tests in ADR-0033).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Query parameter multi-value formats (`explode`, comma-separated `tag=a,b`, pipe-separated `tag=a|b`, RFC 6570 style expansion) — done, section below. ADR number **0034** — query collection format epic, **0035** free.

## Query Parameter Collection Formatting Styles (ADR-0034) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, precedence hierarchy, validation, test strategy, status):
> [`docs/0.10.0/ADR-0034-query-parameter-collection-formats.md`](docs/0.10.0/ADR-0034-query-parameter-collection-formats.md).
> Session #112 (agent-loop). Real-world REST query parameter formatting ergonomics & OpenAPI compatibility (candidate #1 from DRAFT.md).
> Introduces `CollectionFormat` enum (`DEFAULT`, `EXPLODE`, `CSV`, `SSV`, `TSV`, `PIPES`, `BRACKETS`),
> `@QueryCollectionFormat` annotation on controller interfaces and methods,
> and `@RequestParam(format = ...)` attribute on parameters.
> Supports collections (`Collection<?>`), primitive and object arrays (`String[]`, `int[]`, etc.),
> dynamic maps (`@RequestParam Map<String, Object>`), form-urlencoded requests, and multipart parts.
> Strict precedence: `Parameter format > Method @QueryCollectionFormat > Sub-interface @QueryCollectionFormat > Super-interface @QueryCollectionFormat > EXPLODE (default)`.
> 100% backward compatible and zero-dependency.

Key decisions (ADR-0034):
- **`CollectionFormat` Enum (`ru.jrestly.annotation.CollectionFormat`):**
  - Defines supported collection formatting styles: `DEFAULT`, `EXPLODE` (`tag=a&tag=b`), `CSV` (`tag=a,b`), `SSV` (`tag=a%20b`), `TSV` (`tag=a%09b`), `PIPES` (`tag=a%7Cb`), and `BRACKETS` (`tag[]=a&tag[]=b`).
- **Declarative Formatting (`@QueryCollectionFormat` & `@RequestParam.format`):**
  - `@QueryCollectionFormat(CollectionFormat)` applicable at both `ElementType.TYPE` and `ElementType.METHOD`.
  - `@RequestParam(format = CollectionFormat)` per-parameter override.
  - Precedence hierarchy: Parameter format (if not `DEFAULT`) > Method `@QueryCollectionFormat` > Sub-interface `@QueryCollectionFormat` > Super-interface `@QueryCollectionFormat` > fallback `EXPLODE`.
- **Value Extraction & Null Safety:**
  - Empty collections or arrays containing only `null`s are completely omitted from query/form.
  - Null items in collections/arrays are filtered out before applying formatting.
  - Scalar arguments (e.g. `int page`) are unaffected by collection formatting and always serialize as `param=value`.
  - Dynamic maps (`@RequestParam Map<String, Object>`) format collection and array values using effective collection format.
- **Form-Urlencoded & Multipart Symmetry:**
  - `APPLICATION_FORM_URLENCODED` serializes formatted parameters into URL-encoded body matching query formatting rules.
  - `MULTIPART_FORM_DATA` concatenates collection values into a single part for delimiter-based formats (`CSV`, `SSV`, `TSV`, `PIPES`) and creates multiple parts with `name[]` for `BRACKETS`.
- **Upfront Fail-Fast Validation (`ControllerValidator`):**
  - Validates `@QueryCollectionFormat` and `@RequestParam.format` are non-null.
- **Full test coverage:** 26 new tests (+26 cases, total suite 729/729 across 85 classes) spanning:
  - `CollectionFormatValidationTest` (9 unit tests: validation pass across all formats, enum integrity, hierarchy resolution with sub-interface override, method override, parameter format override, scalar parameter immunity, empty/null collection omission, null element filtering, client proxy creation);
  - `CollectionFormatWireMockTest` (17 WireMock integration tests: CSV List, CSV String[] array, CSV primitive int[] array, SSV List, TSV List, PIPES List, BRACKETS List with tag[] parameters, parameter-level override of method format, dynamic map collection formatting, single-item collection handling, interface-level format inheritance, method override of interface format, form-urlencoded CSV, form-urlencoded BRACKETS, multipart CSV single part, multipart BRACKETS parts named tags[], async CompletableFuture twin).

Gates (session #112, clean rebuild):
- `./gradlew test` **729/729** (85 classes, 0 failures/errors; +26 tests in ADR-0034).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Request body streaming from `Flow.Publisher<ByteBuffer>`. ADR number **0035** free.

## Outgoing Request Body Streaming & Validation (ADR-0035) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, streaming wire model, validation, test strategy, status):
> [`docs/0.10.0/ADR-0035-request-body-streaming.md`](docs/0.10.0/ADR-0035-request-body-streaming.md).
> Session #113 (agent-loop). Full symmetrical streaming support for outgoing `@RequestBody` (candidate #1 from DRAFT.md).
> Enables reactive chunked streaming via `Flow.Publisher<ByteBuffer>` and `Flow.Publisher<String>`,
> native standard Java `HttpRequest.BodyPublisher`, `InputStream`, `Supplier<? extends InputStream>` (retry/redirect safe),
> `Supplier<? extends HttpRequest.BodyPublisher>`, `Path`, and `File` without eager in-memory buffering.
> Enhanced with upfront fail-fast controller validation and controller-level `@HttpVersion`.
> 100% backward compatible and zero-dependency.

Key decisions (ADR-0035):
- **Streaming `@RequestBody` Types:**
  - `Flow.Publisher<ByteBuffer>` / `Flow.Publisher<? extends ByteBuffer>`: native reactive streaming chunks via standard `HttpRequest.BodyPublishers.fromPublisher(...)`.
  - `Flow.Publisher<String>`: text/string streaming chunks automatically adapted to `ByteBuffer` publishers with charset encoding.
  - `HttpRequest.BodyPublisher`: direct delegation to JDK body publisher.
  - `InputStream`: streaming via `HttpRequest.BodyPublishers.ofInputStream(() -> stream)`.
  - `Supplier<? extends InputStream>`: reusable stream supplier providing fresh streams across retries and redirect hops.
  - `Supplier<? extends HttpRequest.BodyPublisher>` / `Supplier<? extends Flow.Publisher<ByteBuffer>>`: reusable publisher suppliers.
  - `Path` and `File`: zero-copy file streaming with exact `Content-Length` via `HttpRequest.BodyPublishers.ofFile(path)`.
- **Default Media Type (`Content-Type`) Ergonomics:**
  - Streaming types default to `application/octet-stream` when not explicitly specified via `@ContentType` or `@Post(contentType = ...)`.
  - Explicit Content-Type annotations/headers are strictly honored (e.g. `@ContentType("application/x-ndjson")`).
  - Conventional POJOs, `String`, and `byte[]` maintain backward-compatible default `application/json`.
- **Wire Model & Builder Integration (`WireRequest`):**
  - Added `Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier` to `WireRequest`.
  - Preserved backward-compatible 7-parameter canonical constructor and added fluent builder methods (`bodyPublisher`, `bodyPublisherSupplier`).
  - Seamlessly integrates with `UploadProgressListener` via `ProgressBodyPublisher` for chunked streams.
  - Safe request logging (`<streaming body>`) prevents accidental stream consumption during logging.
- **Fail-Fast Validation & Controller-Level `@HttpVersion`:**
  - Extended `ControllerValidator` with upfront checks: at most one `@RequestBody` parameter per method, prohibition of combining `@RequestBody` with other HTTP parameter annotations, and prohibition of `@RequestBody` on `@Get` methods.
  - Extended `@HttpVersion` to `ElementType.TYPE` so whole controller interfaces can specify protocol versions (e.g. `HTTP_1_1`).
- **Full test coverage:** 20 new tests (+20 cases, total suite 749/749 across 87 classes) spanning:
  - `RequestBodyValidationTest` (8 unit tests: reject @Get with @RequestBody, reject multiple @RequestBody parameters, reject combination of @RequestBody with @RequestParam/@PathVariable/@RequestHeader/@Cookie, pass valid streaming interface, client proxy creation);
  - `RequestBodyStreamingWireMockTest` (10 WireMock integration tests: Flow.Publisher<ByteBuffer> chunked streaming, Flow.Publisher<String> automatic UTF-8 adaptation, explicit @ContentType("application/x-ndjson"), InputStream streaming, Supplier<InputStream> with 503 retry and fresh stream recreation, Path file upload with known Content-Length, direct HttpRequest.BodyPublisher upload, UploadProgressListener progress tracking across chunks, async CompletableFuture twin, null publisher handling);
  - `WireRequestTest` (2 unit tests: withBodyPublisher/withBodyPublisherSupplier withers and builder preservation).

Gates (session #113, clean rebuild):
- `./gradlew test` **749/749** (87 classes, 0 failures/errors; +20 tests in ADR-0035).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Client Metrics & Observability SPI (Micrometer / OpenTelemetry readiness) — done, section below. ADR number **0036** — client metrics epic, **0037** free.

## Client Metrics & Observability SPI (ADR-0036) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, metrics contract, lifecycle hooks, validation, test strategy, status):
> [`docs/0.10.0/ADR-0036-client-metrics-and-observability-spi.md`](docs/0.10.0/ADR-0036-client-metrics-and-observability-spi.md).
> Session #114 (agent-loop). Zero-dependency metrics & observability SPI for seamless integration with Micrometer, OpenTelemetry, Dropwizard, or custom in-house monitoring (candidate #2 from DRAFT.md).
> Introduces `@FunctionalInterface ClientMetricsListener` with lifecycle callbacks (`onCallStart`, `onRetry`, `onAttemptComplete`, and mandatory `onCallComplete(CallMetrics)`),
> immutable `CallMetrics` record / contract exposing timing (`duration`), attempt count (`attempts`), wire sizes (`requestBytes`, `responseBytes`), target (`method`, `uri`), context (`RequestContext`), wire models (`WireRequest`, `WireResponse`), and failures (`failure`),
> `@WithMetrics` declarative annotation for controller interfaces and methods,
> client-level configuration via `JRestlyClient.Builder.metricsListener(...)` and `ModuleInfo`,
> composite listener support via `ClientMetricsListener.composite(...)` and upfront fail-fast controller validation.
> 100% backward compatible and zero-dependency (Java 21 stdlib only).

Key decisions (ADR-0036):
- **Zero-Dependency SPI Design (`ru.jrestly.metrics`):**
  - Standard Java 21 core with no external dependencies (zero micrometer/opentelemetry compile/runtime deps). Third-party monitoring libraries integrate by implementing `ClientMetricsListener`.
  - Immutable `CallMetrics` interface and `SimpleCallMetrics` canonical record with validation.
  - `@FunctionalInterface ClientMetricsListener` provides `onCallComplete(CallMetrics)` as the core aggregation hook, plus default lifecycle hooks `onCallStart`, `onRetry`, and `onAttemptComplete` for active in-flight request gauges and retry counters.
  - Safe failure isolation: listener errors are caught and logged at `WARNING` level; listener exceptions never fail or abort user HTTP calls.
- **Declarative & Programmatic Configuration:**
  - Programmatic: `JRestlyClient.Builder.metricsListener(ClientMetricsListener)` and `ModuleInfo.getMetricsListener()`.
  - Declarative: `@WithMetrics(MyMetricsListener.class)` on controller interfaces (`ElementType.TYPE`) and individual methods (`ElementType.METHOD`), with repeatable `@WithMetrics.List`.
  - Automatic composition: client-level listener, class-level listener, and method-level listener are automatically merged via `ClientMetricsListener.composite(...)`.
- **Fail-Fast Upfront Validation (`ControllerValidator`):**
  - Rejects listener classes without accessible public no-arg constructors, interfaces, abstract classes, or types not implementing `ClientMetricsListener`.
- **Precise Timing and Byte Tracking:**
  - High-precision duration tracking (`System.nanoTime()`).
  - Wire request bytes computed from `byte[]` payload, string charset encoding, or `Content-Length` header on streaming bodies (`Path`, `File`).
  - Wire response bytes computed from raw received payload or decompressed bytes.
- **Full test coverage:** 18 new tests (+18 cases, total suite 767/767 across 89 classes) spanning:
  - `ClientMetricsValidationTest` (8 unit tests: default null metrics listener in ModuleInfo/SimpleModuleInfo/JRestlyClient, SimpleCallMetrics getters and immutability invariants, CompositeClientMetricsListener delegation, ClientMetricsListener.composite() flattening/null-filtering, ControllerValidator upfront rejection of abstract classes / non-listener classes / private constructor classes, upfront pass for valid @WithMetrics);
  - `ClientMetricsWireMockTest` (10 WireMock integration tests: synchronous GET call metrics and duration, POST call metrics with exact request/response bytes, retry metrics with multiple attempts and onRetry/onAttemptComplete verification, 500 server error metrics with wire response, handled exception with status code 422, in-flight gauge tracking via onCallStart/onCallComplete, asynchronous twin CompletableFuture call metrics, declarative @WithMetrics controller method, fault tolerance when listener throws RuntimeException, transport failure on dead port).

Gates (session #114, clean rebuild):
- `./gradlew test` **767/767** (89 classes, 0 failures/errors; +18 tests in ADR-0036).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — HTTP Proxy & Transport Authenticator Configuration — done, section below. ADR number **0037** — proxy configuration epic, **0038** free.

## HTTP Proxy & Transport Authenticator Configuration (ADR-0037) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, proxy routing, transport authenticator, test strategy, status):
> [`docs/0.10.0/ADR-0037-http-proxy-and-transport-authenticator.md`](docs/0.10.0/ADR-0037-http-proxy-and-transport-authenticator.md).
> Session #115 (agent-loop). Zero-dependency HTTP proxy routing and transport-level authentication configuration (essential for corporate enterprise networks and automated mock test harnesses).
> Introduces `ProxySelector` and `Authenticator` support across `ModuleInfo`, `SimpleModuleInfo`, `JRestlyClient.Builder`, and `AbstractHttpClient`.
> Provides ergonomic builder methods: `proxy(ProxySelector)`, `proxy(String host, int port)`, `noProxy()` (`HttpClient.Builder.NO_PROXY`),
> `authenticator(Authenticator)`, `proxyCredentials(String username, String password)`, and `credentials(String username, String password)`.
> Fully validates fail-fast inputs (nulls, blank hosts, invalid TCP ports 1..65535).
> 100% backward compatible and zero-dependency (Java 21 standard library only).

Key decisions (ADR-0037):
- **Zero-Dependency Standard Types:**
  - Uses standard Java 21 platform classes: `java.net.ProxySelector`, `java.net.Proxy`, `java.net.InetSocketAddress`, `java.net.Authenticator`, and `java.net.PasswordAuthentication` from `java.base`.
  - Zero external dependencies introduced.
- **Configuration SPI (`ModuleInfo`):**
  - Added default methods `default ProxySelector getProxySelector() { return null; }` and `default Authenticator getAuthenticator() { return null; }`.
  - When returning `null`, preserves default platform client behavior.
- **Builder Ergonomics (`SimpleModuleInfo.Builder` & `JRestlyClient.Builder`):**
  - `proxy(ProxySelector proxySelector)`: arbitrary proxy routing (e.g. PAC scripts, complex selectors).
  - `proxy(String host, int port)`: convenient host/port configuration with strict validation.
  - `noProxy()`: direct connection bypass for testing local mock servers without interference from system-wide proxies.
  - `authenticator(Authenticator)`: custom transport challenge handler.
  - `proxyCredentials(username, password)`: dedicated proxy challenge authenticator for `RequestorType.PROXY`.
  - `credentials(username, password)`: universal authenticator for both server and proxy challenges.
- **Transport Wiring (`AbstractHttpClient`):**
  - `createConnection()` wires `proxySelector` and `authenticator` directly into `HttpClient.Builder`.
  - Exposes `getProxySelector()` and `getAuthenticator()` getters.
- **Full test coverage:** 22 new tests (+22 cases, total suite 789/789 across 91 classes) spanning:
  - `ProxyConfigTest` (16 unit tests: default nulls, explicit proxy selector, host/port parsing, noProxy mapping to HttpClient.Builder.NO_PROXY, custom authenticator, proxy/universal credentials helpers, fail-fast on null selector/host/blank/port/authenticator/credentials, client getters);
  - `ProxyIntegrationWireMockTest` (6 WireMock integration tests: synchronous GET routed through local HTTP forward proxy, 407 Proxy Authentication Required challenge response with Proxy-Authorization header, invalid credentials fail-fast negative test, server 401 challenge with credentials, direct noProxy connection, async CompletableFuture twin over proxy).

Gates (session #115, clean rebuild):
- `./gradlew test` **789/789** (91 classes, 0 failures/errors; +22 tests in ADR-0037).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — HTTP/2 Protocol Negotiation & Response Inspection — done, section below. ADR number **0038** — HTTP/2 negotiation & response inspection epic, **0039** free.

## HTTP/2 Protocol Negotiation & Response Inspection (ADR-0038) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, protocol negotiation, trailers inspection, test strategy, status):
> [`docs/0.10.0/ADR-0038-http2-protocol-negotiation-and-response-inspection.md`](docs/0.10.0/ADR-0038-http2-protocol-negotiation-and-response-inspection.md).
> Session #116 (agent-loop). End-to-end negotiated HTTP protocol version propagation, response metadata inspection, and RFC 7230 / RFC 9110 trailer header parsing.
> Introduces `version` and `trailers` across `WireResponse`, `ResponseHeaders`, and `CallMetrics`.
> Refactors `HttpTemplateBuilder.getHttpVersion()` to support full interface hierarchy resolution (Method > Sub-interface > Super-interface > Client default).
> 100% backward compatible and zero-dependency (Java 21 standard library only).

Key decisions (ADR-0038):
- **Wire Model Protocol & Trailers Propagation (`WireResponse`):**
  - Extended canonical record: `WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream, HttpClient.Version version, List<Header> trailers)`.
  - Maintained full backwards compatibility with 3-arg, 4-arg, and 5-arg constructors with defensive normalization (`null` version defaults to `HttpClient.Version.HTTP_1_1`, `null` trailers default to empty list).
- **Transport Capture, Decompression & Logging (`HttpTemplate`):**
  - In `toWireResponse(HttpResponse<?> response)`: captures negotiated protocol version `response.version()`.
  - In `handleDecompression(WireResponse response)`: preserves `response.version()` and `response.trailers()` across transparent GZIP/Deflate stream and byte decompression.
  - In `logResponse(WireResponse response, long elapsedMs)`: logs negotiated protocol version (`HTTP/2` or `HTTP/1.1`) along with status code and round-trip duration.
  - In `processResponse(...)`: forwards version and trailers to `responseHeadersHolder` and active `CURRENT_CAPTOR`.
- **Interface Hierarchy Precedence for `@HttpVersion` (`HttpTemplateBuilder`):**
  - Scans interface hierarchy using `collectInterfaceHierarchy(controller)` bottom-up: method annotation overrides sub-interface, sub-interface overrides super-interface, super-interface applies before falling back to client default.
- **Call-Site Metadata Access (`ResponseHeaders`):**
  - Added accessors: `HttpClient.Version version()`, `boolean isHttp2()`, `boolean isHttp1()`.
  - Added trailing headers support: `List<Header> trailers()`, `Optional<String> firstTrailer(String name)`, `List<String> trailerValues(String name)`.
  - Added RFC 7230 / RFC 9110 declared trailer parser: `List<String> declaredTrailers()` extracts trailer field names declared in the `Trailer` header.
  - Forwarded version and trailers through `ResponseHeaders.capture(...)` and `ResponseHeaders.captureCallable(...)`.
- **Telemetry & Observability SPI (`CallMetrics`):**
  - Added non-breaking default method `HttpClient.Version version()` returning the negotiated protocol version (or `HTTP_1_1` fallback).
- **Full test coverage:** 21 new tests (+21 cases, total suite 810/810 across 94 classes) spanning:
  - `HttpVersionHierarchyTest` (6 unit tests: unannotated fallback, direct interface inheritance, method override of direct interface, sub-interface inheritance, sub-interface override of super-interface, method override of sub-interface);
  - `ResponseHeadersHttp2Test` (8 unit tests: unpopulated defaults, HTTP/2 populated flags, case-insensitive trailer lookups, declaredTrailers parsing of single/multiple/empty Trailer headers, capture pattern forwarding, WireResponse constructors and immutability, CallMetrics version inspection and fallback);
  - `Http2NegotiationWireMockTest` (7 WireMock integration tests: method parameter receiving negotiated HTTP/2 and pinned HTTP/1.1 from live server, server response with Trailer header parsing declared trailers, capture pattern receiving HTTP/2, ClientMetricsListener receiving negotiated HTTP/2, GZIP decompression preserving negotiated HTTP/2, async CompletableFuture pipeline preserving version, failed 500 response preserving version in exception metadata).

Gates (session #116, clean rebuild):
- `./gradlew test` **810/810** (94 classes, 0 failures/errors; +21 tests in ADR-0038).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Cookie Store Persistence SPI (CookieHandler state persistence) — done, section below. ADR number **0039** — cookie persistence epic, **0040** free.

## Cookie Store Persistence & Management SPI (ADR-0039) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, persistence SPI, file store, test strategy, status):
> [`docs/0.10.0/ADR-0039-cookie-store-persistence-spi.md`](docs/0.10.0/ADR-0039-cookie-store-persistence-spi.md).
> Session #117 (agent-loop). Pluggable zero-dependency cookie persistence SPI, file-backed storage, and client builder ergonomics.
> Introduces `CookiePersister` SPI (`loadAll`, `saveAll`, `clear`),
> `FileCookiePersister` for atomic RFC 6265 file persistence,
> `CookieCodec` for RFC 6265 streaming serialization/parsing,
> `PersistentCookieStore` implementing `java.net.CookieStore` with `AUTO` and `MANUAL` persistence modes,
> and fluent builder methods `cookieStore(CookieStore)`, `cookieStore(CookieStore, CookiePolicy)`, and `persistentCookieStore(Path)` in `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`.
> 100% backward compatible and zero-dependency (Java 21 standard library only).

Key decisions (ADR-0039):
- **Cookie Persistence SPI (`ru.jrestly.cookie.CookiePersister`):**
  - Pluggable interface providing `loadAll()`, `saveAll(Collection<HttpCookie>)`, and `clear()`.
  - Enables custom storage backends (files, Redis, databases, SharedPreferences, vaults) without external dependencies.
- **Atomic File-Backed Persister (`ru.jrestly.cookie.FileCookiePersister`):**
  - Reads and writes RFC 6265 formatted cookie entries with comments and timestamp headers.
  - Automatically filters expired cookies (`!cookie.hasExpired()`).
  - Uses atomic file replacement (`StandardCopyOption.ATOMIC_MOVE` with fallback) and UTF-8 encoding to prevent data corruption during unexpected process termination.
- **Serialization & Codec Utility (`ru.jrestly.cookie.CookieCodec`):**
  - Serializes `HttpCookie` instances to compliant RFC 6265 lines (`name=value; Domain=...; Path=...; Max-Age=...; Secure; HttpOnly`).
  - Streams cookies to/from any `Reader` / `Writer`.
- **Thread-Safe Persistent Cookie Store (`ru.jrestly.cookie.PersistentCookieStore`):**
  - Implements standard `java.net.CookieStore`.
  - Wraps an underlying delegate `CookieStore` (defaults to standard JDK store).
  - Pre-loads cookies upon creation via `persister.loadAll()`.
  - Maps domain and host aliases (including bare host mapping for `.local` / localhost domains).
  - Supports `PersistenceMode.AUTO` (immediate mutation sync) and `PersistenceMode.MANUAL` (explicit `sync()` / `persist()`).
- **Builder Ergonomics (`SimpleModuleInfo.Builder` & `JRestlyClient.Builder`):**
  - `cookieStore(CookieStore store)`: configures `CookieManager` with the store and default `CookiePolicy.ACCEPT_ORIGINAL_SERVER`.
  - `cookieStore(CookieStore store, CookiePolicy policy)`: configures `CookieManager` with the store and given policy.
  - `persistentCookieStore(Path path)`: one-line shortcut configuring a file-backed persistent store.
- **Full test coverage:** 18 new tests (+18 cases, total suite 828/828 across 96 classes) spanning:
  - `CookiePersistenceUnitTest` (13 unit tests: CookieCodec full attributes formatting, round-trip serialization/deserialization, parse filtering comments/blank/expired, write/read null safety, FileCookiePersister atomic save/load/clear, persister validations, PersistentCookieStore AUTO mode, MANUAL mode with deferred sync, preloading & reloading, builder configuration & validations, concurrent thread-safety under multi-threaded mutations, SimpleModuleInfo & JRestlyClient builders, builder null validations);
  - `PersistentCookieWireMockTest` (5 WireMock integration tests: process restart simulation across separate client instances, logout cookie expiration & storage cleanup, multiple cookies combined with declarative @Cookie annotations, async CompletableFuture twin, MANUAL mode deferred file write until explicit sync).

Gates (session #117, clean rebuild):
- `./gradlew test` **828/828** (96 classes, 0 failures/errors; +18 tests in ADR-0039).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Retry-After exponential backoff & jitter policy (ADR-0040) — done, section below. ADR number **0040** — retry jitter epic, **0041** free.

## Advanced Exponential Backoff, Jitter Strategies & Retry-After Bounds (ADR-0040) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, jitter strategies, bounds math, test strategy, status):
> [`docs/0.10.0/ADR-0040-exponential-backoff-jitter-and-retry-after-bounds.md`](docs/0.10.0/ADR-0040-exponential-backoff-jitter-and-retry-after-bounds.md).
> Session #118 (agent-loop). Advanced jitter strategies (FULL, EQUAL, DECORRELATED, NONE per AWS Architecture recommendations),
> configurable exponential backoff multiplier, and Retry-After bounding & opt-out controls.
> Introduces `ru.jrestly.http.Jitter` enum (`FULL`, `EQUAL`, `DECORRELATED`, `NONE`),
> expands `RetryPolicy` and `RetryPolicy.Builder` with `jitter(Jitter)`, `backoffMultiplier(double)`, `respectRetryAfter(boolean)`, and `maxRetryAfter(Duration)`,
> updates `@Retry` annotation with `jitter`, `multiplier`, `respectRetryAfter`, and `maxRetryAfterMs`,
> and integrates `previousDelayMillis` propagation across synchronous loop and asynchronous `CompletableFuture` pipelines in `HttpTemplate`.
> 100% backward compatible and zero-dependency (Java 21 standard library only).

Key decisions (ADR-0040):
- **Jitter Strategy Enum (`ru.jrestly.http.Jitter`):**
  - `FULL` (default, backward-compatible): uniform random draw in `[0, bound]`, minimizing total competing load.
  - `EQUAL`: guarantees 50% deterministic bound floor with 50% random jitter (`bound / 2 + random[0, bound / 2]`), preventing too-early retries while avoiding thundering herds.
  - `DECORRELATED`: adaptive sleep scaling from previous delay (`min(maxBackoff, random[initialBackoff, previousSleep * 3])`), breaking attempt-number lockstep.
  - `NONE`: strictly deterministic `bound` without randomness (ideal for deterministic tests, background queues, and batch jobs).
- **Configurable Backoff Multiplier:**
  - `backoffMultiplier` (default `2.0`, validated `>= 1.0` and not NaN) enables smooth (e.g. 1.5) or aggressive (e.g. 3.0) exponential backoff curves.
- **Retry-After Bounding & Opt-Out:**
  - `respectRetryAfter` (default `true`) allows opting out of server-provided `Retry-After` headers when behind misconfigured or untrusted reverse proxies.
  - `maxRetryAfter` (default `null`, uncapped by default to preserve ADR-0008 wall-clock semantics) allows capping excessively long server delays (e.g. 24 hours) to prevent thread/resource exhaustion.
- **Pipeline Integration (`HttpTemplate`):**
  - Sync retry loop tracks `lastDelayMillis` and forwards it to `nextDelayMillis`.
  - Async `attemptAsync` pipeline threads `previousDelayMillis` across `delayedExecutor` schedules.
- **Full test coverage:** 15 new tests (+15 cases, total suite 843/843 across 98 classes) spanning:
  - `RetryPolicyJitterTest` (10 unit tests: defaults reflection, builder configuration, builder validation for multiplier/maxRetryAfter/jitter, Jitter.NONE deterministic math, Jitter.EQUAL bound checks, Jitter.DECORRELATED adaptive scaling and bounds, backoffMultiplier custom curve, respectRetryAfter opt-out, maxRetryAfter cap, toString representation);
  - `RetryJitterWireMockTest` (5 WireMock integration tests: declarative @Retry with Jitter.NONE 503 -> 503 -> 200, Jitter.EQUAL recovery, respectRetryAfter = false ignoring server 10s delay, maxRetryAfterMs capping 1-hour delay to 40 ms, async CompletableFuture twin with Jitter.DECORRELATED).

Gates (session #118, clean rebuild):
- `./gradlew test` **843/843** (98 classes, 0 failures/errors; +15 tests in ADR-0040).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Request & Response Compression Negotiation SPI (ADR-0041) — done, section below. ADR number **0041** — request compression epic, **0042** free.

## Request Body Compression & Content-Encoding SPI (ADR-0041) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, SPI design, threshold controls, test strategy, status):
> [`docs/0.10.0/ADR-0041-request-compression-and-content-encoding-spi.md`](docs/0.10.0/ADR-0041-request-compression-and-content-encoding-spi.md).
> Session #119 (agent-loop). Request body compression and pluggable content coding SPI for high-throughput REST APIs,
> telemetry/metrics ingestion, and large POST/PUT/PATCH mutations.
> Introduces `ru.jrestly.compression` package (`CompressionAlgorithm`, `ContentCompressor` SPI, `GzipContentCompressor`, `DeflateContentCompressor`, `ContentCompressors` registry),
> `@Compress` declarative annotation (`ru.jrestly.annotation.Compress`) with `algorithm`, `minSizeBytes` threshold, and `enabled` controls,
> client/module builder ergonomics (`requestCompression(algorithm, thresholdBytes)`, `contentCompressor(compressor)`),
> upfront controller validation (`ControllerValidator` rejects negative thresholds and invalid settings),
> and transparent pipeline compression in `HttpTemplateBuilder` + symmetric custom decompression in `HttpTemplate`.
> 100% backward compatible and zero-dependency (Java 21 standard library `java.util.zip` only).

Key decisions (ADR-0041):
- **Content Coding SPI (`ru.jrestly.compression`):**
  - `CompressionAlgorithm` enum: `GZIP` (`"gzip"`), `DEFLATE` (`"deflate"`), `NONE` (`"identity"`), with case-insensitive token resolution and alias mapping.
  - `ContentCompressor` SPI: clean `compress(byte[] uncompressed)` and optional `decompress(byte[] compressed)` interface.
  - Zero-dep implementations: `GzipContentCompressor` (singleton, `GZIPOutputStream`/`GZIPInputStream`) and `DeflateContentCompressor` (singleton, RFC 1950 zlib and RFC 1951 raw deflate fallback).
  - Built-in registry `ContentCompressors` for fast resolution and custom extensions.
- **Declarative Ergonomics (`@Compress`):**
  - Targets `METHOD` and `TYPE` (interfaces): supports interface-level compression inherited across mutating methods, with per-method overrides (`@Compress(enabled = false)` or custom algorithms/thresholds).
  - `minSizeBytes`: configurable threshold avoiding compression overhead for small payloads.
- **Module and Client Builders (`SimpleModuleInfo.Builder`, `JRestlyClient.Builder`):**
  - `requestCompression(CompressionAlgorithm)` & `requestCompression(CompressionAlgorithm, int thresholdBytes)`.
  - `contentCompressor(ContentCompressor)` for registering custom codecs.
- **Header Hygiene & Double Compression Prevention:**
  - Caller-provided explicit `Content-Encoding` headers are honored without secondary compression.
- **Full test coverage:** 23 new tests (+23 cases, total suite 866/866 across 102 classes) spanning:
  - `CompressionAlgorithmTest` (2 unit tests: standard HTTP tokens, case-insensitive and alias resolution);
  - `ContentCompressorTest` (6 unit tests: GZIP roundtrip and edge cases, Deflate roundtrip and edge cases, registry lookups, default decompress exceptions);
  - `CompressConfigTest` (7 unit tests: ModuleInfo defaults, SimpleModuleInfo builder, custom compressor registration, client builder, ControllerValidator rules, direct HttpTemplateBuilder byte compression verification);
  - `RequestCompressionWireMockTest` (8 WireMock integration tests: declarative GZIP POST with JSON DTO, declarative DEFLATE PUT, minSizeBytes threshold filtering, interface inheritance and method opt-out, client-level builder setting, manual header avoidance, async CompletableFuture twin, custom ContentCompressor SPI).

Gates (session #119, clean rebuild):
- `./gradlew test` **866/866** (102 classes, 0 failures/errors; +23 tests in ADR-0041).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Request Tracing & Correlation ID Propagation SPI (ADR-0042) — done, section below. ADR number **0042** — request tracing epic, **0043** free.

## Request Tracing & Correlation ID Propagation SPI (ADR-0042) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, W3C TraceContext model, SPI design, test strategy, status):
> [`docs/0.10.0/ADR-0042-request-tracing-and-correlation-id-propagation-spi.md`](docs/0.10.0/ADR-0042-request-tracing-and-correlation-id-propagation-spi.md).
> Session #120 (agent-loop). Zero-dependency distributed request tracing, correlation ID propagation, and W3C TraceContext Level 1 support.
> Introduces `ru.jrestly.tracing` package (`TraceContext` record with W3C parsing and random/child factories, `TraceIdGenerator` & `TraceIdGenerators`, `TraceRequestContext`, `TracePropagator` SPI, and `TracePropagators` factory),
> `@Trace` declarative annotation (`ru.jrestly.annotation.Trace`) on methods and interfaces with `header`, `w3c`, `enabled`, and `forceNew` attributes,
> client and module builder ergonomics (`tracePropagator`, `correlationId(name)`, `correlationId(name, generator)`, `correlationId(ThreadLocal)`, `w3cTracing()`),
> validation in `ControllerValidator` (rejects blank header names in `@Trace`),
> transparent injection pipeline in `HttpTemplateBuilder` respecting explicitly passed headers,
> and metadata extraction accessors in `ResponseHeaders` (`requestId()`, `traceparent()`, `traceContext()`).
> 100% backward compatible and zero-dependency (Java 21 standard library only).

Key decisions (ADR-0042):
- **W3C TraceContext Level 1 (`ru.jrestly.tracing.TraceContext`):**
  - Immutable record `TraceContext(traceId, spanId, traceFlags, traceState)` validating 32-hex trace ID, 16-hex span ID, 2-hex flags, and non-zero invariants.
  - Standard formatting `toTraceparent()`: `00-${traceId}-${spanId}-${traceFlags}`.
  - Safe parsing `parse(traceparent)` with full validation against malformed or all-zero headers.
  - `child()` factory generating fresh child span IDs while preserving trace ID and trace state.
- **Trace ID Generators (`ru.jrestly.tracing.TraceIdGenerators`):**
  - `uuid()`: standard UUID v4.
  - `hex(byteLength)`: random lowercase hex strings via `java.util.HexFormat`.
  - `prefixed(prefix, delegate)`: custom prefixes for audit logs.
  - `threadLocal(holder, fallback)`: dynamic extraction from active thread context.
- **Trace Context Propagation SPI (`ru.jrestly.tracing.TracePropagator`):**
  - `inject(TraceRequestContext context)`: context-aware header injection returning `Map<String, String>`.
  - Built-in propagators: `correlationId()`, `w3c()`, `threadLocal()`, and `composite()`.
- **Declarative & Fluent Ergonomics:**
  - `@Trace` annotation: interface inheritance, method overrides (`enabled = false`), custom headers, and `w3c = true`.
  - Client/module builders: `correlationId(...)`, `tracePropagator(...)`, `w3cTracing(...)`.
  - Header preservation: user-supplied `@RequestHeader("X-Request-ID")` is never overwritten unless explicitly requested via `forceNew = true`.
- **Response Metadata Ergonomics (`ResponseHeaders`):**
  - `headers.requestId()` (resolves `X-Request-ID` or `X-Correlation-ID`), `headers.traceparent()`, and `headers.traceContext()`.
- **Full test coverage:** 28 new tests (+28 cases, total suite 894/894 across 106 classes) spanning:
  - `TraceContextTest` (6 unit tests: valid normalization, invalid/all-zero validation, random generation, child span derivation, valid W3C parsing, malformed rejection);
  - `TracePropagatorTest` (7 unit tests: TraceIdGenerators, TraceRequestContext header detection, correlationId propagation, w3c traceparent, w3c supplier + tracestate, threadLocal propagation, composite combining);
  - `TraceConfigTest` (4 unit tests: ModuleInfo defaults, SimpleModuleInfo builder, JRestlyClient builder, ControllerValidator blank header checks);
  - `RequestTracingWireMockTest` (11 WireMock integration tests: interface-level default UUID, method-level custom header, method-level W3C traceparent, method opt-out, explicit @RequestHeader preservation, forceNew overwrite, client builder correlationId, client builder ThreadLocal dynamic propagation, client builder w3cTracing, ResponseHeaders extraction, async CompletableFuture twin).

Gates (session #120, clean rebuild):
- `./gradlew test` **894/894** (106 classes, 0 failures/errors; +28 tests in ADR-0042).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — Circuit Breaker & Client Rate Limiter SPI (ADR-0043) — done, section below. ADR number **0043** — resilience epic, **0044** free.

## Circuit Breaker & Client Rate Limiter SPI (ADR-0043) — COMPLETE ✅ (2026-10-15, unreleased)

> Source of truth (concept, architecture decisions, resilience model, Token Bucket algorithm, SPI design, test strategy, status):
> [`docs/0.10.0/ADR-0043-circuit-breaker-and-client-rate-limiter-spi.md`](docs/0.10.0/ADR-0043-circuit-breaker-and-client-rate-limiter-spi.md).
> Session #121 (agent-loop). Zero-dependency client-side fault tolerance: cascading failure protection via Circuit Breaker and request throughput control via Rate Limiter.
> Introduces `ru.jrestly.resilience` package (`CircuitBreaker` interface with `CLOSED`, `OPEN`, `HALF_OPEN` states, `CircuitBreakerConfig`, `CircuitBreakerMetrics`, `CircuitBreakers` factory, `CircuitBreakerRegistry`, `CircuitBreakerOpenException`, `RateLimiter` interface, `RateLimiterConfig`, `RateLimiterMetrics`, `RateLimiters` token-bucket factory, `RateLimiterRegistry`, `RateLimitExceededException`),
> `@CircuitBreaker` and `@RateLimiter` declarative annotations (`ru.jrestly.annotation`) on methods and interfaces with full threshold, timeout, and opt-out controls,
> client and module builder ergonomics (`circuitBreaker`, `circuitBreakerRegistry`, `rateLimiter`, `rateLimiterRegistry`),
> validation in `ControllerValidator` (rejects blank names, non-positive thresholds, invalid sliding window sizes, negative wait/timeout durations),
> transparent integration in `HttpTemplateBuilder` (prioritizing Method > Sub-interface > Super-interface > Client with per-module isolated registries by default),
> fast-fail execution guarding in `HttpTemplate` (both synchronous `exchange()` and asynchronous `exchangeAsync()` reject calls before network dispatch when circuit is open or limit exceeded, while recording outcomes and keeping 4xx client errors healthy).
> 100% backward compatible and zero-dependency (Java 21 standard library concurrency only).

Key decisions (ADR-0043):
- **Zero-Dep Circuit Breaker (`ru.jrestly.resilience.CircuitBreaker`):**
  - Three-state finite state machine: `CLOSED`, `OPEN`, and `HALF_OPEN`.
  - Configurable sliding window with `minimumNumberOfCalls`, `slidingWindowSize`, `failureRateThreshold` (0..100%), and `waitDurationInOpenState`.
  - Half-open trial phase with `permittedNumberOfCallsInHalfOpenState`: successful trial calls reset to `CLOSED`, single failure trips immediately back to `OPEN`.
  - Default failure classification: network errors (`IOException`, `TimeoutException`) and server errors (HTTP 5xx, `ServerErrorException`, `UnexpectedStatusException >= 500`) are failures; client errors (HTTP 4xx, `ClientErrorException`) are normal server responses and do not trip the circuit breaker.
  - Fail-fast rejection throwing `CircuitBreakerOpenException` containing circuit name and `remainingWaitDuration`.
- **Zero-Dep Client-Side Rate Limiter (`ru.jrestly.resilience.RateLimiter`):**
  - Thread-safe Token Bucket algorithm running on monotonic nanosecond clock (`System.nanoTime()`).
  - Configurable `limitForPeriod`, `limitRefreshPeriod`, and `timeoutDuration` (default `Duration.ZERO` for non-blocking fail-fast).
  - Rejection throws `RateLimitExceededException` without network roundtrip.
- **Declarative & Fluent Ergonomics:**
  - `@CircuitBreaker` and `@RateLimiter` annotations on interfaces and methods with `enabled = false` method-level opt-out.
  - Fluent builders in `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`.
  - Registries (`CircuitBreakerRegistry`, `RateLimiterRegistry`) isolated per-module/client instance by default, preventing state leakage across test cases while supporting explicit cross-client sharing.
- **Full test coverage:** 30 new tests (+30 cases, total suite 924/924 across 111 classes) spanning:
  - `CircuitBreakerTest` (8 unit tests: initial closed state, minimumNumberOfCalls threshold guard, sliding window failure rate trip, half-open recovery, half-open fail-fast reopening, client 4xx safety, default predicate classification, manual transitions, registry caching);
  - `RateLimiterTest` (4 unit tests: token bucket capacity and exhaustion, time-based refill, timeout-based waiting, registry caching);
  - `ResilienceConfigTest` (6 unit tests: ModuleInfo defaults, SimpleModuleInfo defaults, client builder wiring, ControllerValidator valid rules, blank name rejection, invalid bounds rejection);
  - `CircuitBreakerWireMockTest` (6 WireMock integration tests: 500 server error trip & network request bypass, 404 client error tolerance, wait duration recovery to 200 OK, method-level opt-out bypass, async CompletableFuture exceptional completion, client-level circuit breaker);
  - `RateLimiterWireMockTest` (5 WireMock integration tests: throughput enforcement & network bypass, timeout token refill wait, method opt-out, async exceptional completion, client-level rate limiter).

Gates (session #121, clean rebuild):
- `./gradlew test` **924/924** (111 classes, 0 failures/errors; +30 tests in ADR-0043).
- `compileJava` and `compileTestJava` 0 warnings under permanent `-Xlint:all -Werror`.
- `javadoc` 0 warnings under permanent `-Xdoclint:all -Werror`.
- Zero external runtime dependencies preserved (0 dependencies in Maven POM).

After the epic: candidate #1 — test-javadoc (ADR-0013 Non-goals); candidate #2 — next roadmap epic. ADR number **0044** free.

## Post-0.5.0 shortlist (not scheduled — see ADR-0003)

- ~~Retry with backoff (integrates with `@OnError` semantics)~~ — done 2026-10-13, section above
- ~~`CompletableFuture` return type~~ — done 2026-10-12, section above
- ~~Gson / Moshi codec adapters (classpath probe chain extension)~~ — done 2026-10-13, section above

Carry-over decision (maintainer, 2026-10-12): `JacksonCodec` serializer output
should also learn ISO-8601 `T`. Resolved as option A (configurable,
non-breaking) — done, section above.
