# ADR-0017: Request parameter & header flexibility

> 2026-10-14 · Proposed · concept step of the "Request parameter & header flexibility"
> epic (DRAFT, 6 steps). Implementation follows in Steps 2–5; this ADR is the
> source of truth for design decisions, edge-case contracts, and validation rules.

## Context

Following the response byte streaming epic (ADR-0015) and Server-Sent Events / line streaming
epic (ADR-0016), this is the third ergonomics epic addressing direction 3 from the maintainer's
2026-10-14 mailbox ("request parameter flexibility, data streaming, LLM, request pipeline tuning").

In modern web API integration and test automation, declarative HTTP clients must handle real-world
HTTP parameter patterns gracefully:
1. **Optional / nullable parameters & headers:** real-world APIs have optional query parameters
   (e.g., search filters, pagination offsets) and optional headers (e.g., correlation IDs, trace
   context). Passing `null` to a `@RequestHeader` currently results in a `NullPointerException`
   (`args[i].toString()`). Passing a collection with `null` items to `@RequestParam` crashes with NPE.
   Passing `null` to `@PathVariable` causes awkward URI replacement or NPE.
2. **Dynamic / open-ended query parameters and headers (`Map<String, ?>`):** search endpoints,
   dynamic filter sets, and custom metadata APIs accept arbitrary query parameters or headers.
   Currently, passing a `Map` to `@RequestParam` stringifies the map into `paramName={a=1, b=2}`,
   which corrupts the HTTP request line.
3. **Class-level and method-level static headers:** services typically require common headers
   across all endpoints (e.g., `Accept: application/json`, `X-Api-Key: secret`, `User-Agent: app/1.0`).
   Currently, `@RequestHeader` is only applicable to method parameters (`ElementType.PARAMETER`),
   forcing developers to repeat headers as arguments on every method or misuse `AuthProvider`
   (which is intended for dynamic authentication lifecycle, not static client headers).
4. **Class-level default query parameters:** multi-tenant or versioned APIs often require a static
   query parameter across all endpoints (e.g., `api_version=2024-01-01`). Currently,
   `@RequestDefaultParam` is only supported inside the method verb annotation's `params()` array
   (`@Get(params = ...)`), forcing repetition across every method of an interface.

### Constraints & Invariants (from Direction 1)

1. **Zero-dep core:** JDK 21 standard library only (`java.lang.reflect`, `java.util`,
   `java.lang.annotation`). No external reflection tools, no bytecode manipulation.
2. **Transparent methods without `Response<T>` (ADR-0004):** parameter bindings remain transparent
   and declarative directly on controller interface declarations.
3. **Anti-combination (ADR-0003):** no heavyweight annotation processing or DI container coupling;
   resolution is performed cleanly at proxy dispatch time inside `HttpTemplateBuilder`.
4. **Strict backward compatibility:** existing controller interfaces using `@RequestParam`,
   `@RequestHeader`, `@PathVariable`, and `@RequestDefaultParam` must behave identically without
   requiring any migration.

---

## Decisions

### Aspect 1: Nullable parameters & graceful omission

- **`@RequestHeader` parameter:**
  - If the parameter value is `null`, the header is **omitted** from the HTTP request.
  - If the parameter value is a `Collection<?>`, `null` elements are skipped. If the collection is empty
    or contains only `null`s, no header is emitted.
  - If the parameter value is a `Map<?, ?>`, entries with `null` keys or `null` values are skipped.
- **`@RequestParam` parameter:**
  - If the parameter value is `null`, the parameter is **omitted** (already existing behavior for
    scalar arguments, now formalized across all types).
  - If the parameter value is a `Collection<?>`, `null` elements are skipped (prevents NPE in
    `o.toString()`).
  - If the parameter value is a `Map<?, ?>`, entries with `null` values are skipped.
- **`@PathVariable` parameter:**
  - A path variable cannot be omitted from a URL template. If the argument value is `null`,
    `HttpTemplateBuilder` throws `IllegalArgumentException("Path variable '${name}' value cannot be null")`
    with an informative error message instead of an opaque NPE.

### Aspect 2: Dynamic Map parameters (`@RequestParam Map<?, ?>` & `@RequestHeader Map<?, ?>`)

- **`@RequestParam Map<?, ?>`:**
  - When an argument annotated with `@RequestParam` implements `Map<?, ?>`:
    - Each map entry is treated as an individual query parameter (or form field / multipart text part).
    - If `@RequestParam.name()` is empty (the default), the map key `String.valueOf(entry.getKey())`
      becomes the query parameter name.
    - If `@RequestParam.name()` is specified (e.g., `@RequestParam(name = "filter") Map<String, Object>`),
      the parameter name is prefixed: `filter[key]`.
    - If an entry's value is a `Collection<?>`, it expands into multiple parameters with the same name:
      `key=val1&key=val2`.
    - `null` values are omitted per Aspect 1.
- **`@RequestHeader Map<?, ?>`:**
  - When an argument annotated with `@RequestHeader` implements `Map<?, ?>`:
    - Each map entry becomes an HTTP request header: name is `String.valueOf(entry.getKey())`.
    - If the value is a `Collection<?>`, each non-null element creates a header entry with that name.
    - `null` values and `null` keys are omitted per Aspect 1.

### Aspect 3: Class-level and method-level static `@RequestHeader`

- **Annotation definition:**
  - Target expanded: `@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})`.
  - Made repeatable: `@Repeatable(RequestHeaders.class)`.
  - Added `String value() default ""` attribute:
    - On classes and methods: `name` specifies the header name, `value` specifies the static header value.
    - On parameters: `value` is ignored; the runtime argument provides the value.
  - Added `String name() default ""` attribute:
    - On parameters: an empty `name()` defaults to the Java parameter name (matching the ergonomics
      of `@RequestParam` and `@PathVariable`).
    - On classes and methods: `name()` is required and must not be blank (validated by `ControllerValidator`).
- **Container annotation:**
  - `public @interface RequestHeaders { RequestHeader[] value(); }` with
    `@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})`.

### Aspect 4: Class-level `@RequestDefaultParam`

- **Annotation definition:**
  - Target expanded: `@Target({ElementType.METHOD, ElementType.TYPE})`.
  - Made repeatable: `@Repeatable(RequestDefaultParams.class)`.
- **Container annotation:**
  - `public @interface RequestDefaultParams { RequestDefaultParam[] value(); }` with
    `@Target({ElementType.METHOD, ElementType.TYPE})`.
- **Behavior:**
  - `@RequestDefaultParam` placed directly on a controller interface applies to every HTTP method
    in that interface (and sub-interfaces).

### Aspect 5: Precedence, Merging, and Validation

- **Headers merge order:**
  1. Module `AuthProvider` headers (lowest precedence).
  2. Class-level `@RequestHeader` annotations on controller interface hierarchy (parent interfaces first,
     sub-interface wins).
  3. Method-level `@RequestHeader` annotations.
  4. Method parameter `@RequestHeader` arguments (highest precedence).
  - *Override semantics:* if a method-level header or parameter-level header specifies the same header name
    (case-insensitive comparison per RFC 9110), it **replaces** the static class-level header with that name.
    This allows a specific method or call site to override a general client header (e.g., overriding
    `Accept: application/json` with `Accept: text/plain` or setting a specific `X-Api-Key`).
- **Default query parameters merge order:**
  1. Class-level `@RequestDefaultParam` annotations.
  2. Method-level default parameters (from verb annotation `params()` or direct `@RequestDefaultParam`).
  3. Dynamic `@RequestParam` arguments.
  - *Override semantics:* if a parameter specifies the same parameter name as a default parameter,
    the parameter argument **replaces** the default parameter, avoiding duplicate query keys when
    an explicit override is intended.
- **`ControllerValidator` additions:**
  - Verify that any `@RequestHeader` placed on a class or method has a non-blank `name()`.
  - Verify that any `@RequestDefaultParam` placed on a class or method has a non-blank `name()`.

---

## Plan & Steps in DRAFT

1. `[x]` **Step 1: Concept session (ADR-0017 + DRAFT epic setup)** — this document.
2. `[ ]` **Step 2: Nullable parameters & headers (TDD)** — omit null `@RequestHeader`, skip null elements in collections, reject null `@PathVariable` with informative IAE.
3. `[ ]` **Step 3: Dynamic Map parameters (TDD)** — support `Map<?, ?>` in `@RequestParam` (query, form, multipart) and `@RequestHeader`.
4. `[ ]` **Step 4: Class-level and method-level `@RequestHeader` & `@RequestDefaultParam` (TDD)** — annotation expansion, repeatable containers, inheritance & override mechanics in `HttpTemplateBuilder`, validation in `ControllerValidator`.
5. `[ ]` **Step 5: Documentation, WireMock end-to-end tests & README** — comprehensive test suite, complete Javadoc, README section.
6. `[ ]` **Step 6: Distillation into ROADMAP.md & DRAFT cleanup** — update ROADMAP, clean DRAFT active zone.

## Amendment #212

**Missing eager validation of the parameter-name fallback — without the `-parameters` compiler flag a blank `name()` silently binds to `arg0`.**

A blank `name()` on a parameter-level binding falls back to the Java parameter name (this ADR, Aspect 2/3; `@PathVariable` — ADR-0025; `@Cookie` — ADR-0032): `MethodMetadata.parse` resolves `name().isEmpty() ? parameter.getName() : name()`. The compiler only retains parameter names under `-parameters` (README states the requirement), and without the flag `Parameter.getName()` returns a synthetic `arg0`/`arg1`/… — so:

- **`@RequestParam` / `@RequestHeader` / `@Cookie`** — the query key, header name, or cookie name silently becomes `arg0`; the value is sent under the wrong name and the server ignores it. No error, no log line.
- **`@PathVariable`** — the synthetic name never matches a `${...}` placeholder, so the mismatch check of Amendment #210 fired with a bare, confusing `arg0` note instead of naming the real cause.

A `Map` parameter (and an `HttpCookie`) takes its names from the entries/instance rather than the parameter name, so a blank `name()` there is legitimate and is not flagged.

Fix: `ControllerValidator.validateParameterNames` reports, for every parameter-level binding whose blank `name()` resolves from the parameter name, when `Parameter.isNamePresent()` is false:

- `method(): @RequestParam requires an explicit name() when the controller is compiled without '-parameters' (parameter names are not retained at runtime)`
- (the same for `@RequestHeader`, `@Cookie`, `@PathVariable`)

`@PathVariable` parameters whose fallback name is unavailable are also left out of the Amendment #210 mismatch set, so only the actionable message remains. No public API change; a controller compiled with `-parameters` (the documented requirement) behaves exactly as before. Covered by `ControllerValidationTest.rejectsParameterNameFallbackWithoutParametersFlagEagerly` and `reportsUnnamedPathVariableWithoutParametersFlagActionably` (red-first; the throwaway controller is compiled at runtime both with and without the flag to prove the guard keys on the flag, not on the declarations).
