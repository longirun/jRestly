# ADR-0090: MethodMetadataParser Extraction — Reflection & Annotation Analysis Decoupling and MethodMetadata Red-to-Green Transition

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the god-class reduction program, we attack `ru.jrestly.http.MethodMetadata` (1,140 lines, WMC 257, 28 missed branches — RED in the ADR-0081 scorecard). We extract all reflection parsing, annotation hierarchy traversal, and parameter descriptor analysis into a dedicated stateless collaborator `MethodMetadataParser`.

---

## 1. Problem & Boundaries

In earlier versions of jRestly (ADR-0051), `MethodMetadata` served two distinct architectural responsibilities:
1. **Immutable Dispatch Metadata (Data Model / Value Object):** Cached per `(Method, ControllerClass)` key in `CACHE`, providing zero-reflection accessor methods on hot dispatch paths (`HttpTemplateBuilder`, `UrlComposer`, `HeaderComposer`, `BodyComposer`, `ResilienceComposer`).
2. **Heavy Reflection Parser (Algorithm / Parser):** Contained ~725 lines of reflection logic across 25 static helper methods:
   - Request HTTP verb parsing (`Get`, `Post`, `Put`, `Patch`, `Delete`);
   - Parameter descriptor array compilation (`@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestParam`, `@RequestBody`, `@MultipartFormFile`, `ResponseHeaders`, progress listeners, `@TargetUrl`);
   - Annotation inheritance and hierarchy traversal (`collectInterfaceHierarchy`, `collectInterfaces`);
   - Static headers and cookie parsing with case-insensitive deduplication and order preservation;
   - Static default query params merging;
   - Policies parsing (`@Timeout`, `@Retry`, `@HttpVersion`, `@ExpectStatus`, `@FollowRedirects`, `@Compress`, `@CircuitBreaker`, `@RateLimiter`, `@WithInterceptor`, `@WithMetrics`, `@OnError`);
   - Return type analysis (`CompletableFuture`, `Optional`, reactive/streaming types).

Because the parsing algorithms and edge guards were coupled inside `MethodMetadata`, the class remained in the **RED** zone of the ADR-0081 scorecard (lines: 1,140 > 800 red line; WMC: 257 > 250 red line; missed branches: 28).

---

## 2. Architecture & Design Decisions

### (D1) Extraction of `MethodMetadataParser`
All reflection, annotation extraction, and descriptor compilation logic is extracted into a dedicated, package-private, stateless collaborator:
`ru.jrestly.http.MethodMetadataParser` (package-private, `final`, private constructor).

Public package surface:
- `static MethodMetadata parse(Method method, Class<?> controller)` — the single entry-point orchestrating the extraction and returning the immutable `MethodMetadata` instance.
- Package-private helper seams:
  - `static RequestMeta parseRequestMeta(Method method)`
  - `static List<Class<?>> collectInterfaceHierarchy(Class<?> clazz)`
  - `static List<Header> parseStaticHeaders(Method method, List<Class<?>> hierarchy, String interfaceAccept, String methodAccept)`
  - `static Map<String, String> parseStaticCookies(Method method, List<Class<?>> hierarchy)`
  - `static List<Param> parseStaticDefaultParams(Method method, List<Class<?>> hierarchy, RequestMeta requestMeta)`
  - `static String formatCookieHeader(Map<String, String> cookies)`
  - `static boolean checkHasContentTypeHeader(List<Header> headers)`
  - `static boolean checkIsConditional(List<Header> headers)`
  - `static CollectionFormat parseDefaultCollectionFormat(Method method, List<Class<?>> hierarchy)`
  - `static OnErrorParser parseOnErrorParser(Method method, List<Class<?>> hierarchy)`
  - `static Duration parseTimeout(Method method, List<Class<?>> hierarchy)`
  - `static MethodMetadata.ReturnTypeInfo parseReturnType(Type genericReturnType)`
  - `static MethodMetadata.EffectiveCompression parseCompression(Method method, List<Class<?>> hierarchy)`

### (D2) Transformation of `MethodMetadata` into a Pure Value Object
`MethodMetadata` retains all final fields, its compact records (`MethodKey`, `ReturnTypeInfo`, `EffectiveCompression`, `ParameterDescriptor`), its thread-safe cache `CACHE`, its factory `of(Method, Class<?>)`, and all accessor methods:
```java
static MethodMetadata of(Method method, Class<?> controller) {
    return CACHE.computeIfAbsent(new MethodKey(method, controller),
            key -> MethodMetadataParser.parse(key.method(), key.controllerClass()));
}
```
For binary and internal backward compatibility, `MethodMetadata.parse(Method, Class<?>)` is preserved as a one-line delegate to `MethodMetadataParser.parse(...)`.

Its constructor is made package-private so that `MethodMetadataParser` can directly instantiate the immutable model.

All 25 static parsing methods (724 lines) and 35 unused reflection imports were removed from `MethodMetadata`.

### (D3) Elimination of Dead Annotation Null-Guards
Under the Java Language Specification (JLS §9.7), annotation element values cannot be `null`. Several historical defensive guards checking for `null` on annotation values were removed:
- In `@Timeout`: removed `if (ann.unit() == null)` (in Java, default `TimeUnit.SECONDS` is guaranteed non-null).
- In `@RequestParam`: removed `rp.format() != null &&` (default `CollectionFormat.DEFAULT` is guaranteed non-null).
- In `@QueryCollectionFormat`: removed `format != null &&` (default `CollectionFormat.EXPLODE` is guaranteed non-null).
- In `@PathVariable`: simplified `isPathVar && pathVarName != null ? ...` to `isPathVar ? ...` since `pathVarName` is guaranteed non-null when `isPathVar` is true.

---

## 3. Verification & Metrics

- **TDD Red-First:** `MethodMetadataParserTest` written before implementation (66 compilation errors on missing symbol), featuring 20 comprehensive unit tests covering:
  - Private constructor reflection invocation (`UnsupportedOperationException`);
  - Full parse of rich controller method with all 14 parameter kinds, headers, cookies, listeners, and policies;
  - All HTTP verbs (`@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`) and missing verb fail-fast;
  - Diamond inheritance resolution (`BaseApi <- LeftApi, RightApi <- DiamondApi`);
  - Case-insensitive static header deduplication and ordering;
  - Cookie header formatting with blanks filtering and declaration ordering;
  - Content-Type and RFC conditional header detection;
  - Static default params merging from interface, verb annotation, and direct method annotations;
  - Collection format fallback (`QueryCollectionFormat(CollectionFormat.DEFAULT)` -> parent / `EXPLODE`);
  - Timeout validation (positive validation on method and interface levels);
  - Interceptors order, order annotation, deduplication, and request/response partitioning;
  - Metrics listeners deduplication and class filtering;
  - Compression negotiation (explicit, enabled, algorithm, minSizeBytes clamp);
  - Circuit breaker and rate limiter resolution and override semantics;
  - Return type info analysis (sync, async, optional, async-optional, raw-optional, streams, SSE);
  - OnError parser hierarchy and fallback parsing.

- **Collaborator Quality (ADR-0081 C11 Ratchet):**
  - **`MethodMetadataParser`:**
    * **Branches:** **334 / 334 covered (100.00%, 0 missed)**
    * **Complexity:** **200 / 200 covered (100.00%, 0 missed)**
    * **Lines:** **406 / 406 covered (100.00%, 0 missed)**
    * **Instructions:** **2,149 / 2,149 covered (100.00%, 0 missed)**
    * **Methods:** **33 / 33 covered (100.00%)**
    * **Classes:** **1 / 1 covered (100.00%)**

- **Scorecard Impact on `MethodMetadata` (Red to Green!):**
  - Physical Lines: **1,140 → 379** (−761 lines, **C1 Green ≤ 400!**)
  - WMC (Complexity): **257 → 49** (−208 complexity, **C2 Green ≤ 100!**)
  - Missed Branches: **28 → 0** (−28 missed branches, **100% covered!**)
  - Verdict: **GREEN** (exited the RED god-class list permanently).

- **Library-wide Metrics (Historic Absolute Records):**
  - **Branch Coverage:** 92.33% → **92.93%** (4,051 / 4,359 covered — **new all-time record**)
  - **Missed Branches:** 336 → **308** (−28 missed branches — **new all-time minimum**)
  - **Complexity Coverage:** 90.95% → **91.69%** (3,388 / 3,695 covered — **new all-time record**)
  - **Instruction Coverage:** 98.39% → **98.43%** (30,747 / 31,237 covered)
  - **Line Coverage:** 98.07% → **98.13%** (6,775 / 6,904 covered)
  - **Classes:** **159 / 159 (100.00%)**
  - **Total Tests:** 1,969 → **1,989 / 1,989** (root 1,825 = 1,805 + 20; submodules 164), 0 failures/errors
  - **Javadoc:** clean across all 8 modules under `-Werror`.
