# ADR-0075: Header-Merge Invariants, Live Trace Views & Response-Map Fast-Paths

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 Candidate №14 — Header Merge, W3C Tracing & `ResponseHeaders` Allocation Pruning

---

## 1. Context & Motivation

**ADR-0055** (Zero-Garbage Dispatch Pipeline) and **ADR-0066** (`HttpTemplate` Decomposition) pruned the dispatch pipeline, but three request-scoped zones still allocated intermediate objects on *every* call, even in the most common shapes:

1. **Header merge in `HttpTemplateBuilder.createHeaders`:** whenever both ambient (`AuthProvider`) and static (`@RequestHeader`/interface-level) headers were present, the merge branch built a transient `HashSet<String>` of lowercased ambient names plus a lowercased `String` per ambient header just to detect name collisions. The dynamic-override path then used `removeIf(h -> ...)` and `stream().anyMatch(...)` — a lambda `Predicate`, a `Stream`, and a pipeline stage allocated per dynamic header, per request.

2. **Ambient trace propagation in `applyTracePropagator`:** the live merged header list was wrapped in `Collections.unmodifiableList(...)` before being handed to the `TraceRequestContext` — whose compact constructor already applies the single unmodifiable view the propagator contract requires (ADR-0042 §6.4). One pure wrapper object per ambient-trace request, with no behavioral role.

3. **`ResponseHeaders` map assembly:** two inline copies of the header/trailer name→values assembly (headers in `doPopulate`, trailers in `doPopulate`/`populateTrailers`). The *empty* trailer case — the default for every non-HTTP/2 response — still allocated an empty `TreeMap` plus an unmodifiable wrapper, and *single-value* entries (the overwhelmingly common response-header shape) allocated a backing `ArrayList` plus a second unmodifiable-list layer.

The ADR-0072 fast-path discipline (single-pass assembly, shape-driven allocation) is applied here to the remaining zones.

---

## 2. Key Decisions & Architecture

### Decision 1: Hash-Set-Free Header Merge with a Fixed Order Invariant

The merge branch of `createHeaders` no longer materializes a name set. Collision detection is a direct case-insensitive scan over the two small lists (`equalsIgnoreCase`), with **no lowercased intermediate strings**:

- Complexity `O(a·s)` — ambient count × static count. This is deliberate: ambient auth headers are typically 1–3 (`Authorization`, a couple of tenant/role markers) and static headers are the declared count, so the short-string comparison count stays far below the cost of allocating a `HashSet` plus one `toLowerCase(Locale.ROOT)` string per ambient header and hashing it. The crossover (an ambient provider returning hundreds of headers) is a documented non-goal — the ambient seam is the `AuthProvider` contract, whose typical shape the scan is tuned for.
- **Order invariant** (structurally pinned by test): surviving ambient headers keep their *original positions* → static headers in declaration order → dynamic headers in parameter order → the default `Accept-Encoding: gzip, deflate` appended last (decompression is ON by default via `SimpleModuleInfo`). A static declaration wins over the ambient header with the same name (case-insensitive): the ambient copy disappears, the static survives.

### Decision 2: Null-Tolerant In-Place Name Operations

Two package-private static helpers replace the per-dynamic-header lambda/stream allocations:

- `removeHeaderByName(List<Header>, String)` — reverse in-place scan, so removals are index-safe in a single pass;
- `hasHeaderNamed(List<Header>, String)` — plain forward scan (also serves the `Accept-Encoding` pre-check and the `Content-Encoding` compression pre-check).

Both **skip headers with a `null` name** instead of failing: `AuthProvider.getAuthHeaders()` is a public seam, so a third-party provider can emit a nameless header; the null-tolerance downgrades that data-quality issue from a hot-path NPE to a non-match. This replaces `removeIf(h -> h.name().equalsIgnoreCase(name))` (a `Predicate` per call) and `stream().anyMatch(...)` (a `Stream` + stage + predicate per call).

### Decision 3: Single Unmodifiable Wrap for the Ambient Trace View (Live Semantics)

`applyTracePropagator` passes the live merged list to the `TraceRequestContext` as is. The **only** unmodifiable wrap is now the record's compact constructor; the external `Collections.unmodifiableList` layer was removed.

The live-view semantics are preserved and deliberately *not* changed (ADR-0042 §6.4): the propagator observes the **live** merge list, not a snapshot copy — a header the propagator injects itself is visible through the context view it receives. This is "view of the live list" semantics, structurally pinned: the view is exactly one `Collections$Unmodifiable*` layer whose backing list is the live merge `ArrayList` (a second wrapper is detectable — and fails — by reflection on the backing field).

### Decision 4: Shape-Driven Response Map Assembly (`ResponseHeaders.buildValueMap`)

The two inline copies of the name→values assembly collapsed into one static helper, `buildValueMap(List<Header>)`, shared by `doPopulate` (headers **and** trailers) and `populateTrailers` (ADR-0059 late trailers). Allocation is driven by the *shape* of the input:

| Shape | Result |
|---|---|
| no headers, or only nameless headers | the shared `Collections.emptyMap()` **singleton** — zero objects (previously: empty `TreeMap` + wrapper) |
| single-value entry | compact `List.of(value)` — no backing `ArrayList`, no wrapper layer (previously: both) |
| multi-value entry | `Collections.unmodifiableList` over the accumulated list |
| map itself | `TreeMap(String.CASE_INSENSITIVE_ORDER)` + one `Collections.unmodifiableMap` — the case-insensitive lookup contract is byte-for-byte unchanged |

Nameless headers are indexed the same way as before (skipped in the map, kept in `rawHeaders`/`trailers`), so `headers()`/`trailers()` observability is untouched.

### Known Gaps & Non-Goals

- The `O(a·s)` merge scan is tuned for small ambient/static header lists (Decision 1). A pathological ambient provider (hundreds of headers) would favor a hash set; the `AuthProvider` seam documents the typical shape.
- Multi-value response entries still accumulate into a per-name `ArrayList` before being wrapped — the *final shape* is compact, accumulation is not (unavoidable without a two-pass assembly, which would re-scan the list).
- `TreeMap` key ordering (case-insensitive lexicographic) in `map()`/`trailerMap` is observable behavior and preserved; the empty-singleton shape is *not* order-observable (the map is empty).
- The structural tests reflect into the JDK's private `Collections$UnmodifiableList.list` field, which requires `--add-opens java.base/java.util=ALL-UNNAMED` on the **root test JVM** (rationale commented in the root `build.gradle`) — a test-time opens, not a core production dependency.

---

## 3. Compatibility & Verification

- **100% behavioral parity:** the merge-order invariant, the case-insensitive dynamic override (new `DupHeaderApi` fixture: a differently-cased dynamic name replaces the static), and the trace live-view semantics are pinned structurally — not only by behavior.
- **Five red-then-green structural tests** (TDD; all failed before the refactor, all pass after):
  - `HttpTemplateBuilderTest` — "Header merge order: surviving ambient auth first, then statics, then dynamics (Candidate #14 parity)"
  - `HttpTemplateBuilderTest` — "Dynamic header replaces a static one case-insensitively (Candidate #14 parity)"
  - `HttpTemplateBuilderTest` — "Ambient propagator sees exactly one unmodifiable header view backed by the live merge list (Candidate #14)"
  - `ResponseHeadersTest` — "Empty trailer set shares the empty-map singleton instead of a built TreeMap (Candidate #14)"
  - `ResponseHeadersTest` — "Single-value header names are served by immutable List.of() entries (Candidate #14)"
- **Verification:** target suites 56/56; full gate **1,809 / 1,809** green across all eight modules (root 1,647 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + benchmarks 37).
- **Javadoc gate untouched:** all main-source changes are `//` comments; no public signature moved.
- **Coverage:** no new behavioral branches — the core JaCoCo records are unchanged since ADR-0072 (100% Class / 100% Method / 90.65% Branch).
