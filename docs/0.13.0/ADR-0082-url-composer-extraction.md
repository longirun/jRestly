# ADR-0082: `UrlComposer` — the URI-Assembly Slice of the Builder Wave

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.13.0 — structural wave 2 (ADR-0081), step (a): URI assembly

---

## 1. Context & Motivation

ADR-0081 measured the two remaining god classes and queued a four-step
decomposition of `HttpTemplateBuilder`: URI assembly, header/cookie merge,
body/multipart assembly, resilience wiring. This ADR records **step (a)**.

The audit found one concern living in two homes:

1. **Builder side** — `HttpTemplateBuilder.createUrl` substituted path
   variables, applied `@TargetUrl` (path/query/fragment merge), and fell back
   to the module base URL. Its helper `joinUrlPath` normalized the slash seam.
2. **Template side** — `HttpTemplate.createUri` appended request parameters
   and `appendQuery` inserted a query before an RFC 3986 fragment (ADR-0072).

This split had three costs:

* **Navigability:** the ADR-0081 map had to point "URL/query assembly" at two
  classes; a reader following one request had to jump between them.
* **Drift risk:** fragment placement and percent-encoding rules existed in two
  files, each with its own tests reaching into static methods.
* **Size:** at the start of the step, `HttpTemplateBuilder` was 1,516 physical
  lines / WMC 407 / 110 missed branches, `HttpTemplate` 2,286 / 461 / 80.

Both classes are red on C1/C2. The URI slice is self-contained, stateless, and
has no dependency on dispatch state — the safest first cut.

## 2. Key Decisions

### Decision 1 — one stateless home

`ru.jrestly.http.UrlComposer` is a package-private `final` class with a private
constructor and four static methods:

| method | moved from | responsibility |
|---|---|---|
| `compose(RequestMeta, MethodMetadata, Object[], String)` | `HttpTemplateBuilder.createUrl` | path variables, `@TargetUrl`, base URL, static path query merge |
| `joinUrlPath(String, String)` | `HttpTemplateBuilder.joinUrlPath` | exactly one slash at the seam |
| `resolve(String, List<Param>)` | `HttpTemplate.createUri` | append request params before the fragment, single pass (ADR-0072) |
| `appendQuery(String, String)` | `HttpTemplate.appendQuery` | insert a raw query before the fragment |

The helper holds no fields, no cache, and no thread-local. Every input is an
argument, so the hot path allocates exactly the strings/`URI` it returns — the
same allocation profile as before the move. This matches the collaborator
pattern already used by `ResponseBody`, `MetricsNotifier`, and `CacheDispatch`.

### Decision 2 — seams move, they do not duplicate

The old methods were **deleted**, not wrapped:

* `HttpTemplateBuilder.createUrl` and `joinUrlPath` are gone; `build()` calls
  `UrlComposer.compose(requestMeta, methodMetadata, args, baseUrl)`.
* `HttpTemplate.createUri` and `appendQuery` are gone; `WireRequest`
  construction calls `UrlComposer.resolve(url, requestParams)`.
* Test call sites now reference `UrlComposer` directly
  (`HttpTemplateUriTest`, `UriEncoderTest`, `CollectionFormatUriResolutionTest`,
  `HttpTemplateBuilderTest`).

No deprecated delegate remains: a second seam would recreate the drift this
step removes.

### Decision 3 — behavior pins are byte-for-byte

The extraction is deliberately behavior-preserving:

* null path variable → `IllegalArgumentException("Path variable 'id' value cannot be null")`;
* null `@TargetUrl` → `IllegalArgumentException("@TargetUrl parameter value cannot be null")`;
* an absolute verb path (`http://`, `https://`) bypasses the base URL and the
  target merge;
* a static verb-path query (`@Get("/items?size=10")`) is merged **before** the
  target's query/fragment, so `?size=10&sig=abc` and `?size=10#frag` survive;
* `resolve` keeps the fragment last, appends with `&` when a query already
  exists, and writes encoded names/values directly into one `StringBuilder`
  (ADR-0072).

### Decision 4 — the collaborator owns its spec

`UrlComposerTest` was written first and failed to compile ("cannot find symbol
UrlComposer") before the class existed. It now has **16 tests** and reaches
**90/90 branches, 83/83 lines, 49/49 complexity** — the C11 bar for an
extracted collaborator, with no suite-level debt left behind. The migrated
integration suites above remain as end-to-end pins; they no longer need to
test the helper's internals.

## 3. Measurements (ADR-0081 scorecard)

Physical lines from `wc -l`; WMC, branches, and lines from the JaCoCo XML of
the green gate.

| class | C1 lines before → after | C2 WMC before → after | missed branches before → after |
|---|---:|---:|---:|
| `http.HttpTemplateBuilder` | 1,516 → **1,439** (−77) | 407 → **378** (−29) | 110 → **102** |
| `http.HttpTemplate` | 2,286 → **2,221** (−65) | 461 → **441** (−20) | 80 → **79** |
| `http.UrlComposer` (new) | — → **184** | — → **49** | — → **0** |

Both red classes moved down on every axis. Total WMC is unchanged by design —
an extraction relocates complexity rather than deleting it; the gain is that
the URI concern now sits in a green class with its own spec, and the two red
classes no longer carry it. The next three slices are what will actually
remove complexity from the builder.

## 4. Compatibility & Verification

* **Public API:** untouched — the class and every method are package-private.
* **Gates:** `./gradlew test` green across all eight modules (count in the
  session log / roadmap banner); `compileJava` and `javadoc` stay at zero
  warnings under `-Werror`.
* **Allocation:** no new collections, lambdas, streams, or intermediate
  substrings; `resolve` remains the single-pass ADR-0072 path.
* **Targeted suites:** `UrlComposerTest` (16), plus
  `HttpTemplateUriTest`, `UriEncoderTest`, `CollectionFormatUriResolutionTest`,
  `HttpTemplateBuilderTest` — all green after the call-site migration.

## 5. Consequences

* The ADR-0081 navigation-map row for URL/query assembly now points at
  `http/UrlComposer`.
* Wave 2 continues with: **(b)** header/cookie merge, **(c)** body/multipart
  assembly, **(d)** resilience wiring — each measured against the same
  scorecard and each with its own collaborator suite.
* After the wave, `ControllerValidator.validateMethod` is the next C5 target
  (≤ 20), as recorded in ADR-0081.
* Next free ADR: **0083**.
