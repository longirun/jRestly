# ADR-0010: Form-Data & Multipart Flexibility (0.8.0 cycle, part 2)

- **Status:** accepted
- **Date:** 2026-10-29
- **Scope:** FEAT-07 of the 0.8.0 cycle (TODO.md §1)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0024

## Context

While jRestly supported basic `multipart/form-data` and `application/x-www-form-urlencoded` payloads, real-world REST and RPC APIs frequently require:
1. **JSON DTO or custom MIME parts in multipart requests:** A common REST pattern is sending metadata alongside a file in the same multipart payload (e.g. `item: {"name":"widget"}` with `Content-Type: application/json` and `file: doc.txt`). Previously, all `@RequestParam` parts in multipart were stringified via `toString()` and forced to `Content-Type: text/plain; charset=UTF-8`.
2. **Explicit Charset & MIME type per part:** Localized or legacy systems often mandate specific charsets (e.g. `windows-1251`, `ISO-8859-1`) or custom text formats (`text/csv`, `application/octet-stream`).
3. **Null-Safety:** Passing `null` to the `MultipartPart.text` factory triggered an unhandled `NullPointerException` for bypass users assembling parts directly. Form-urlencoded bodies need no fix: null `@RequestParam` arguments are omitted there since 0.6.1, and `Param` values are never null.
4. **Validation Gaps:** Eager controller validation permitted blank part names in `@MultipartFormFile`, allowed file parameters on non-multipart methods, silently ignored `@RequestParam` when paired with `@MultipartFormFile` on the same parameter, and missed verifying that form or multipart methods actually declared payload parameters (Amendments #209, #214, #216, #233).

---

## Decisions

### 1. Explicit `contentType` in `@RequestParam`

Added `String contentType() default ""` to `ru.jrestly.annotation.RequestParam`:
- **Default (empty):** Multipart text parts continue to produce `Content-Type: text/plain; charset=UTF-8` for full backward compatibility.
- **Explicit Content-Type (e.g. `@RequestParam(name = "item", contentType = "application/json")`):**
  - If the argument is a domain DTO (and contentType contains `"json"`), it is serialized to JSON using the client's configured `JsonCodec`.
  - If the argument is a `String`, it is encoded into bytes using the charset extracted from `contentType` (e.g. `charset=ISO-8859-1` or `charset=windows-1251`), falling back to `UTF-8`.
  - If the argument is `byte[]`, the raw bytes are sent verbatim with the specified `Content-Type`. Without an explicit `contentType`, `byte[]` continues to fail fast with actionable guidance.
  - Supports collections and arrays: each element is serialized with the part's configured `contentType`.

### 2. Ergonomic & Null-Safe `MultipartPart` Factories

Added new factory methods to `ru.jrestly.http.MultipartPart`:
- `public static MultipartPart text(String name, String value)`: hardened to be null-safe (null `value` produces empty byte array `byte[0]`).
- `public static MultipartPart text(String name, String value, Charset charset)`: creates a text part with an explicit charset (null-safe).
- `public static MultipartPart part(String name, String contentType, byte[] content)`: creates a non-file part without a `filename` attribute in `Content-Disposition`, carrying explicit `contentType` and content bytes (null-safe).
- `public static MultipartPart part(String name, String filename, String contentType, byte[] content)`: creates a part with explicit filename, contentType, and content bytes.

### 3. Form-Urlencoded Encoding — intentionally unchanged

No code change: null `@RequestParam` arguments are omitted (0.6.1 semantics) and `Param` values are never null, so the null-value NPE scenario guarded against in the reference is unreachable in `main`. Wire behavior is unchanged: empty-string values render `name=`, null arguments yield no pair. The reference's dead guards (`params.isEmpty()` early return, null-value ternary before `URLEncoder.encode`) are deliberately not ported — see Deviations.

### 4. Eager Controller Validation Hardening

Extended `ru.jrestly.ControllerValidator`:
- **Amendment #209:** Validate that `@MultipartFormFile.partName` is not blank (`upload(): @MultipartFormFile partName must not be blank`).
- **Amendment #214:** Validate that `@MultipartFormFile` parameters are declared only on methods with `requestType = MULTIPART_FORM_DATA` (`upload(): @MultipartFormFile parameter requires requestType = MULTIPART_FORM_DATA`).
- **Amendment #216:** Reject parameters combining `@MultipartFormFile` and `@RequestParam` (`upload(): parameter must not combine @MultipartFormFile with @RequestParam`).
- **Amendment #233:** Positive capability check requiring at least one body parameter:
  - `MULTIPART_FORM_DATA` requires at least one `@MultipartFormFile` or `@RequestParam` parameter.
  - `APPLICATION_FORM_URLENCODED` requires at least one `@RequestParam` parameter.
  Methods with only `@PathVariable` or non-body parameters fail fast eagerly at proxy creation.

---

## Deviations from the reference (ADR-0024)

1. **JSON detection heuristic instead of the `PayloadCodec` registry:** the reference resolves a serialization codec by the part's `contentType` through the pluggable `PayloadCodec` registry (FEAT-41 scope). Until FEAT-41 is ported, `main` detects JSON parts by a case-insensitive `contains("json")` check on the declared `contentType` and serializes DTOs with the client's `JsonCodec`. Non-JSON content types with non-String arguments fall back to `toString()` — the same result as the reference when no codec is registered.
2. **`byte[]` fail-fast message** keeps main's 0.6.0 `@MultipartFormFile`/`FileContent` guidance next to the reference's `@RequestParam(contentType)` hint.
3. **Dead urlencoded guards stripped (§2 divergence):** the reference's `createUrlEncodedBody` early-returns `new byte[0]` on empty params and guards `param.value()` against null before `URLEncoder.encode`; both branches are unreachable in `main`, so the dead code is removed instead of ported verbatim. Wire behavior is identical.

---

## Verification

1. Unit tests in `MultipartPartFlexibilityTest`: null safety, custom charsets, non-file parts with explicit MIME, CRLF smuggling rejection.
2. WireMock integration tests in `MultipartFlexibilityWireMockTest`:
   - Multipart upload with JSON DTO part + file part.
   - Multipart upload with String JSON.
   - Multipart upload with custom charsets (`windows-1251`, quoted `"ISO-8859-1"`).
   - Multipart upload with raw `byte[]` and explicit `Content-Type`.
   - Multipart upload with collection of JSON DTOs.
   - Form-urlencoded submission with an empty-string parameter (renders `name=`).
3. Controller validation tests in `ControllerValidationTest` covering Amendments #209, #214, #216, and #233.
4. Quality gates: full test suite green (245 tests passed), zero runtime dependencies in generated POM.
