# ADR-0006: Quality & Core Polish (0.6.1 cycle)

- **Status:** accepted
- **Date:** 2026-10-23
- **Scope:** release cycle 0.6.1 (minor bug fixes & developer experience polish)
- **Provenance:** ported from `jrestly-infinite` Tier 1 and field issue recorded in `../../TODO.md`

## Context

Following the release of `v0.6.0`, three concrete developer experience gaps and minor quirks were observed in field usage and automated testing:

1. **`LocalDateTime` deserialization failure:** The default `JacksonCodec.JavaTimeSupport` hardcoded the pattern to `yyyy-MM-dd HH:mm:ss`. Standard ISO-8601 timestamps containing `T` separators and/or fractional seconds (e.g. `2026-10-08T09:25:53.123456`, which JDK 9+ `LocalDateTime.now().toString()` emits by default) fail deserialization with Jackson's `InvalidFormatException`.
2. **0.5.0 Carry-over quirks:** 
   - `HttpTemplate.logRequest` body logging logic bypassed `PUT` requests entirely (`POST`, `PATCH`, `DELETE` only), missing debug logs for PUT payloads and allocating `Arrays.asList` per request.
   - `HttpTemplate.processResponse` checked `Void.class.equals(returnType)` but omitted primitive `void.class`. On successful responses carrying non-empty bodies, primitive `void` methods attempted unnecessary deserialization via the JSON codec.
3. **Nullable parameters in request mapping:** Passing `null` as an argument to `@RequestParam` or `@RequestHeader` either appended `"param=null"` to the query string or threw formatting exceptions instead of being omitted gracefully.

**Goal:** Polish the core client behavior without breaking any existing contract, maintaining 100% backward compatibility and zero runtime dependencies.

---

## Decisions

### 1. `JacksonCodec` Standard `java.time` Support

- Standardize default JSON temporal handling across all `java.time` types (`LocalDateTime`, `LocalDate`, `Instant`, `OffsetDateTime`, etc.) by registering `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule` when present on the classpath.
- Disable `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` by default to produce standard ISO-8601 wire strings.
- Reject custom formatting mini-frameworks or single-type enums in the library core: temporal customization belongs strictly in the user's domain via `new JacksonCodec(customObjectMapper)`.

### 2. 0.5.0 Quirk Fixes

- Update `BODY_LOGGING_METHODS` in `HttpTemplate` to `Set.of(POST, PATCH, DELETE, PUT)` stored in a `private static final` constant to eliminate per-request hot-path allocations and ensure PUT bodies are logged.
- Update response processing: check `|| void.class.equals(returnType)` alongside `Void.class.equals(returnType)`, cleanly discarding any returned payload on successful statuses without invoking the codec.

### 3. Nullable `@RequestParam` and `@RequestHeader` Omission

- In `HttpTemplateBuilder`, if a parameter annotated with `@RequestParam` or `@RequestHeader` evaluates to `null` at invocation time, gracefully omit the query parameter or header from the constructed request.
- Keep `@PathVariable` strict: passing `null` to a `@PathVariable` continues to fail fast with an actionable exception since a null path component invalidates the URI structure.

---

## Verification Gates

1. **WireMock suite green:** `./gradlew test` passes all unit and integration tests.
2. **IDE inspections clean:** Zero compiler warnings, zero lint issues.
3. **Zero runtime dependencies:** Generated POM contains 0 `<dependency>`.
