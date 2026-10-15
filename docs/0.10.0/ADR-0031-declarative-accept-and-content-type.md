# ADR-0031: Declarative @Accept, @ContentType & MediaType Constants

> 2026-10-15 · **Accepted** · Session #109 (agent-loop). Builds on ADR-0017 (Request Parameters & Dynamic Headers) and ADR-0027 (Timeout Controls).
> Source of truth for declarative HTTP media type negotiation, content type overrides, and zero-allocation header pipeline integration.

## Context

In modern REST and microservice architectures, HTTP media type negotiation (`Accept`) and payload serialization declaration (`Content-Type`) are fundamental protocol features (RFC 9110 §8.3 & §12.5.1).

Prior to ADR-0031, configuring media types in jRestly required developers to write verbose static or parameter-level annotations:
```java
@RequestHeader(name = "Accept", value = "application/json")
@RequestHeader(name = "Content-Type", value = "application/xml")
```

This approach had multiple drawbacks:
1. **Verbosity & Boilerplate:** Repetitive string literals (`name = "Accept"`, `name = "Content-Type"`) scattered across methods and interfaces.
2. **Error-Prone:** Typos in header names or media types were not caught at compile time.
3. **Multi-Value Negotiation:** Declaring multiple acceptable media types (e.g. `Accept: application/json, text/plain`) required clumsy comma-separated strings inside `@RequestHeader`.
4. **Header Duplication Hazard:** If both `requestType.mime()` and a `@RequestHeader(name = "Content-Type")` were present, standard `HttpRequest.Builder.header("Content-Type", ...)` could append multiple conflicting headers instead of cleanly replacing the value.
5. **Non-JSON Payload Ergonomics:** When sending raw `byte[]` or XML/plain-text strings with `@RequestBody`, users expected natural byte passthrough and charset encoding rather than JSON serialization.

### Invariants & Non-Goals

1. **Zero-Dep Core:** Built entirely on standard Java 21 types (`java.lang.annotation`, `java.nio.charset.Charset`, `java.net.http.HttpRequest`). No third-party dependencies.
2. **Transparent Return Types (ADR-0004):** No changes to return types.
3. **Deterministic Hierarchy:** `Method > Sub-interface > Super-interface > Client default`. Runtime method arguments (`@RequestHeader`) always retain final override priority.
4. **Strict Security Validation:** Blank media types and CRLF injections (`\r`, `\n`) are rejected fail-fast during proxy creation.

---

## Decisions

### 1. The `@Accept` Annotation (`ru.jrestly.annotation.Accept`)

Declares the accepted response representation media types for an interface or method:

```java
package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Accept {

    /**
     * One or more media types to accept in the response (e.g. {@code "application/json"},
     * {@code "text/plain"}, or {@code MediaType.APPLICATION_JSON}).
     * When multiple values are provided, they are joined with {@code ", "} into a single
     * HTTP {@code Accept} header according to RFC 9110.
     *
     * @return the accepted media types
     */
    String[] value();
}
```

### 2. The `@ContentType` Annotation (`ru.jrestly.annotation.ContentType`)

Declares the request body payload media type for an interface or method:

```java
package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ContentType {

    /**
     * The MIME type of the request body (e.g. {@code "application/json"},
     * {@code "application/xml"}, {@code "text/plain; charset=utf-8"}, or
     * {@code MediaType.APPLICATION_OCTET_STREAM}).
     *
     * @return the content type string
     */
    String value();
}
```

### 3. Standard Media Type Constants: `MediaType` (`ru.jrestly.http.MediaType`)

To eliminate string typos and provide clean developer ergonomics:

```java
package ru.jrestly.http;

public final class MediaType {

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_XML = "application/xml";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_XML = "text/xml";
    public static final String TEXT_EVENT_STREAM = "text/event-stream";
    public static final String APPLICATION_NDJSON = "application/x-ndjson";
    public static final String ALL = "*/*";

    private MediaType() {}
}
```

### 4. Resolution Order & Deduplication Pipeline

In `HttpTemplateBuilder`:
- **`Accept` Resolution:**
  1. Method-level `@Accept` annotation.
  2. Sub-interface to super-interface hierarchy traversal.
  3. Formatted into a single header: `Header("Accept", String.join(", ", accept.value()))`.
  4. Runtime or static `@RequestHeader(name = "Accept")` takes precedence and replaces any static `@Accept` definition.
- **`Content-Type` Resolution:**
  1. Method-level `@ContentType` annotation.
  2. Sub-interface to super-interface hierarchy traversal.
  3. Fallback to `requestMeta.requestType().mime()` (or boundary formatting for multipart).
  4. Runtime or static `@RequestHeader(name = "Content-Type")` takes precedence.
  5. Content-Type is set explicitly via `HttpRequest.Builder.setHeader("Content-Type", ...)` to prevent duplicate header fields.
- **Payload Handling:**
  - `byte[]` parameters passed to `@RequestBody` are sent directly as raw bytes without JSON conversion.
  - `String` parameters are encoded using the charset parsed from `ContentType` (or UTF-8 default).
  - Other DTO objects are serialized via `JsonCodec`.

### 5. Fail-Fast Validation (`ControllerValidator`)

`ControllerValidator` enforces at proxy creation time:
- `@Accept`: `value` must not be empty; no media type string may be blank; no CRLF characters (`\r`, `\n`) are permitted.
- `@ContentType`: `value` must not be blank; no CRLF characters are permitted.
- Violations trigger `ControllerValidationException`.
