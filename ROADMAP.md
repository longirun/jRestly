# jRestly Roadmap

The project's architectural execution log. Active release cycles live here with verification gates and steps; completed cycles are preserved below. The unassigned backlog of feature candidates lives in [TODO.md](TODO.md).

## Engineering Method: Atomic Porting & Controlled Convergence

All architectural features are ported from the research branch `jrestly-infinite` (`ba68f1f`) into `main` according to an atomic migration protocol:

1. **Reference Target:** `jrestly-infinite` (`ba68f1f`) serves as the architectural reference and code quarry, not an immutable dogma.
2. **Controlled Divergence Permitted:** If defects, API ergonomics flaws, or optimization opportunities are identified during review, they should be fixed directly in `main`. Intentional deviations from `infinite` are documented in the corresponding ADR.
3. **Atomic Commits (`1 feature = 1 commit`):** Rather than replicating the historical ~380 exploratory commits of `infinite`, each feature lands as an atomic, bisectable commit (`feat(<scope>): ... (ADR-xxxx)`) accompanied by its tests and ADR.
4. **Extraction from Canonical Target:**
   - **New Files (classes, submodules, tests):** Checked out directly from `infinite`'s final state (`git checkout infinite -- <path>`) as the starting baseline.
   - **Shared Files (`HttpTemplate`, `HttpTemplateBuilder`, etc.):** Isolated hunks belonging to the active feature are extracted from `git diff main..infinite` to prevent accidental formatting drift.
5. **Topological Order:** Features are ported strictly along their dependency graph as defined in [TODO.md](TODO.md), guaranteeing a green test suite (`./gradlew test`) at every single commit.

---

## 0.6.1 — Quality & Core Polish (In Progress)

Source of truth: [`docs/0.6/ADR-0006-quality-and-core-polish.md`](docs/0.6/ADR-0006-quality-and-core-polish.md).

Scope: 3 high-impact, zero-risk developer experience improvements ported from `jrestly-infinite` Tier 1:
1. `JacksonCodec` flexible `LocalDateTime` support (lenient ISO-8601 & fractional seconds).
2. 0.5.0 quirk fixes (PUT request body logging & primitive `void.class` return handling).
3. Nullable `@RequestParam` and `@RequestHeader` graceful omission.

Gates for every step: full WireMock test suite green (`./gradlew test`), IDE inspections clean, generated POM has zero runtime `<dependency>`.

### Steps:
- [ ] Step 1 — `LocalDateTime` lenient parsing & ISO-8601 wire format option (`JacksonCodec`)
- [ ] Step 2 — 0.5.0 quirk fixes (PUT logging gate, `void.class` handling in `HttpTemplate`)
- [ ] Step 3 — Nullable `@RequestParam` / `@RequestHeader` omission in query and headers
- [ ] Step 4 — Release 0.6.1 finalize (bump version in `build.gradle`, tag `v0.6.1`, update README coordinates)

---

## 0.5.0 — zero-dependency core

Plan derived from ADR-0001..0004 (`docs/0.5/`). The 0.4.0 improvement plan is complete; its history lives in git.

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

Known carry-over quirks (accepted, non-blocking): `logRequest` body logging skips PUT (POST/PATCH/DELETE only); `Void.class.equals(returnType)` instead of also checking `void.class` (masked by the empty-payload early return). Post-0.5.0 shortlist unchanged (ADR-0003).

## Cycle 0.5.2 — HTTP version pinning — COMPLETE ✅ (2026-09-01)

> Documented retroactively (2026-10): the cycle ran through the usual developer/tester separation but was never recorded here. Client-wide default `httpVersion(HttpClient.Version)` on `JRestlyClient.Builder` (delegates to `SimpleModuleInfo.Builder`, `Objects.requireNonNull` fail-fast; `ModuleInfo.getHttpVersion()` defaults to `HTTP_2`), applied on `HttpClient.newBuilder().version(...)` in `AbstractHttpClient`. Per-method override `@HttpVersion(...)` resolves in `HttpTemplateBuilder` into `HttpTemplate` and is applied per-request via `builder.version(...)` when present — a method without the annotation inherits the client-level version. Commit `35f80e9`, tag `v0.5.2`. Tests: `HttpVersionConfigTest` (builder/ModuleInfo contract, NPE fail-fast, defaults), `HttpTemplateVersionResolveTest` (annotation → template resolution), `HttpVersionPinningTest` (WireMock pinning + `@HttpVersion` + redirect-with-version).

## Cycle 0.6.0 — multipart authoring & streaming — COMPLETE ✅ (2026-10-11)

> Source of truth (decisions, stages, verification gates, deviations, status): [`docs/0.6/ADR-0005-multipart-authoring-streaming.md`](docs/0.6/ADR-0005-multipart-authoring-streaming.md).

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

## Future Backlog

Feature quarry and candidates for subsequent releases live in [TODO.md](TODO.md).
