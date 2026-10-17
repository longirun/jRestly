# ADR-0025: URI template expansion, path encoding controls, and collection/array parameter handling across query, headers and form

## Status
Accepted

## Context
In modern REST APIs, HTTP clients interact with complex URL structures and data types:
1. **Multi-segment and pre-encoded path variables:** In storage, CMS, or object-store APIs (e.g. AWS S3, Git blobs, document management), path variables often represent multi-segment subpaths like `documents/2026/report.pdf` or tokens that are already percent-encoded. Currently, jRestly's `@PathVariable` always passes values through `URLEncoder.encode(..., UTF_8)`, which encodes `/` as `%2F`. Standard web servers, proxies, and firewalls (e.g. Nginx, Spring Boot `StrictHttpFirewall`, Tomcat) frequently reject `%2F` in the path segment with 400 Bad Request to protect against path traversal attacks. Developers need a way to declare unencoded path variables for raw subpaths or already-encoded strings.
2. **Array support in `@RequestParam` and `@RequestHeader`:** While `Collection<?>` types (e.g. `List<String>`) are supported across query parameters, form-urlencoded bodies, and headers, Java arrays (e.g. `String[]`, `Integer[]`, and primitive arrays like `int[]`, `long[]`) were previously only handled in `multipart/form-data` (ADR-0024). When passed to `@RequestParam` or `@RequestHeader`, arrays were passed to `.toString()`, serializing into unusable type markers like `tag=[Ljava.lang.String;@5e265ba4`.
3. **RFC 3986 URI fragment placement:** When an endpoint path or base URL contains a fragment (`#anchor`), appending query parameters naively via `url + "?" + query` results in `http://host/path#anchor?query=1`. According to RFC 3986 §4.1, the URI query component must always precede the fragment component: `[path][?query][#fragment]`. Placing query parameters after the `#` causes HTTP clients to treat them as part of the fragment, omitting them from the actual network request.
4. **Query parameter null safety:** If an interceptor or default parameter injects a `Param` with a `null` value into query parameters, `URLEncoder.encode(param.value(), ...)` previously threw a `NullPointerException`.

## Decisions

### 1. Path Variable Encoding Control via `@PathVariable(encoded = true)`
- Add `boolean encoded() default false` to `@PathVariable`.
- When `encoded = false` (default): retain strict backward compatibility by percent-encoding all reserved and non-ASCII characters via UTF-8 (`/` -> `%2F`, space -> `%20`).
- When `encoded = true`: substitute the variable value into `${name}` directly as `arg.toString()` without invoking `URLEncoder.encode(...)`. This allows multi-segment resource subpaths (e.g. `storage/files/${path}` -> `storage/files/docs/2026/spec.pdf`) and avoids double-encoding already percent-encoded parameters.
- Fail-fast null safety: passing `null` still immediately throws `IllegalArgumentException("Path variable '" + name + "' value cannot be null")`.

### 2. Universal Array Handling in `@RequestParam`, Form-Urlencoded, and `@RequestHeader`
- Provide unified array handling across:
  - Query parameters (`@RequestParam` for GET/POST/etc.)
  - Form-urlencoded body parameters (`@RequestParam` for `APPLICATION_FORM_URLENCODED`)
  - Request headers (`@RequestHeader`)
  - Dynamic maps (`Map<String, ?>` passed to `@RequestParam` or `@RequestHeader`)
- Use `java.lang.reflect.Array` inspection to unpack both object arrays (`String[]`, `Object[]`) and primitive arrays (`int[]`, `long[]`, `double[]`, `boolean[]`, etc.). `byte[]` is preserved as a raw binary payload.
- Empty arrays (`new String[0]`) omit the parameter/header entirely (consistent with empty collections).
- `null` array elements are gracefully skipped (consistent with collections).

### 3. RFC 3986 Compliant Query & Fragment Placement
- Implement `HttpTemplate.appendQuery(String url, String query)`:
  - Detect `#fragment` in the URL.
  - If a fragment exists, query parameters are inserted before `#`, ensuring the final URI structure conforms to RFC 3986: `base + separator + query + fragment`.
  - Preserve existing query parameters if the base URL already contained a `?`.

### 4. Null-Safe Query Param Serialization
- In `HttpTemplate.createUri()`, if `param.value() == null`, serialize as `encode(param.name())` (flag parameter) rather than throwing `NullPointerException`.

## Verification & Test Strategy
1. **Unit tests for URI construction:**
   - `HttpTemplateTest`: query appending with and without fragments, pre-existing query strings, null and empty query strings.
2. **Unit & integration tests for `@PathVariable(encoded = true)`:**
   - Multi-segment paths with slashes (`a/b/c`).
   - Pre-encoded paths without double encoding (`token%201`).
   - Standard encoded path variables retain percent-encoding (`/` -> `%2F`).
   - Null checks fail fast with `IllegalArgumentException`.
3. **Integration tests with WireMock for arrays:**
   - Query parameters with `String[]`, `int[]`, `new String[0]`, arrays with null elements.
   - Headers with `String[]` and arrays in `Map<String, ?>`.
   - Form-urlencoded body with `String[]` and primitive arrays.
4. **Zero-dependency verification:**
   - Only `java.base` reflection and standard URL utilities used.

## Amendment #210

**Missing eager validation of the path template ⇄ `@PathVariable` agreement — an unresolved placeholder fails late, an unused path variable is silently dropped.**

A verb's path template (`@Get`/`@Post`/`@Put`/`@Patch`/`@Delete` `path()`) and its `@PathVariable` parameters are two halves of one contract (ADR-0025), but `ControllerValidator` never checked that they agree:

- **Unresolved `${name}` placeholder** — a template placeholder with no matching `@PathVariable` parameter survives into the URL, where `URI.create` rejects it at *send time* with a cryptic `IllegalArgumentException: Illegal character in path at index N` (verified: `URI.create("http://host/api/users/${id}")`). The failure is late and names neither the method nor the placeholder.
- **`@PathVariable` without a placeholder** — `HttpTemplateBuilder.createUrl` substitutes with `path.replace("${" + name + "}", value)`; when the placeholder is absent the `replace` is a no-op, so the parameter's value is **silently dropped** from the request. No error, no log line.

Fix: `ControllerValidator.validateMethod` now extracts the `${...}` placeholders from the resolved verb path and compares them (by set) against the effective `@PathVariable` names (explicit `name()`, or the Java parameter name when blank), reporting each mismatch through the aggregated `ControllerValidationException` raised at proxy creation:

- `method(): path placeholder '${name}' has no matching @PathVariable parameter`
- `method(): @PathVariable 'name' does not match any ${...} placeholder in path '...'`
- a blank placeholder (`${}`) is rejected as well.

No public API change; a matching template behaves exactly as before. Covered by `ControllerValidationTest.rejectsPathVariableTemplateMismatchEagerly` (red-first).
