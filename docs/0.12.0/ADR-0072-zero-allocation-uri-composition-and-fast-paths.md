# ADR-0072: Zero-Allocation URI Composition & RFC 3986 Fast-Paths

* **Status:** Accepted
* **Date:** 2026-10-19
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 Candidate №13 — Hot-Path Dispatch Allocation Pruning & URI Fast-Paths

---

## 1. Context & Motivation

During the execution of **ADR-0055** (Zero-Garbage Dispatch Pipeline) and **ADR-0066** (`HttpTemplate` Decomposition), jRestly eliminated redundant allocations across method dispatching, interceptor execution, and response parsing. However, dynamic URL and URI assembly remained an unaddressed hot-path allocation bottleneck:

1. **Multi-Pass Query Assembly in `HttpTemplate.createUri`:**
   Building a URI with query parameters previously allocated:
   - An intermediate `StringBuilder query = new StringBuilder()`;
   - Multiple intermediate `String` objects via `URLEncoder.encode(param.name()).replace("+", "%20")` and `param.value()`;
   - An intermediate `query.toString()`;
   - Two `String.substring` slices (`base` and `fragment`) inside `appendQuery`;
   - A multi-string concatenation `base + separator + query + fragment` creating another intermediate `String`;
   - Finally, `URI.create(...)` parsed the combined string.
   For a typical GET request with 3 query parameters (e.g. `page=1&size=20&sort=desc`), this pipeline allocated 10–15 temporary string and builder objects in Young Gen.

2. **Redundant Percent-Encoding on Pure ASCII Unreserved Inputs:**
   In real-world REST APIs, the vast majority of query parameter names (`id`, `page`, `size`, `sort`, `filter`, `limit`, `offset`) and scalar values (numeric IDs like `42`, booleans `true`/`false`, ISO dates `2026-10-19`, UUIDs, alphanumeric slugs) consist exclusively of RFC 3986 / RFC 1738 unreserved characters:
   `[a-zA-Z0-9_.*-]`.
   Calling `URLEncoder.encode(...)` followed by `.replace("+", "%20")` for these inputs wasted CPU cycles on bitset lookups, char array buffers, and substring searches, even though the output is mathematically identical to the input.

3. **Per-Invocation Placeholder Token Concatenation:**
   In `HttpTemplateBuilder.createUrl()`, substituting path variables computed `"{" + p.pathVariableName() + "}"` on every single HTTP request, despite the variable name being static and known at controller compilation/parse time.

---

## 2. Key Decisions & Architecture

### Decision 1: Single-Pass Zero-Intermediate URI Assembly
`HttpTemplate.createUri()` is refactored into a single-pass builder:
- When `requestParams` is null or empty, it short-circuits directly to `URI.create(url)` with zero allocations.
- When `requestParams` are present, a single `StringBuilder` is allocated with pre-sized capacity (`url.length() + requestParams.size() * 16 + 8`).
- The base path and query are appended using `CharSequence` slicing (`sb.append(url, 0, baseEnd)`) without creating substring objects.
- Query parameters and values are encoded and written **directly** into the single builder buffer.
- Fragments (`#anchor`) are appended directly from the original URL at the end before invoking `URI.create(sb.toString())`.
- The standalone helper `HttpTemplate.appendQuery(url, query)` is similarly optimized to slice `url` directly without allocating intermediate `base` and `fragment` substrings.

### Decision 2: Pure ASCII Unreserved Fast-Path (`UriEncoder`)
A dedicated, package-private utility `ru.jrestly.http.UriEncoder` is introduced:
- `isUnreserved(String s)` scans the string for characters strictly within `[a-zA-Z0-9_.*-]`.
- For any string satisfying `isUnreserved(s)`:
  `URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20") == s` is invariant.
- `UriEncoder.encode(s)` immediately returns the input instance `s` when unreserved, allocating 0 bytes.
- `UriEncoder.encodeTo(s, sb)` appends `s` directly to the `StringBuilder`, bypassing all encoder indirection.
- Reserved characters (spaces, non-ASCII Unicode, `/`, `?`, `#`, `&`, `=`, `+`) safely fall back to standard `URLEncoder.encode(s, UTF_8).replace("+", "%20")`, guaranteeing 100% wire-level parity.

### Decision 3: Cached `placeholderToken` in `MethodMetadata.ParameterDescriptor`
`MethodMetadata.ParameterDescriptor` precomputes and caches `placeholderToken` (e.g. `"{id}"`) at controller startup when `pathVariableName` is parsed. `HttpTemplateBuilder.createUrl()` uses `p.placeholderToken()` directly, eliminating string concatenation on every request.

---

## 3. Compatibility & Verification

- **100% Wire Parity:** All existing percent-encoding tests (`PathVariableEncodingTest`, `PathVariableEncodedTest`, `HttpTemplateUriTest`, `CollectionFormatUriResolutionTest`) remain fully passing.
- **Fail-Fast & Fragment Preservation:** URLs with existing query parameters, existing fragments, or question marks inside fragment anchors are strictly preserved.
- **Coverage Gate:** `UriEncoder` is covered to 100% across all branches, lines, and methods.
