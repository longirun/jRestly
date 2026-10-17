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

## Amendment #243 (2026-10-17)

**Array Serialization in ProblemDetail Properties & Parser Hardening:**
1. *Issue (31st "quiet" finding in series):* In `ProblemDetail.appendJsonValue`, only `Iterable<?>` was checked for array-like collection values. When callers supplied native Java arrays (e.g., `String[]`, `int[]`, `Object[]`) into extension properties via `setProperty(...)`, `appendJsonValue` fell through to the `else` branch, invoking `val.toString()` and outputting Java type tokens like `"[Ljava.lang.String;@5e265ba4"` instead of a proper JSON array `[...]`. Furthermore, in `ZeroDepJsonParser.parseString`, unicode escapes (`\uXXXX`) without 4 trailing characters or with invalid hex characters either silently swallowed the escape or threw unhandled raw `NumberFormatException`.
2. *Resolution:*
   - `ProblemDetail.appendJsonValue` explicitly checks `val.getClass().isArray()` and recursively formats every element using `java.lang.reflect.Array` reflections, supporting both primitive and object arrays.
   - `ZeroDepJsonParser.parseString` enforces exact 4-character hex verification for `\uXXXX` sequences, failing fast with actionable `IllegalArgumentException`.
   - Comprehensive unit testing for fluent setters (`withType`, `withTitle`, `withStatus`, `withDetail`, `withInstance`, `withProperty`), typed property access (`getProperty(name, Class<T>)`), `ProblemDetailException` constructors, and `CacheControl` / `InMemoryHttpCache` expiration mechanics.
   - Total test suite expanded to **1324/1324** tests green. JaCoCo instruction coverage exceeded the **90%** threshold (**90.85% Instructions**, **90.64% Lines**, **76.92% Branches**, **100.00% Classes**).

## Amendment #244 (2026-10-17)

**Client Facade Verification, ProblemDetail Nested Map Serialization & JaCoCo 92% Instruction Milestone:**
1. *ProblemDetail & Parser Enhancements:*
   - Verified that `ProblemDetail.appendJsonValue` properly handles nested `Map<?, ?>` structures and explicit `null` properties in extension members, formatting valid nested JSON objects and null literals.
   - Tested large `long` numbers exceeding `Integer.MAX_VALUE`, string-based status recovery, escape sequences (`\/`, `\b`, `\f`, `\r`, `\t`), and nested string quote balancing in `ZeroDepJsonParser`.
   - Verified `ProblemDetail.Builder.properties(Map)` bulk property assignment.
2. *Core Facade Quality & Unification:*
   - Built dedicated `JRestlyClientBuilderTest` testing all `JRestlyClient.Builder` facade shortcuts across caching, virtual threads, cookie stores, proxies, compression thresholds, tracing propagators, resilience (circuit breaker, rate limiter), authentication helpers (Bearer, Basic, API Key), mock/short-circuit mechanisms, timeouts, and engines.
   - Closed coverage gaps in `FileCookiePersister.of(File)`, `OnErrorParser` reverse setter sequencing, and `InMemoryHttpCache` non-cacheable/unparseable date scenarios.
3. *Quality Metrics:*
   - Total test suite reached **1343 / 1343** tests green (+19 tests across all modules, 0 failures, 0 errors).
   - JaCoCo coverage established a new historic high: **92.02% Instructions** (28,738 / 31,231), **91.92% Lines** (6,248 / 6,797), **77.76% Branches**, **95.29% Methods**, and **100.00% Classes (143/143)**.

## Amendment #246 (2026-10-17)

**Peripheral Hardening, Wire Contract Verification & JaCoCo 80% Branch / 93.5% Instruction Milestone:**
1. *ResponseHeaders & Transport Ergonomics:*
   - Added unit tests for `ResponseHeaders.ageSeconds()`, validating non-negative parsing, absent header defaults, negative values, and malformed strings.
   - Tested `ResponseHeaders.captureCallable(Callable<T>)` covering checked exceptions propagation, guaranteed ThreadLocal cleanup on failures, and nested execution scopes.
   - Verified `Set-Cookie2` header parsing, `cookie(name)` and `cookieValue(name)` case-insensitivity, and `cookieMap()` empty-string fallback for null values.
   - Verified 4-arg `populate` overload and HTTP/2 trailers accessors (`declaredTrailers`, `firstTrailer`).
2. *WireResponse Comprehensive Contract:*
   - Built dedicated `WireResponseUnitTest` covering all `of(...)` static factory overloads (status code only, string bodies, byte arrays, headers with null/non-null bodies), `hasBody()` semantics, `Builder` body overloads (byte[], string, charset, stream), null version fallback to HTTP/1.1, equality, hashCode, and formatted `toString()`.
3. *Persistent Cookie Storage & Tracing:*
   - Verified `PersistentCookieStore.getURIs()` delegation to underlying store, `Builder.delegate(...)`, `.local` domain normalization during preload, and `FileCookiePersister` on paths without parent directories.
   - Verified `TraceContext.of(...)` 3-arg factory, 1-arg `parse(...)`, W3C forward compatibility Level 1 for future versions with extra fields, and `TracePropagators` default factories, null/empty composites, and input validations.
4. *ProblemDetail & ZeroDepJsonParser:*
   - Tested typed property mismatch safety in `getProperty(name, Class<T>)`, URI instances and property name blank filtering in `Builder`.
   - Verified parser resilience against malformed numbers (`1.2.3`, `1e+`), unclosed brackets in nested structures, and non-string object keys.
5. *Quality Metrics:*
   - Total test suite reached **1376 / 1376** tests green (+21 tests across all modules, 0 failures, 0 errors).
   - **JaCoCo Coverage Milestones:**
     * **CLASS:** **143 / 143 (100.00%)** — complete class coverage across all modules.
     * **BRANCH:** **80.17%** (3,533 / 4,407) — surpassed 80% branch coverage for the first time in project history!
     * **INSTRUCTION:** **93.51%** (29,174 / 31,198) — new historic all-time high!
     * **LINE:** **93.39%** (6,346 / 6,795) — new historic all-time high!
     * **METHOD:** **96.76%** (1,372 / 1,418) — new historic all-time high!

## Amendment #257 (2026-10-18)

**Tolerance of the Optional Leading UTF-8 BOM — 35th "Quiet" Finding in Series:**
1. *Problem:* RFC 8259 §8.1 permits a JSON text to be prefixed with an optional byte order mark (U+FEFF). A server emitting a BOM-prefixed `application/problem+json` body lost the structured error in **both** resolution paths: `ProblemDetail.fromJson` failed its `trim().startsWith("{")` shape check (Java's `trim()` does not strip U+FEFF, which is not a whitespace character), and `HttpTemplate.resolveProblemDetail` rejected the body in the same pre-check before any codec saw it. Both `fromJson` call sites wrap the parse in `catch (Throwable ignored)`, so the failure was silent: unexpected-status exceptions carried no `ProblemDetail` at all, and on a zero-dependency classpath (NoOp codec) the `@OnError` mapping fell back to a bare `ProblemDetail.forStatus(statusCode)` — title, detail, type, instance and extension members silently dropped.
2. *Resolution:*
   - `ProblemDetail.fromJson` strips a single leading U+FEFF after `trim()` (re-trimming whitespace that may follow it), before the object shape check — one fix covering direct calls, the `handleOnError` fromJson fallback, and the `resolveProblemDetail` fallback.
   - `HttpTemplate.resolveProblemDetail` applies the identical one-line normalization before its pre-check, so the codec path (external JSON libraries already skip the BOM) and the built-in parser see the same clean document.
   - At most one BOM is stripped, mirroring RFC 8259's "may be prefixed" wording — a doubled BOM is content, not a mark.
3. *Verification:* red-first — `ProblemDetailTest.fromJsonToleratesLeadingByteOrderMark` (unit), `ProblemDetailWireMockTest` (BOM body on an unexpected status — the definite pre-check break; `@OnError` BOM mapping guard on the Jackson classpath), `ZeroDepStandaloneWireMockTest` (NoOp codec, where the `fromJson` fallback is the only route — both the `@OnError` and the unexpected-status legs red before the fix). Total test suite reached **1448 / 1448** green; JaCoCo: **83.45% Branch**, **95.88% Instruction**, **95.69% Line** — all new historic highs.




