# ADR-0064: Problem Details for HTTP APIs (RFC 7807 / RFC 9457) & Error Ergonomics

## Status

Accepted (2026-10-17, Session #239)

## Context

Following the competitive matrix and strategic gap analysis in ADR-0061, the third vital capability identified for enterprise production maturity was standardized HTTP error ergonomics conforming to **RFC 7807** and **RFC 9457** ("Problem Details for HTTP APIs").

In modern REST and cloud-native architectures:
1. **Industry Standard:** Microservices and modern API frameworks (Spring 6+, ASP.NET Core, FastAPI, Cloudflare, AWS API Gateway) standardize error reporting on `application/problem+json` and `application/problem+xml`.
2. **Prior Limitations in jRestly:**
   - Unexpected non-2xx responses were surfaced as raw string diagnostics in `UnexpectedStatusException`, `ClientErrorException` (4xx), and `ServerErrorException` (5xx). Callers had to manually parse JSON or extract error fields.
   - Declarative `@OnError` required authoring custom user DTOs or custom exceptions for standard error structures.
   - No standardized `ProblemDetail` model existed in the core library.

jRestly's architectural principles require:
- **Zero-Dep Core (ADR-0001):** The model and parser must operate without mandatory external dependencies.
- **Ergonomics & Transparency (ADR-0004):** Error information must be accessible intuitively across both declarative `@OnError` and unexpected exception hierarchies.
- **Zero-Garbage Hot Path:** Successful responses (2xx) and non-problem responses must not incur extra allocations.

## Decision

We introduce the `ru.jrestly.problem` package containing the RFC 9457 model `ProblemDetail` and `ProblemDetailException`, and integrate automatic problem detection and resolution into the core HTTP template pipeline.

### 1. Model: `ru.jrestly.problem.ProblemDetail`

Implements the standard members defined by RFC 9457 §3.1:
- `type`: URI reference identifying the problem type (defaults to `about:blank`).
- `title`: Short, human-readable summary of the problem type (auto-populated with standard HTTP status phrases like "Bad Request", "Not Found", "Internal Server Error").
- `status`: HTTP status code (int).
- `detail`: Human-readable explanation specific to this occurrence.
- `instance`: URI reference identifying the specific occurrence.
- `properties`: Application-specific extension members (e.g. `balance`, `invalid-params`, `errorCode`).
- Built-in zero-dependency parser (`ProblemDetail.fromJson(String)`) and serializer (`ProblemDetail.toJson()`).
- Full codec interoperability: integrates with Jackson (`@JsonAnyGetter` / `@JsonAnySetter`), Gson, and Moshi.
- Fluent builder: `ProblemDetail.builder()`, `ProblemDetail.forStatus(int)`, `ProblemDetail.forStatusAndDetail(int, String)`.

### 2. Exception Hierarchy Integration

- **`ru.jrestly.problem.ProblemDetailException`:** Extends `HandledException`, carrying `ProblemDetail` and providing clear formatting in `getMessage()` (`HTTP 404 [Not Found]: User not found`).
- **`ru.jrestly.http.HandledException`:** Gains `public Optional<ProblemDetail> getProblemDetail()`.
- **`ru.jrestly.http.UnexpectedStatusException`**, **`ClientErrorException`**, **`ServerErrorException`:**
  - Extended with constructors accepting `ProblemDetail`.
  - Override `getProblemDetail()` to return `Optional<ProblemDetail>`.
  - Automatically enhance `getMessage()` with `title` and `detail` while preserving prefix compatibility (`Unexpected HTTP status 403 (Forbidden): You do not have enough credit.`).

### 3. Pipeline Dispatch & Resolution (`HttpTemplate`)

1. **Automatic Detection:**
   When an unexpected HTTP status code occurs (outside 2xx and not listed in `@ExpectStatus`), `HttpTemplate` inspects the response:
   - If `Content-Type` is `application/problem+json` or `application/problem+xml`, or if the body is a JSON object carrying RFC 9457 members (`status`, `title`, `detail`, `type`, `instance`), it resolves the payload into `ProblemDetail`.
   - Resolution first attempts the configured `PayloadCodec`, falling back to the built-in zero-dependency parser.
   - Non-problem payloads (HTML, plain text, or standard data JSON) remain untouched with zero error-parsing side effects.
2. **Declarative `@OnError`:**
   - `@OnError(statuses = {400, 404}, errorObject = ProblemDetail.class)` decodes directly into `ProblemDetail` and surfaces via `HandledException.getProblemDetail()`.
   - `@OnError(statuses = {422}, errorObject = ProblemDetailException.class)` instantiates and throws `ProblemDetailException` directly for clean `catch (ProblemDetailException ex)` handling.
3. **Async Parity:**
   Identical resolution behavior in synchronous `exchange()` and asynchronous `exchangeAsync()` (`CompletableFuture`).

## Consequences

1. **Enterprise Grade Error Handling:** Callers consuming RFC 7807 / 9457 APIs can access structured errors via `ex.getProblemDetail().orElseThrow()` without writing custom error DTOs.
2. **Backward Compatibility:** All existing exception signatures and string getters (`getRawBody()`, `getStatusCode()`, `getResponseHeaders()`) remain 100% intact.
3. **Zero Dependencies:** Pure Java 21 core with zero external library requirements.

## Amendment #242 (2026-10-17)

**ZeroDepJsonParser Hardening against Malformed Input, Infinite Loops and OOM:**
1. *Issue:* In `ProblemDetail$ZeroDepJsonParser.parseValue`, unrecognized tokens (such as `[?]`, `[abc]`, `{"key": }`) fell through to the number parsing routine, read zero characters, caught `NumberFormatException`, and returned an empty string with an un-advanced index `i`. In `parseArray`, this triggered an infinite loop allocating memory until `OutOfMemoryError`. In addition, keys containing escaped characters caused an inaccurate offset calculation in `i - key.length() - 2`.
2. *Resolution:*
   - Number parsing strictly verifies a leading digit or minus sign before attempting numeric extraction.
   - Any unrecognized token immediately throws an `IllegalArgumentException` with the token and character position.
   - String key skipping in `parseObject` uses direct `skipQuotedString(json, i)`, ensuring exact index positioning regardless of escaped characters.
   - Unclosed strings, arrays, brackets, missing colons, and missing commas throw actionable `IllegalArgumentException` fail-fast errors instead of looping or misparsing.
   - Default method coverage for `PayloadCodec` and `PayloadCodecProvider` brought class coverage across the entire library to **100.00% (143/143 classes)**.

