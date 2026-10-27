# ADR-0009: Declarative @Accept, @ContentType & MediaType (0.8.0 cycle, part 1)

- **Status:** accepted
- **Date:** 2026-10-27
- **Scope:** FEAT-06 of the 0.8.0 cycle (TODO.md §1)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0031

## Context

Media type negotiation so far required verbose static annotations — `@RequestHeader(name = "Accept", value = "application/json")` — with all their drawbacks: repetitive string literals, no compile-time typo protection, clumsy comma-joined multi-value `Accept`, and a duplicate-header hazard: the merged header list is applied with `HttpRequest.Builder.header(...)` (appending), so a `@RequestHeader("Content-Type")` plus the template's own Content-Type slot could put two conflicting `Content-Type` fields on the wire. Payload ergonomics had the same gap: `byte[]` under the JSON request type was JSON-serialized and `String` bodies were hardwired to UTF-8 regardless of the declared charset.

**Goal:** declarative `@Accept` / `@ContentType` at class and method level with hierarchy inheritance and defined precedence, standard `MediaType` constants, raw `byte[]` passthrough, charset-aware `String` bodies, and fail-fast validation. Strict backward compatibility: without the new annotations the wire is byte-identical to 0.7.0.

---

## Decisions

### 1. Annotation surface

- `@Accept` (`TYPE`, `METHOD`): `String[] value()`; multiple values are joined with `", "` into a single `Accept` header per RFC 9110 §12.5.1.
- `@ContentType` (`TYPE`, `METHOD`): `String value()`; the request body payload media type.
- `MediaType` constants class (`ru.jrestly.http`) checked out from the reference — a superset of the ADR-0031 listing (later reference ADRs added YAML/protobuf/SSE/NDJSON constants; they are inert strings).

### 2. `Accept` resolution

Resolution order: method-level `@Accept`, else interface hierarchy parents-first (the most derived declaration wins), else the client-level `defaultHeader("Accept", ...)` builder default. The resolved value is added as the base `Accept` header right after the auth headers. The existing header merge then does the precedence work: a class/method static `@RequestHeader(name = "Accept")` replaces it case-insensitively, and a runtime parameter `@RequestHeader` replaces the result of that. A `null` runtime argument leaves the static declaration in place (ADR-0006 null-omission). With no layer set, no `Accept` header is sent.

### 3. `Content-Type` resolution

Precedence, highest first: (1) any static/runtime `@RequestHeader` named `Content-Type` — all such entries are extracted from the merged header list (first value wins), so exactly one `Content-Type` field reaches the wire; (2) `@ContentType` (method, else hierarchy parents-first); (3) the client-level `defaultHeader("Content-Type", ...)` builder default; (4) `requestType.mime()`.

Multipart: a declared `multipart/*` type keeps its subtype and gains `; boundary=...` if no boundary is present (`@ContentType("multipart/related")` → `multipart/related; boundary=jrestly-…`); anything else is forced to the canonical `multipart/form-data; boundary=...`. The client-level default participates on the same terms.

### 4. Client-level defaults & Rationale

`JRestlyClient.Builder` (via `SimpleModuleInfo`) deliberately avoids dedicated convenience setters (`accept(...)`, `contentType(...)`, `userAgent(...)`) to prevent API surface explosion as new HTTP headers are requested. Instead, it gains a single universal ambient header mechanism:
- `defaultHeader(String name, String value)`
- `defaultHeaders(Map<String, String>)`
- exposed on `ModuleInfo` as `default List<Header> getDefaultHeaders()`.

Values are validated eagerly at builder time (blank names, null values, and CRLF sequences fail fast with `IllegalArgumentException`).

**Precedence & Codec Safety:**
- `defaultHeader("Accept", ...)` serves as the lowest layer for Accept negotiation, overridden by any `@Accept` (interface, then method) or runtime `@RequestHeader`.
- `defaultHeader("Content-Type", ...)` is excluded from direct ambient header emission and serves strictly as a client-level fallback in `resolveContentType`. It never overrides method-level `@ContentType`, explicit `@RequestHeader`, or form/multipart request types, ensuring POJO codec resolution is never broken by ambient defaults.

### 5. Payload handling (JSON body path)

- `byte[]` `@RequestBody` → raw passthrough, no JSON conversion, declared content type kept.
- `String` `@RequestBody` → encoded with the `charset=` parameter parsed from the effective Content-Type (quoted values handled; absent or unknown charset names fall back to UTF-8 instead of failing).
- Any other DTO → `JsonCodec` serialization, UTF-8.

### 6. `ControllerValidator` additions

At proxy creation, on class and method level: `@Accept` with an empty array, blank entries, or CRLF sequences; `@ContentType` blank or with CRLF sequences — all produce validation problems (header-injection guard). The client-level builder enforces its own eager guards at call time: blank default header names, null values, and CRLF sequences in a name or value throw `IllegalArgumentException`.

### 7. Deviations from the reference

- The client-level `defaultHeader(...)` builder defaults are an extension beyond the reference: `infinite` resolves only the method and interface layers (ADR-0031 names a "Client default" in its hierarchy but implements none). Full chain, most significant first: runtime `@RequestHeader` → static `@RequestHeader` → method `@Accept`/`@ContentType` → interface → client defaultHeader → `requestType.mime()` (Accept: no header when unset).
- Dedicated setters (`accept(...)`, `contentType(...)`) were rejected in favor of universal `defaultHeader(...)` to keep the client builder API surface compact and future-proof against arbitrary HTTP headers.
- The reference resolves declarative media types into `MethodMetadata` at parse time and composes bodies in `BodyComposer`; `main` keeps the resolution as transit code in `HttpTemplateBuilder`, recomputed per `build()` — `MethodMetadata` caching is FEAT-34 and composer extraction is FEAT-35, neither ported yet. Semantics are identical.
- The reference's stream/`Path`/`Supplier` body paths and the `PayloadCodec` content-negotiation registry (FEAT-18/FEAT-41) are not part of this port; the JSON path handles `byte[]`/`String`/DTO only.
- The reference WireMock suite's async `CompletableFuture` case (FEAT-08) is omitted from the adapted test.

---

## Verification Gates

1. **WireMock suite green:** 225 tests, including new `AcceptContentTypeWireMockTest` (28 tests: interface/method `@Accept`, multi-value pass-through, runtime override and null-fallback, interface/method `@ContentType`, charset-encoded `String`, raw `byte[]`, exactly-one-Content-Type wire check, runtime Content-Type override, inheritance with sub-interface overrides, multipart subtype with boundary; the full client-level chain via default headers — accept sent verbatim incl. multi-value, overridden by interface/method declarations and by a runtime header, null-fallback from the runtime layer; defaults sent on every request and replaced by auth headers of the same name; contentType with charset encoding, DTO bodies staying JSON under a declared type, unknown-charset UTF-8 fallback (against a bare JDK server — WireMock 500s on undecodable charsets), runtime override and null-fallback, never overriding form and multipart request types; no `Accept` header when no layer configures it) and `AcceptContentTypeValidationTest` (17 tests: empty/blank/CRLF on both annotation levels, proxy-creation throw, valid pass, builder rejection of blank/CRLF default header names, null and CRLF values, case-insensitive same-name replace).
2. **IDE inspections clean.**
3. **Zero runtime dependencies in the generated POM.**
