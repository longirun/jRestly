# ADR-0022: Response headers and metadata access

> 2026-10-15 · Proposed · Status: Accepted · Scope: Library maturity vector #3 (maintainer's 2026-10-14 23:25 mailbox: "элегантный доступ к метаданным заголовков (для пагинации, ETag, Location и рейт-лимитов, и т.д.)").

## Context

In jRestly, the method return type is the contract (ADR-0004: Transparent method calls). Methods return domain DTOs (`User`, `List<Item>`), raw strings (`String`), or streams (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`), without forcing unwrapping through generic wrapper envelopes like `Response<T>`, `ResponseEntity<T>`, or `Call<T>`.

However, real-world REST APIs frequently convey critical metadata in HTTP response headers:
1. **Pagination**: RFC 8288 / RFC 5988 `Link` headers (e.g. GitHub/GitLab APIs: `<https://api.example.com/items?page=2>; rel="next"`).
2. **Resource creation**: `Location` header in `201 Created` responses.
3. **Optimistic concurrency & caching**: `ETag` and `Last-Modified` headers.
4. **Rate limiting**: `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`.
5. **Observability**: Execution duration and actual HTTP status code.

In error scenarios (`@OnError` or unexpected HTTP status codes), headers are already accessible via `HandledException.getHeaders()` and `UnexpectedStatusException.getHeaders()`. For global observability, `ResponseInterceptor` (ADR-0018) provides access to `ResponseContext`.

The missing capability is **call-site access to response headers and metadata on success paths** without compromising the zero-dependency core or violating ADR-0004's transparent return type principle.

## Decisions

### Decision 1: `ResponseHeaders` Holder Class

Introduce an immutable-friendly, thread-safe metadata container: `ru.jrestly.http.ResponseHeaders`.
It encapsulates:
- `int statusCode()`: HTTP status code.
- `Duration duration()`: invocation round-trip duration.
- `List<Header> headers()`: complete list of raw response headers.
- `Map<String, List<String>> map()`: case-insensitive view of headers.
- `Optional<String> firstValue(String name)`: case-insensitive header lookup.
- `List<String> allValues(String name)`: all values for a given header name.
- `OptionalLong firstLongValue(String name)`: parses integer/long headers (rate limits, content length).
- `Optional<String> etag()`: convenience getter for `ETag`.
- `Optional<String> location()`: convenience getter for `Location`.
- `Optional<URI> locationUri()`: parsed `URI` from `Location`.
- `Optional<String> contentType()`: convenience getter for `Content-Type`.
- `Optional<String> link(String rel)`: RFC 8288 link target lookup by relation (e.g. `"next"`, `"prev"`).
- `Map<String, String> links()`: parsed RFC 8288 relations to target URIs.
- `Optional<Duration> retryAfter()`: parsed `Retry-After` (seconds or HTTP-date).
- `boolean isSuccess()`: helper for 2xx status codes.
- `boolean isPopulated()`: indicates whether metadata has been captured.

`ResponseHeaders` instances can be instantiated directly via `new ResponseHeaders()`, serving as mutable holders that receive response data upon call completion.

### Decision 2: Method Parameter Binding

Controller interface methods may declare an optional unannotated parameter of type `ResponseHeaders`:
```java
@Get("/items")
List<Item> getItems(@RequestParam("page") int page, ResponseHeaders headers);
```

- **Validation**: `ControllerValidator` recognizes `ResponseHeaders` as an intrinsic library hook parameter. It does not treat it as a missing-annotation parameter or a request body.
- **Invocation**: At call execution, `HttpTemplateBuilder` records the `ResponseHeaders` instance. The parameter is never serialized into the request URL, headers, or body.
- **Population**: In `HttpTemplate.processResponse`, the provided `ResponseHeaders` holder is populated with the status code, response headers, and duration *before* payload deserialization and before any exception is thrown.
- **Null safety**: Passing `null` as the argument is safe and treated as an opt-out (no-op).
- **Async compatibility**: In `CompletableFuture<T>` methods, the holder is populated inside `.thenApply()` before the future completes, ensuring headers are available when callbacks (`thenAccept`, `join()`) run.

### Decision 3: Zero-Signature Capture Pattern (`ResponseHeaders.capture`)

For callers who do not wish to modify their controller interface signatures (e.g., standard domain interfaces), `ResponseHeaders` provides a thread-scoped capture mechanism:
```java
CapturedResponse<List<Item>> response = ResponseHeaders.capture(() -> client.getItems(1));
List<Item> items = response.result();
Optional<String> nextLink = response.headers().link("next");
```

- `record CapturedResponse<T>(T result, ResponseHeaders headers)` holds both the domain result and the populated metadata.
- Operates via a scoped `ThreadLocal<Consumer<ResponseHeaders>>`.
- Reentrant and safely cleans up in a `try ... finally` block.
- Ideal for synchronous pagination loops and one-off metadata checks.

### Decision 4: RFC 8288 Web Linking Parser

Implement a robust, zero-dependency RFC 8288 / RFC 5988 parser in `ResponseHeaders`:
- Extracts `<target-uri>; rel="relation"` and `<target-uri>; rel=relation`.
- Supports comma-separated multi-link headers.
- Case-insensitive relation matching.

## Consequences & Invariants

1. **ADR-0004 strictly preserved**: Methods continue to return domain types (`T`, `List<T>`, `InputStream`, `Stream<T>`), never forced envelopes.
2. **Zero-dependency**: Only standard Java 21 platform classes (`java.lang.*`, `java.util.*`, `java.time.*`, `java.net.URI`).
3. **Zero breaking changes**: All existing controller interfaces and tests remain 100% compatible.

## Test Suite & Verification

The feature is verified by 17 tests (+18 test cases) across unit and WireMock integration suites:
1. `ResponseHeadersTest` (8 unit tests):
   - Unpopulated holder defaults and immutability.
   - Case-insensitive map lookups (`firstValue`, `allValues`, `contentType`, `etag`, `location`).
   - RFC 8288 multi-link, unquoted `rel`, and multi-relation parsing (`link("next")`, `links()`).
   - `Retry-After` parsing (delta-seconds and RFC 1123 HTTP-date).
   - `firstLongValue` numeric parsing and safe fallback.
   - `ResponseHeaders.capture` clean lifecycle and reentrancy.
   - `ControllerValidator` rules for methods with `ResponseHeaders`.
2. `ResponseHeadersWireMockTest` (9 E2E WireMock integration tests):
   - RFC 8288 pagination via method parameter `ResponseHeaders`.
   - RFC 8288 pagination via `ResponseHeaders.capture` zero-signature pattern.
   - 201 Created with `Location` and `ETag`.
   - Rate limit headers parsing (`X-RateLimit-*`).
   - Async twin `CompletableFuture<T>` parameter binding.
   - Streaming responses (`InputStream`) parameter binding.
   - Fault handling: `@OnError` and `UnexpectedStatusException` error response header capture.
   - Null-safety opt-out.

## Amendment #234 (Session #234, 2026-10-17): Eager validation of ResponseHeaders parameter annotations and side-channel parameter cardinality

- **Problem:** `ResponseHeaders` is an intrinsic side-channel hook parameter (along with `UploadProgressListener` and `DownloadProgressListener`). Unlike its siblings, `ResponseHeaders` previously lacked eager validation in `ControllerValidator`. Annotating a `ResponseHeaders` parameter with HTTP request bindings (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestBody`, `@MultipartFormFile`) was silently accepted and resulted in double-binding (the `ResponseHeaders.toString()` hashcode was serialized into the URL, headers, or body). Furthermore, none of the three side-channel parameter types checked for arity/cardinality: declaring two `ResponseHeaders`, two `UploadProgressListener`, or two `DownloadProgressListener` parameters silently bound only the first parameter while silently leaving the second unpopulated.
- **Decision:** `ControllerValidator` now eagerly enforces:
  1. `ResponseHeaders parameter must not be annotated with HTTP parameter annotations`.
  2. `multiple parameters of type ResponseHeaders, at most one is allowed`.
  3. `multiple parameters of type UploadProgressListener, at most one is allowed`.
  4. `multiple parameters of type DownloadProgressListener, at most one is allowed`.
- **Verification:** 11 new tests in `ResponseHeadersValidationTest` (red-first on all 10 invalid combinations, green on valid declarations). Core suite at **1176**, total gate at **1256/1256** passing tests.

