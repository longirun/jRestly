# ADR-0023: Conditional requests, 304 Not Modified, and Optional return types

> 2026-10-15 · Proposed · Status: Accepted · Scope: Library maturity (candidate #2 from DRAFT.md: conditional requests & caching headers, ETag, 304 Not Modified, plus native Optional return type ergonomics).

## Context

In modern REST APIs, HTTP conditional requests (RFC 9110 §13 / RFC 7232) and caching semantics are ubiquitous:
1. **Conditional GET**: Clients send `If-None-Match` (with an entity tag `ETag`) or `If-Modified-Since` (with an HTTP-date). If the server resource has not changed, the server responds with `304 Not Modified` and an empty body (0 bytes), while updating caching headers (`ETag`, `Cache-Control`, `Date`).
2. **Optimistic Locking**: Clients send `If-Match` or `If-Unmodified-Since` for state-changing operations (`PUT`, `PATCH`, `DELETE`). If the precondition fails, the server responds with `412 Precondition Failed`.

In jRestly, two ergonomics limitations existed prior to this ADR:
- **304 Treated as Unexpected Failure**: By default, `isExpectedStatus` accepts only 2xx status codes (`200..299`). When a client performed a conditional request and received `304 Not Modified`, jRestly threw an `UnexpectedStatusException(304)` unless the user manually annotated the method with `@ExpectStatus(statuses = {200, 304})`.
- **No Native `Optional<T>` Return Type Support**: Modern Java idioms represent optional or nullable REST resources with `java.util.Optional<T>`. Previously, returning `Optional<T>` either produced `null` on empty responses or failed during JSON deserialization because base Jackson/Gson/Moshi configurations without external modules do not handle `Optional` reflection types natively. Furthermore, a `404 Not Found` response on an `Optional<T>` method resulted in an `UnexpectedStatusException(404)` instead of the idiomatic `Optional.empty()`.

Additionally, while `ResponseHeaders` (ADR-0022) introduced `etag()`, it lacked standard caching convenience accessors for `Last-Modified` (including RFC 1123 date parsing), `Cache-Control`, `isNotModified()`, and `isNotFound()`.

## Decisions

### Decision 1: Protocol-Aware 304 Not Modified Handling

When a request is conditional (i.e. carries `If-None-Match` or `If-Modified-Since` headers), or when the method's return type is `Optional<T>`, HTTP `304 Not Modified` is automatically recognized as an expected response status.
- The method call succeeds without requiring an explicit `@ExpectStatus(statuses = {200, 304})` annotation.
- If an explicit `@ExpectStatus` annotation is present on the method, it retains strict precedence.
- For domain return types `T` (non-Optional), `null` is returned since the body is empty, while `ResponseHeaders` (passed as an argument or captured via `ResponseHeaders.capture`) exposes `headers.isNotModified() == true`, `headers.statusCode() == 304`, and updated response headers.

### Decision 2: Native `Optional<T>` Return Type Ergonomics

jRestly natively supports `Optional<T>` and `CompletableFuture<Optional<T>>` as interface return types:
1. **Type Resolution**: `HttpTemplateBuilder` inspects the return type. When the effective return type is a `ParameterizedType` with raw type `java.util.Optional`, it extracts the type argument `T` (e.g. `User`, `List<Item>`, `String`).
2. **Missing/Empty Resource Handling**:
   - Status codes `404 Not Found`, `304 Not Modified`, `204 No Content`, or an empty response body (`null` or 0 bytes) evaluate to `Optional.empty()`.
   - Unless intercepted by `@OnError`, HTTP `404 Not Found` is treated as expected for `Optional<T>` methods, avoiding `UnexpectedStatusException(404)`.
3. **Payload Deserialization**:
   - When a body is present with a successful status code, jRestly deserializes the payload into the extracted inner type `T` using the configured `JsonCodec` and wraps the result in `Optional.ofNullable(result)`.
   - This eliminates dependency on Jackson `jackson-datatype-jdk8` or external codec modules, preserving the zero-dependency core invariant.
4. **Async Twin**:
   - `CompletableFuture<Optional<T>>` methods resolve non-blockingly, yielding a `CompletableFuture` completed with `Optional.of(value)` or `Optional.empty()`.

### Decision 3: Caching & Condition Helpers in `ResponseHeaders`

Extend `ru.jrestly.http.ResponseHeaders` with standard caching and condition convenience methods:
- `boolean isNotModified()`: returns `true` if `statusCode == 304`.
- `boolean isNotFound()`: returns `true` if `statusCode == 404`.
- `Optional<String> lastModified()`: returns the raw `Last-Modified` header string.
- `Optional<Instant> lastModifiedInstant()`: parses the `Last-Modified` header using `DateTimeFormatter.RFC_1123_DATE_TIME` into a `java.time.Instant`. Invalid or unparseable date values return `Optional.empty()`.
- `Optional<String> cacheControl()`: returns the `Cache-Control` header string.

## Consequences

- **Developer Ergonomics**: Developers can declare clean `Optional<User>` query methods that naturally return `Optional.empty()` on 404 or empty responses, and perform conditional GETs with automatic 304 handling.
- **Zero-Dependency**: Implemented purely on standard Java 21 platform classes (`java.util.Optional`, `java.time.format.DateTimeFormatter`, `java.time.Instant`).
- **Backward Compatibility**: Existing methods returning domain types `T`, `void`, or primitive wrappers remain completely unaffected. Explicit `@ExpectStatus` and `@OnError` annotations continue to take precedence.
