# ADR-0007: Request Parameter Flexibility — Dynamic Map Expansion (0.7.0 cycle, part 1)

- **Status:** accepted
- **Date:** 2026-10-25
- **Scope:** FEAT-04 of the 0.7.0 cycle (TODO.md §1)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0017 Aspect 2 with Amendments #296 and #305; design decisions below restate the reference contract, deviations are listed explicitly

## Context

Following the nullable-parameter omission shipped in 0.6.1 (ADR-0006, reference ADR-0017 Aspect 1), the remaining ergonomics gap in parameter binding is **dynamic / open-ended parameters and headers**. Search endpoints, dynamic filter sets, and metadata APIs accept arbitrary query parameters or headers that are not known at controller-definition time. Before this ADR, passing a `Map` to `@RequestParam` stringified the whole map (`paramName={a=1, b=2}`), corrupting the request line.

Alongside the Map expansion, an audit-inspired hardening lands in the same code paths: a `byte[]` argument in a header or parameter position previously rendered as the JVM class string (`[B@1a2b3c4d`) on the wire — silent payload corruption (reference Amendment #305).

**Goal:** `@RequestParam Map<?, ?>` and `@RequestHeader Map<?, ?>` expand entry-by-entry into individual query parameters, form fields, multipart text parts, and headers; `byte[]` fails fast in all text positions. Zero runtime dependencies, strict backward compatibility for existing scalar/collection controllers.

---

## Decisions

### 1. `@RequestParam Map<?, ?>` — entry-per-parameter expansion

- Each map entry becomes an individual query parameter (or form field / multipart text part).
- **Blank `name()` → flat keys** (the default): the map key `String.valueOf(entry.getKey())` becomes the parameter name.
- **Named `name()` → namespace prefix**: `@RequestParam(name = "filter") Map<String, Object>` sends `filter[status]=…` (bracketed nesting).
- A `Collection<?>` entry value expands into repeated parameters: `key=val1&key=val2`.
- Null keys, null values, and null collection elements are skipped (consistent with ADR-0006 omission semantics).

### 2. Amendment #296 — the prefix deliberately reads the raw `name()`, not the effective name

For scalar/collection parameters a blank `name()` falls back to the Java parameter name. For a `Map` parameter, `name()` plays a different role — an *optional namespace prefix* — and a blank value means "no prefix": keys are sent as-is, the industry-standard dynamic-map wire format (Retrofit/Feign `@QueryMap`, Spring `@RequestParam Map`). Extending the parameter-name fallback to Map prefixes would make flat keys unreachable (every `Map` would be silently prefixed) — a wire-format breaking change for bare-`Map` controllers, against this epic's compatibility constraint.

### 3. `@RequestHeader Map<?, ?>` — entry-per-header expansion

- Each map entry becomes an HTTP header: name is `String.valueOf(entry.getKey())` (the annotation `name()` is ignored for Maps — header names come from the entries).
- A `Collection<?>` value creates one header instance per non-null element.
- Null keys and null values are skipped.

### 4. Array expansion (parity with collections)

Non-`byte[]` array arguments (`String[]`, `Integer[]`, …) expand element-by-element exactly like collections — one parameter/header/text-part per non-null element — in all four positions (query, header, urlencoded form, multipart text parts). Previously arrays fell into the scalar `toString()` branch and corrupted the wire the same way Maps did.

### 5. Amendment #305 — `byte[]` fails fast in text positions

Header and parameter positions are text on the wire; a `byte[]` has no defined text rendering. A `byte[]` argument — scalar, array element, collection element, or `Map` value — throws `IllegalArgumentException` with an actionable message ("encode the bytes to text explicitly — e.g. Base64 — before passing") instead of silently sending `[B@…` garbage. The fail-fast mirrors the existing "bare `byte[]` cannot be a multipart file" precedent and leaves the encoding decision at the call site.

**Deviation from the reference:** in `infinite`, a `byte[]` multipart *text part* with an explicit `@RequestParam(contentType = …)` is a documented raw-bytes channel (ADR-0031/0033 machinery). `main` does not yet have declarative content types (FEAT-06), so a `byte[]` in a multipart text-part position fails fast unconditionally with `FileContent`/Base64 guidance; the raw-bytes channel arrives with FEAT-06.

### 6. Scope boundary — override/precedence semantics deferred to FEAT-05

The reference `createRequestParams`/header composer also implement the ADR-0017 Aspect 5 precedence merge (dynamic arguments replace same-named static/default entries via `removeIf`). That machinery is inseparable from class/method-level static declarations (FEAT-05, Aspects 3–5) and is **not** part of this port: a dynamic parameter with the same name as a `@RequestDefaultParam` still appends (today's behavior), and the full precedence merge lands with FEAT-05. Documented as a controlled divergence in scope, not in semantics.

---

## Verification Gates

1. **WireMock suite green:** `./gradlew test` — 168 tests, including 14 new in `RequestParameterFlexibilityTest` (flat/prefixed Map query, collection-value expansion, null skipping, non-String keys, array expansion, header Map, urlencoded Map, multipart flat/bracketed parts, `byte[]` fail-fast ×4).
2. **IDE inspections clean.**
3. **Zero runtime dependencies in the generated POM.**
