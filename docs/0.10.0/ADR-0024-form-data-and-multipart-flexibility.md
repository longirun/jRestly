# ADR-0024: Form-data & Multipart Flexibility (Explicit Content-Type, Charsets, JSON DTO Parts & Null-Safety)

## Status
Accepted (Session #102, 2026-10-15)

## Context & Motivation
jRestly offers first-class support for `multipart/form-data` and `application/x-www-form-urlencoded` requests (ADR-0002, ADR-0005).
However, modern web APIs (Spring Boot `@RequestPart`, FastAPI, AWS S3, Google Drive, payment gateways) frequently require:
1. **JSON DTO or custom MIME parts in multipart requests:** A common REST pattern is sending a metadata entity alongside a binary file in the same multipart request (e.g. `item: {"name":"invoice", "total": 100}` with `Content-Type: application/json` and `file: invoice.pdf` with `Content-Type: application/pdf`). Previously, all `@RequestParam` parts in multipart were strictly stringified via `toString()` and forced to `Content-Type: text/plain; charset=UTF-8`.
2. **Explicit Charset & MIME type per part:** Legacy or localized systems often require non-UTF-8 charsets (e.g. `windows-1251`, `ISO-8859-1`) or specific text formats (`text/csv`, `text/html`, `application/xml`).
3. **Null-Safety & Nullable part values:** Calling `MultipartPart.text(name, null)` threw an unhandled `NullPointerException`. In `application/x-www-form-urlencoded`, any param value evaluating to null triggered NPE during `URLEncoder.encode`.

## Decisions

### 1. Explicit `contentType` in `@RequestParam`
Add `String contentType() default ""` to `ru.jrestly.annotation.RequestParam`:
- When empty (default): multipart text parts continue to produce `Content-Type: text/plain; charset=UTF-8` for backward compatibility.
- When explicitly specified (e.g. `@RequestParam(name = "metadata", contentType = "application/json")`):
  - If the argument is a domain DTO (not String, byte[], File, Path, or FileContent), it is serialized to JSON using the client's configured `JsonCodec`.
  - If the argument is a `String`, it is encoded into bytes using the charset extracted from `contentType` (e.g., `charset=ISO-8859-1` or `charset=windows-1251`), defaulting to `UTF-8` if omitted.
  - If the argument is `byte[]`, the bytes are sent verbatim with the specified `Content-Type`.
  - Supports collections and arrays: each element is serialized with the part's configured `contentType`.

### 2. Ergonomic `MultipartPart` Factories
Extend `ru.jrestly.http.MultipartPart` with:
- `public static MultipartPart text(String name, String value, Charset charset)`: creates a text part with an explicit charset. Null-safe (null `value` produces empty byte array `byte[0]`).
- `public static MultipartPart text(String name, String value)`: hardened to be null-safe (null `value` produces `byte[0]`).
- `public static MultipartPart part(String name, String contentType, byte[] content)`: creates a non-file part without `filename` attribute in `Content-Disposition`, carrying explicit `contentType` and content bytes (null-safe).
- `public static MultipartPart part(String name, String filename, String contentType, byte[] content)`: creates a part with explicit filename, contentType, and content bytes.

### 3. Nullable & Empty Form-Urlencoded Safety
In `HttpTemplateBuilder.createUrlEncodedBody`:
- If `param.value()` is null, render `URLEncoder.encode(param.name()) + "="` with an empty value instead of throwing `NullPointerException`.

## Non-Goals & Invariants
- **Zero dependencies:** Everything is implemented strictly on Java 21 standard library (`java.net.http`, `java.nio.charset`, `java.lang.reflect`). No external multipart or MIME parsing libraries.
- **Strict backward compatibility:** Existing `@RequestParam` without `contentType` continues to behave identically.

## Verification
1. Unit tests in `MultipartPartTest` / `MultipartTest`: custom charsets, null-safety, explicit parts.
2. WireMock integration tests:
   - Multipart upload with JSON DTO part + file part.
   - Multipart upload with custom charset (e.g. ISO-8859-1 or Windows-1251).
   - Form-urlencoded with empty and null parameters.

## Amendment #209

**Missing eager validation of `@MultipartFormFile.partName` — a blank value is silently put on the wire as `name=""`.**

`@MultipartFormFile.partName` is a *required* name attribute (no default), yet `ControllerValidator` never checked it. It is the one name-bearing parameter annotation without a fallback: `@RequestParam`, `@RequestHeader` and `@Cookie` all resolve a blank `name()` to the Java parameter name (`MethodMetadata.parse`, `name().isEmpty() ? p.getName() : name`), while `@MultipartFormFile.partName()` is used verbatim. A blank value therefore produces a part whose `Content-Disposition` reads `name=""` — no client-side error, and the server cannot associate the part with any form field (RFC 7578 §4.2 requires a `name` parameter). The same silent-misconfiguration class as the blank `@SetAuthDetails.headerName` (ADR-0046 Amendment #208) and the null `@Compress` algorithm (ADR-0041 Amendment #190).

Fix: `ControllerValidator.validateMethod` adds a blank check (`method(): @MultipartFormFile partName must not be blank`), joining the aggregated `ControllerValidationException` raised at proxy creation. No public API change; a properly named part behaves exactly as before. CR/LF in `partName`/`contentType` is *not* duplicated here — `MultipartPart`'s canonical constructor already rejects it loudly. Covered by `ControllerValidationTest.rejectsBlankMultipartFormFilePartNameEagerly` (red-first).

## Amendment #214

**`@MultipartFormFile` on a method whose `requestType` is not `MULTIPART_FORM_DATA` silently dropped the parts.**

`@MultipartFormFile` parameters are assembled into parts only inside the `MULTIPART_FORM_DATA` branch of `HttpTemplateBuilder` (`createMultipartParts()`); every other `requestType` builds its own body and never reads the multipart parameters. `ControllerValidator` already enforced the *inverse* direction — a `MULTIPART_FORM_DATA` method must have at least one payload parameter — but nothing enforced the forward direction: a file parameter on a plain `@Post`/`@Put`/`@Patch`/`@Delete` (default `APPLICATION_JSON`) or on an `APPLICATION_FORM_URLENCODED` method passed eager validation, the parts list stayed `null`, and the file argument was silently dropped from the wire request (no error, no log). The same paired-declaration class as `path()` ⇄ `@PathVariable` (ADR-0025 Amendments #210/#211).

Fix: `ControllerValidator.validateMethod` computes the verb's effective `RequestType` once and, for every `@MultipartFormFile` parameter whose method is not `MULTIPART_FORM_DATA`, adds `method(): @MultipartFormFile parameter requires requestType = MULTIPART_FORM_DATA` to the aggregated `ControllerValidationException` at proxy creation. No public API change; a multipart method behaves exactly as before. Covered by `ControllerValidationTest.rejectsMultipartFormFileWithoutMultipartRequestTypeEagerly` (red-first).

## Amendment #216

**A `@MultipartFormFile` parameter combined with `@RequestParam` on the same parameter silently dropped the request-param binding.**

`HttpTemplateBuilder.createMultipartParts()` resolves each parameter with an `if/else-if` chain — `if (p.isMultipartFile()) { … } else if (p.isRequestParam()) { … }` — so a parameter carrying *both* annotations is turned into a file part and its `@RequestParam` binding is never reached. There is no second path that could still apply it: multipart methods skip the query string entirely (`HttpTemplateBuilder.build()` calls `createRequestParams()` only when the request type is neither `MULTIPART_FORM_DATA` nor `APPLICATION_FORM_URLENCODED`). The value was therefore absent from the wire request without an error or a log.

The inverse-direction guard from Amendment #214 (a `@MultipartFormFile` requires `MULTIPART_FORM_DATA`) does not cover this, and neither does the hub-centric `@RequestBody`/`@Cookie` combination guard (Amendment #215) — this is a pairing between two non-hub annotations. Note the asymmetry with the other pairings among `@PathVariable`/`@RequestParam`/`@RequestHeader`/`@MultipartFormFile`: those double-bind (both halves fire), which is why they remain accepted; only `@MultipartFormFile` + `@RequestParam` resolves to a single part and drops the other binding.

Fix: `ControllerValidator.validateMethod` adds `method(): parameter must not combine @MultipartFormFile with @RequestParam` to the aggregated `ControllerValidationException` at proxy creation. No public API change; separate file and text-field parameters behave exactly as before. Covered by `ControllerValidationTest.rejectsMultipartFormFileCombinedWithRequestParamEagerly` (red-first).

## Amendment #233

**Form or multipart methods with only URI/header parameters (e.g. `@PathVariable`) silently passed eager validation and produced empty bodies.**

`ControllerValidator.validateMethod` previously enforced the "must have at least one parameter" rule for `APPLICATION_FORM_URLENCODED` and `MULTIPART_FORM_DATA` via a blacklist-based `payloadParamCount` that filtered out `ResponseHeaders`, progress listeners, `@Cookie`, and `@RequestHeader` — but neglected `@PathVariable`. A method whose only parameters were `@PathVariable` (or unannotated / headers) therefore counted as having payload parameters.

At runtime, however, `HttpTemplateBuilder.createUrlEncodedBody()` builds the form body strictly from `@RequestParam` parameters (`createRequestParams()`), and `createMultipartParts()` builds parts strictly from `@MultipartFormFile` and `@RequestParam` parameters. When none are present, `createUrlEncodedBody()` returns `new byte[0]` and `createMultipartParts()` returns an empty list. The request was silently sent on the wire with an empty body (or empty multipart payload).

Fix: `ControllerValidator.validateMethod` replaces the negative-filter heuristic with a positive capability check:
- `MULTIPART_FORM_DATA` requires at least one `@MultipartFormFile` or `@RequestParam` parameter.
- `APPLICATION_FORM_URLENCODED` requires at least one `@RequestParam` parameter.

If none are present, the aggregated `ControllerValidationException` reports `method(): MULTIPART_FORM_DATA method must have at least one parameter` or `method(): APPLICATION_FORM_URLENCODED method must have at least one parameter`. No public API change; methods combining `@PathVariable` with body parameters behave exactly as before. Covered by `ControllerValidationTest.rejectsFormOrMultipartWithoutBodyParametersEagerly` (red-first).
