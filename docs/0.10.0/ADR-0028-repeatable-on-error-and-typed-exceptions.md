# ADR-0028: Repeatable `@OnError`, Type-Level Error Handlers & Refined Exception Hierarchy

- **Status**: Accepted
- **Date**: 2026-10-15
- **Author**: Autonomous Senior Core Engineer & Architect (Session #106)

## Context & Motivation

jRestly's error handling model was founded in ADR-0004 ("Transparent Calls") and evolved through ADR-0008 (Retry with Backoff), ADR-0015 (Streaming), and ADR-0022 (Response Headers). The core tenets have proven robust:
1. **Transparent Calls**: Successful calls return plain domain types `T`, `void`, or `Optional<T>` without wrapping them into `Response<T>`.
2. **Explicit Contracts**: Declared error status codes are handled via `@OnError(statuses = ..., errorObject = ...)`, deserialized into a typed error payload carried by `HandledException`.
3. **Fail-Fast Backstop**: Undeclared error statuses throw `UnexpectedStatusException`, preserving raw body and response headers.

However, as jRestly moves toward production use in real-world microservices and API clients, three critical ergonomic and architectural limitations have surfaced:

1. **No `@Repeatable` on `@OnError`**:
   Real-world REST APIs return distinct error payload schemas for different HTTP error codes (e.g., HTTP `400`/`422` returns `ValidationErrorsDto`, HTTP `404` returns `NotFoundErrorDto`, and HTTP `500` returns `ServerErrorDto`). Currently, `@OnError` can only be declared once per method, forcing developers to either use an unwieldy generic wrapper or forgo type-safe decoding for multiple error statuses.

2. **Method-Only Scope (Lack of `ElementType.TYPE`)**:
   `@OnError` is currently constrained to `ElementType.METHOD`. When a controller defines 10–30 endpoints sharing uniform error contracts (e.g. standard corporate error schemas for `400`, `401`, `403`, `500`), developers are forced to duplicate identical `@OnError` annotations across every single method.

3. **No Direct Exception Throwing**:
   `@OnError` always wraps the decoded payload into `HandledException.getDetails()`. When a developer specifies an exception class (such as `CustomNotFoundException extends RuntimeException`), callers are still forced to catch `HandledException` and manually unwrap or rethrow `e.<CustomNotFoundException>getDetails()`, violating natural Java catch idioms:
   ```java
   // What developers are currently forced to write:
   try {
       client.getUser(id);
   } catch (HandledException e) {
       CustomNotFoundException ex = e.getDetails();
       ...
   }

   // What developers should be able to write:
   try {
       client.getUser(id);
   } catch (CustomNotFoundException e) {
       ...
   }
   ```

4. **Coarse `UnexpectedStatusException`**:
   Any unhandled non-2xx status currently throws a generic `UnexpectedStatusException`. Distinguishing between client errors (HTTP 4xx, bad requests, authentication, authorization) and server errors (HTTP 5xx, upstream gateway timeouts, internal faults) requires repetitive inspection: `if (e.getStatusCode() >= 400 && e.getStatusCode() < 500)`.

## Decision

### 1. Repeatable `@OnError` Annotation with Type-Level Scope
- Update `ru.jrestly.annotation.OnError`:
  - Add `@Target({ElementType.METHOD, ElementType.TYPE})`.
  - Add `@Repeatable(OnError.List.class)`.
  - Add container annotation `ru.jrestly.annotation.OnError.List`.
- Multiple `@OnError` declarations can now be placed on the same method or interface:
  ```java
  @OnError(statuses = {400, 422}, errorObject = ValidationErrorDto.class)
  @OnError(statuses = {404}, errorObject = NotFoundException.class)
  @OnError(statuses = {500}, errorObject = ServerErrorDto.class)
  ```

### 2. Hierarchical Priority Resolution
When resolving error handlers for an invocation:
1. **Method-level declarations win**: Any `@OnError` declared directly on the method has priority for its declared status codes.
2. **Sub-interface declarations win over parent interfaces**: Interface-level `@OnError` applies to all methods of that interface unless explicitly overridden by a method-level declaration for that specific status code.
3. **Super-interface declarations**: Inherited if not overridden by sub-interfaces or methods.

### 3. Multi-Status Mapping in `OnErrorParser`
- Refactor `ru.jrestly.http.OnErrorParser` to maintain a status-to-errorClass mapping:
  - `canParse(int httpStatusCode)`: checks if the status is registered in any active rule.
  - `getErrorClass(int httpStatusCode)`: returns the target class bound to that status code.
  - `getErrorClass()`: returns the primary/first error class for backward compatibility.
  - `register(int status, Class<?> errorClass)`: fluent registration of status mappings.

### 4. Direct Exception Throwing
When a status matches an `@OnError` rule whose `errorObject` is assignable to `Throwable`:
- The response body is deserialized into the target `errorClass`.
- If the deserialized object is an instance of `RuntimeException`, it is thrown directly.
- If it is an `Error`, it is thrown directly.
- If it is a checked exception (`Throwable`), it is wrapped in `RuntimeException`.
- If the response body was empty or deserialization returned `null`, jRestly instantiates the exception via reflection:
  - Tries constructor `(Object details, int statusCode, List<Header> headers)` (matching `HandledException`).
  - Tries constructor `(String message, int statusCode)`.
  - Tries constructor `(String message)` with `"HTTP " + statusCode`.
  - Tries no-arg constructor `()`.
  - Fallback: wraps in `HandledException(null, statusCode, responseHeaders)`.
- If `errorObject` is NOT assignable to `Throwable` (e.g. standard DTO records/classes), the existing behavior is 100% preserved: thrown as `HandledException(details, statusCode, responseHeaders)`.

### 5. Refined Exception Hierarchy
Introduce specialized subclasses extending `UnexpectedStatusException`:
- `ru.jrestly.http.ClientErrorException`:
  - Thrown for undeclared HTTP `4xx` statuses (`statusCode >= 400 && statusCode < 500`).
  - Extends `UnexpectedStatusException`.
- `ru.jrestly.http.ServerErrorException`:
  - Thrown for undeclared HTTP `5xx` statuses (`statusCode >= 500 && statusCode < 600`).
  - Extends `UnexpectedStatusException`.
- Because both extend `UnexpectedStatusException`, existing code catching `UnexpectedStatusException` or `HandledException` continues to function without modification.

### 6. Fail-Fast Validation (`ControllerValidator`)
- `ControllerValidator` validates `@OnError` declarations at controller proxy creation:
  - Each declared status code must be a valid HTTP status (`status >= 100 && status < 600`).
  - Detects conflicting duplicate declarations on the same method or interface where the same status code is bound to different error classes.

## Consequences & Guarantees

- **100% Backward Compatibility**:
  - Existing single `@OnError` annotations on methods behave identically.
  - `HandledException` and `UnexpectedStatusException` hierarchy remains unchanged at the top.
  - All existing tests pass without modification.
- **Zero-Dep Core Preserved**:
  - Implemented entirely within `ru.jrestly` core using standard Java 21 features.
- **Ergonomics**:
  - Seamless handling of realistic REST error scenarios with multiple error schemas.
  - Elimination of boilerplate by declaring shared error handlers at the interface level.
  - Idiomatic Java exception handling via direct custom exception throwing.
