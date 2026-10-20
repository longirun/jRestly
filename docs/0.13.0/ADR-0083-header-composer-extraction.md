# ADR-0083: `HeaderComposer` — the Header/Cookie Slice of the Builder Wave

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.13.0 — structural wave 2 (ADR-0081), step (b): header/cookie merge

---

## 1. Context & Motivation

ADR-0081 queued a four-step decomposition of `HttpTemplateBuilder`: URI
assembly, header/cookie merge, body/multipart assembly, resilience wiring.
ADR-0082 recorded step (a). This ADR records **step (b)**, the header slice.

After step (a) the builder was 1,439 physical lines / WMC 378 / 102 missed
branches — the largest debt in the library. The header concern was the next
coherent, self-contained slice:

1. `createHeaders(url)` — the whole merge: ambient auth headers, static
   `@RequestHeader` declarations, dynamic header arguments (scalar, `Map`,
   collection/array), cookies, and the decompression `Accept-Encoding` default;
2. `resolveCookies()` — static `@Cookie` map plus dynamic cookie arguments
   (scalar, `Map`, `HttpCookie`) with the documented null semantics;
3. `appendHeaderValues(...)` — collection/array/scalar expansion of one dynamic
   header argument;
4. `removeHeaderByName(...)` / `hasHeaderNamed(...)` — the Candidate #14 plain
   scans (ADR-0075);
5. `textValue(...)` — the `byte[]` fail-fast conversion shared by header and
   request-parameter positions (the builder's parameter call sites use it too).

Two properties made the slice self-contained. First, every input is an argument
already in scope at the `build()` call site (`ModuleInfo`, `MethodMetadata`,
`Object[] args`) — no dispatch state. Second, **tracing is not part of the
merge**: `applyTracingHeaders` / `applyTracePropagator` depend on the
per-invocation trace context and intentionally run *after* the merge so trace
headers stay the last emitted ones (pinned by `HttpTemplateBuilderTest`). What
tracing needs from the merge is surgery by name — hence `hasHeaderNamed` /
`removeHeaderByName` remain package-private surface on the collaborator, not
private helpers.

## 2. Key Decisions

### Decision 1 — one stateless home

`ru.jrestly.http.HeaderComposer` is a package-private `final` class with a
private constructor, mirroring `UrlComposer` (ADR-0082) and the collaborator
pattern of `ResponseBody`, `MetricsNotifier`, and `CacheDispatch` (C11 in
ADR-0081):

| member | moved from | responsibility |
|---|---|---|
| `compose(ModuleInfo, MethodMetadata, Object[])` | `HttpTemplateBuilder.createHeaders` | ambient + static + dynamic headers, cookie merge, `Accept-Encoding` default; returns a mutable list ready for tracing |
| `resolveCookies(MethodMetadata, Object[])` (private) | `HttpTemplateBuilder.resolveCookies` | static `@Cookie` + dynamic cookie args → `Cookie` header value or `null` |
| `appendHeaderValues(List, String, Object)` (private) | same | collection/array/scalar expansion of one dynamic argument |
| `removeHeaderByName(List, String)` | same | in-place reverse removal, plain loop (Candidate #14) |
| `hasHeaderNamed(List, String)` | same | plain scan (Candidate #14) |
| `textValue(Object, String)` | same | `null` → `"null"`, `byte[]` → fail-fast IAE, else `toString()` |

The class holds no fields, no cache, and no thread-local. `compose` allocates
exactly the result list and (only with dynamic cookie parameters) the cookie
`LinkedHashMap` — the same allocation profile as before the move.

### Decision 2 — seams move, they do not duplicate

The old members were **deleted**, not wrapped. `HttpTemplateBuilder.createHeaders`
now reads:

```java
List<Header> result = HeaderComposer.compose(moduleInfo, methodMetadata, args);
applyTracingHeaders(result, url);
return result;
```

Tracing and the auth-provider consumer stay in the builder; the builder's
compression path, tracing, and request-parameter paths call
`HeaderComposer.hasHeaderNamed` / `removeHeaderByName` / `textValue` directly.
No deprecated delegate remains.

### Decision 3 — dead code is deleted, not migrated

Three branch sites disappeared permanently (ten JaCoCo branch slots, fully
accounted for below):

* `moduleInfo != null && moduleInfo.isDecompressionEnabled()` → the guard was
  unreachable: `compose` dereferences `moduleInfo` on its first line;
  the conjunction was deleted (−2 slots);
* `if (value == null) return;` in `appendHeaderValues` → unreachable: every
  call site pre-filters null (`arg == null → continue`; `Map` entry key/value
  check) (−2 slots);
* the inline `Accept-Encoding` scan in `createHeaders` — a duplicated copy of
  `hasHeaderNamed` — was deduplicated onto the existing helper (−6 slots).

Behavior is unchanged because the paths were dead; the duplicated scan had
identical first-match semantics.

### Decision 4 — behavior pins are byte-for-byte (ADR-0075 invariants kept)

* merge order: surviving ambient headers keep their place → statics in
  declaration order → dynamics in parameter order → `Accept-Encoding` last
  (tracing then appends after that);
* a static declaration wins over an ambient header with the same name; a
  dynamic argument case-insensitively wins over both;
* dynamic `Map` keys with null key/value are skipped; null collection/array
  elements are skipped; a null scalar argument keeps the static twin;
* a dynamic header name keeps its annotation casing (`x-dup` stays `x-dup`);
* cookie merge order follows **parameter-descriptor order**, pinned as
  `staticCookie1=val1; dynCookie=dynVal; mapCookie=mapVal; httpCookie=httpVal`;
  a null dynamic cookie argument removes its static twin; a `Map` null value
  removes the key; `HttpCookie` arguments contribute `name=value`;
* an empty/blank/null existing `Cookie` header value is **replaced**, never
  joined with a leading `"; "` (ADR-0075 / #334 pin);
* `byte[]` in any header or request-parameter position throws the documented
  `IllegalArgumentException` (text-on-the-wire contract).

### Decision 5 — the collaborator owns its spec

`HeaderComposerTest` was written first and failed to compile ("cannot find
symbol HeaderComposer") before the class existed. It now has **22 tests** and
reaches **109/109 branches, 120/120 lines, 61/61 complexity, 570/570
instructions, 0 missed** — the C11 bar for an extracted collaborator, with no
suite-level debt left behind. Running it surfaced two expectations that were
corrected against the code, not vice versa: cookie order follows
parameter-descriptor order (not statics-then-dynamics), and dynamic header
names keep the annotation casing.

## 3. Measurements (ADR-0081 scorecard)

Physical lines from `wc -l`; WMC, branches, and lines from the JaCoCo XML of
the green gate.

| class | C1 lines before → after | C2 WMC before → after | missed branches before → after |
|---|---:|---:|---:|
| `http.HttpTemplateBuilder` | 1,439 → **1,225** (−214) | 378 → **313** (−65) | 102 → **88** (−14) |
| `http.HeaderComposer` (new) | — → **245** | — → **61** | — → **0** |

The branch-universe accounting closes: the builder went from 599 to 480 slots —
**109 relocated** to the composer, **10 deleted** as dead code (Decision 3) —
and the measured library-wide universe confirms it, 4,483 → 4,473.

Library totals for the gate: **1,873/1,873** tests (root 1,709 = 1,687 + 22;
submodules 164), 0 failures/errors. JaCoCo: **Branch 4,085/4,473 = 91.33%**
— new record (previous 90.99%); **Complexity 3,350/3,735 = 89.69%** — new
record (previous 89.28%); Instruction 98.17%, Line 97.84%, Class/Method 100%.

Complexity overall is flat by design: an extraction relocates it (−65 in the
builder, +61 in the composer, −4 net from deleted dead code). The gain is that
the header concern now lives in a green, independently specified class, and the
builder no longer carries it.

## 4. Compatibility & Verification

* **Public API:** untouched — class and every member are package-private.
* **Gates:** `./gradlew test` green across all eight modules; `:javadoc` clean
  under `-Werror`.
* **Allocation:** no new collections, lambdas, or streams on the hot path; the
  Candidate #14 shapes (plain scans, in-place removal, single pass) moved
  intact.
* **Targeted suites:** `HeaderComposerTest` (22) plus the end-to-end pins
  `RequestHeadersTest`, `StaticHeadersAndParamsTest`,
  `CookieSessionWireMockTest`, `HttpTemplateBuilderTest`,
  `RequestTracingWireMockTest` — all green after the call-site migration.

## 5. Consequences

* The ADR-0081 navigation-map row for headers/cookies/auth now points at
  `http/HeaderComposer`; the builder's row keeps tracing.
* Wave 2 continues with: **(c)** body/multipart assembly, **(d)** resilience
  wiring — each measured against the same scorecard and each with its own
  collaborator suite.
* `HeaderComposer` joins `UrlComposer` as a green reference collaborator; the
  builder's remaining red zones are body/multipart and resilience wiring.
* After the wave, `ControllerValidator.validateMethod` is the next C5 target
  (≤ 20), as recorded in ADR-0081.
* Next free ADR: **0084**.
