# ADR-0084: `BodyComposer` — the Body/Multipart Slice of the Builder Wave

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.13.0 — structural wave 2 (ADR-0081), step (c): body/multipart assembly

---

## 1. Context & Motivation

ADR-0081 queued a four-step decomposition of `HttpTemplateBuilder`: URI
assembly, header/cookie merge, body/multipart assembly, resilience wiring.
ADR-0082 recorded step (a), ADR-0083 step (b). This ADR records **step (c)**,
the request-payload slice.

After step (b) the builder was 1,225 physical lines / WMC 313 / 88 missed
branches. The remaining body concern was still the largest coherent block:

1. `resolveBody` / `createBodyPublisher` — the whole payload dispatch:
   `BodyPublisher`, `Flow.Publisher`, `InputStream`, `Path`, `File`,
   `Supplier` (with every nested type), `byte[]`, `String`, and codec-driven
   DTO serialization with the no-codec fail-fast;
2. multipart assembly — the `@MultipartFormFile` expansion and the
   `@RequestParam` part builder: scalar, collection, array, `Map` prefix
   semantics, CSV/SSV/TSV/PIPES/BRACKETS/EXPLODE formats, explicit part
   content types, and the bare-`byte[]` guard;
3. the shared helpers `parseCharset`, `adaptToByteBufferPublisher`,
   `fileSizeBytes`, and the file-part constructors.

The slice is self-contained: every input is already in scope at `build()`
(`ModuleInfo`, `MethodMetadata`, `RequestMeta`, `Object[] args`, effective
content type). The form-urlencoded body (`createUrlEncodedBody`) deliberately
stays in the builder: it consumes the builder-private `createRequestParams()`,
so it belongs to the parameter/query slice rather than the payload slice.
`MultipartBodyPublisher` remains the wire encoder (ADR-0078 seam untouched).

## 2. Key Decisions

### Decision 1 — one stateless home

`ru.jrestly.http.BodyComposer` is a package-private `final` class with a
private constructor, following `UrlComposer` (ADR-0082) and `HeaderComposer`
(ADR-0083) and the C11 collaborator pattern:

| member | moved from | responsibility |
|---|---|---|
| `compose(ModuleInfo, MethodMetadata, RequestMeta, Object[], String, boolean)` | builder body dispatch | returns a `BodyPayload(bytes, publisherSupplier, effectiveContentType, knownSize)`; covers every supported payload type and the codec fail-fast |
| `composeParts(ModuleInfo, MethodMetadata, RequestMeta, Object[])` | builder multipart assembly | returns parts in parameter-declaration order |
| `parseCharset(String, Charset)` | builder helper | `charset=` parsing with quoted values, delimiters, and fallback |
| `adaptToByteBufferPublisher(Flow.Publisher<?>, Charset)` | builder helper | `ByteBuffer` pass-through, `byte[]` raw, `String`/`toString()` encoded |
| `fileSizeBytes(Path)` (private) | builder helper | lazy size probe for metrics; `null` on I/O failure |
| `partFromValue` / `createFilePart` / `createFilePartFromPath` / `multipartFileValues` (private) | builder helpers | part construction, file-name derivation, array/collection flattening |

`BodyPayload` is a nested record so the builder receives the four values in
one allocation instead of four mutable locals. The class holds no fields, no
cache, and no thread-local.

### Decision 2 — seams move, they do not duplicate

The old members were **deleted**, not wrapped. `HttpTemplateBuilder.build`
now reads:

```java
BodyComposer.BodyPayload payload = BodyComposer.compose(
        moduleInfo, methodMetadata, requestMeta, args,
        effectiveContentType, isContentTypeExplicit);
body = payload.bytes();
bodyPublisherSupplier = payload.publisherSupplier();
knownBodySize = payload.knownSize();
effectiveContentType = payload.effectiveContentType();
```

and the multipart branch calls `BodyComposer.composeParts(...)`. Boundary
folding, boundary generation, compression, and `HttpTemplate` wiring stay in
the builder. No deprecated delegate remains.

### Decision 3 — invariants replace unreachable guards

Three guards disappeared because their inputs are normalised upstream by
`MethodMetadata.ParameterDescriptor`, and one switch case was dead:

* `methodMetadata != null ? methodMetadata.defaultCollectionFormat() : EXPLODE`
  — `composeParts` dereferences `methodMetadata` on the next line; the ternary
  was unreachable (−2 branch slots);
* `p.requestParamFormat() != null ? p.requestParamFormat() : default` — the
  descriptor already resolves `DEFAULT`/null against the method/interface
  format, so the re-resolution duplicated the invariant and could never take
  the fallback (−2);
* the `format == null || format == DEFAULT → EXPLODE` normalisation in
  `appendCollectionParts` plus the separate `DEFAULT` switch label — the same
  invariant; the switch now uses `default` for EXPLODE (−3);
* `explicitContentType.isEmpty()` in `partFromValue` and the null-value
  ternaries — callers pre-filter null and the descriptor maps an empty
  annotation to `null` (−4).

The bare-`byte[]` guard, the null map key/value skips, and the null
collection-element filters are **not** touched: they are observable behavior
pins (ADR-0024/#296).

### Decision 4 — behavior pins are byte-for-byte

* payload precedence: explicit `BodyPublisher` → `Flow.Publisher` →
  `InputStream` → `Path` → `File` → `Supplier` → `byte[]` → `String` → codec;
* `knownSize` is populated only for `Path`/`File` (via `Files.size`), and is
  `null` when the probe fails; `String`/`byte[]` keep their bytes and no
  publisher supplier;
* `Supplier` bodies are replayed per subscription; a null supply yields
  `noBody()`; unsupported supply types throw the documented
  `IllegalArgumentException`;
* a content type is preserved only when explicitly declared; otherwise the
  non-text legs fall back to `application/octet-stream` while `String`/`byte[]`
  keep the caller's effective type;
* `Flow.Publisher` adaptation preserves `ByteBuffer` instances, passes raw
  `byte[]` through untouched, encodes `String`/`toString()` with the payload
  charset, drops nulls, and forwards subscribe/error/complete unchanged;
* multipart order follows parameter declaration; collection formats are
  CSV/SSV/TSV/PIPES/BRACKETS/EXPLODE; a `Map` parameter uses the raw annotation
  name as an optional namespace prefix (ADR-0017 Amendment #296); explicit part
  content types resolve through the codec registry first, then fall back to the
  charset; `byte[]` parts require an explicit content type;
* `@MultipartFormFile` accepts `FileContent`, `File`, `Path`, `String`, and
  arrays/collections/varargs thereof; a root path fails with the documented
  “Path must have a file name” error; a bare `byte[]` fails with the
  `FileContent.of(...)` hint.

### Decision 5 — the collaborator owns its spec

`BodyComposerTest` was written first against the not-yet-existing class, then
filled out until the collaborator reached **142/142 branches, 191/191 lines,
100/100 complexity, 1,008/1,008 instructions, 0 missed** — the C11 bar. The
suite has **17 tests**. Running it corrected one expectation against the code,
not vice versa: raw `byte[]` items in a `Flow.Publisher` are passed through
untouched (the collector must decode them with the matching charset), rather
than re-encoded with the declared charset.

## 3. Measurements (ADR-0081 scorecard)

Physical lines from `wc -l`; WMC, branches, and lines from the JaCoCo XML of
the green gate.

| class | C1 lines before → after | C2 WMC before → after | missed branches before → after |
|---|---:|---:|---:|
| `http.HttpTemplateBuilder` | 1,225 → **850** (−375) | 313 → **205** (−108) | 88 → **66** (−22) |
| `http.BodyComposer` (new) | — → **432** | — → **100** | — → **0** |

The branch-universe accounting: the builder's slots moved into the composer;
the normalisation/dead guards above removed the rest. The library-wide branch
universe shrank from 4,475 to 4,458 (−17 net) even while the new class added
147 slots. Library totals for the gate: **1,890/1,890** tests (root 1,726 =
1,709 + 17; submodules 164), 0 failures/errors. JaCoCo: **Branch 91.79%
(4,092/4,458) — new record**, **Complexity 90.23% (3,363/3,727) — new record**,
Instruction 98.36%, Line 98.05%, Class/Method 100%.

The line drop is larger than the composer itself because `BodyComposer` uses
the nested `BodyPayload` record and consolidated helpers; complexity moved to
a green, independently specified home rather than disappearing.

## 4. Compatibility & Verification

* **Public API:** untouched — class and every member are package-private;
  `BodyPayload` is nested and package-private.
* **Gates:** `./gradlew test` green across all eight modules; `:javadoc` clean
  under `-Werror`.
* **Allocation:** no new collections on the hot path; `BodyPayload` replaces
  four builder locals with one record; the codec is still resolved once per
  payload, and the multipart list is the same `ArrayList`.
* **Targeted suites:** `BodyComposerTest` (17), `HttpTemplateBuilderTest`,
  `MultipartPartFlexibilityTest`, `MultipartSizeOnlySeamTest` (ADR-0078),
  `RequestBody*`, `RequestParams*`, and the WireMock multipart/streaming
  suites — all green after the call-site migration.

## 5. Consequences

* The ADR-0081 navigation map now points request body/multipart assembly at
  `http/BodyComposer`; `MultipartBodyPublisher` stays the wire encoder and
  response streaming stays with `ResponseBody`.
* Wave 2 has one step left: **(d)** resilience wiring (circuit breaker, rate
  limiter, retry policy, engine options).
* `BodyComposer` joins `UrlComposer` and `HeaderComposer` as a green reference
  collaborator. The builder is now 850 lines, less than 40% of its 0.12.0
  size.
* Next free ADR: **0085**.
