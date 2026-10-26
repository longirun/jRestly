# ADR-0008: Request Parameter Flexibility — Static Headers & Default Params (0.7.0 cycle, part 2)

- **Status:** accepted
- **Date:** 2026-10-26
- **Scope:** FEAT-05 of the 0.7.0 cycle (TODO.md §1)
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0017 Aspects 3–5 with Amendment #212

## Context

With 0.6.1 (Aspect 1, ADR-0006) and ADR-0007 (Aspect 2), the parameter-flexibility epic has one remaining gap: **static declarations**. Services typically require common headers across all endpoints (`Accept`, `X-Api-Key`, `User-Agent`) and static query parameters for multi-tenant or versioned APIs (`api_version=2024-01-01`). Today `@RequestHeader` is `PARAMETER`-only and `@RequestDefaultParam` is only available inside the verb annotation's `params()` array — both force repetition on every method or misuse `AuthProvider` (meant for dynamic auth lifecycle, not static client headers).

**Goal:** class-level and method-level repeatable `@RequestHeader` / `@RequestDefaultParam` with hierarchy inheritance and a defined precedence merge; eager validation of blank names. Strict backward compatibility (existing `PARAMETER`-only / `params()`-only controllers behave identically).

---

## Decisions

### 1. Annotation surface

- `@RequestHeader`: targets expanded to `{PARAMETER, METHOD, TYPE}`, made repeatable via `@Repeatable(RequestHeaders.class)`, new `name() default ""` (parameter-name fallback on parameters; required non-blank on methods and types) and `value() default ""` (static value; ignored on parameters).
- `@RequestDefaultParam`: targets expanded to `{METHOD, TYPE}`, made repeatable via `@Repeatable(RequestDefaultParams.class)`.
- New container annotations `RequestHeaders` and `RequestDefaultParams` (checked out from the reference).

### 2. Header merge order (per invocation)

1. Module `AuthProvider` headers (lowest precedence).
2. Class-level `@RequestHeader` across the controller interface hierarchy — parents first, a sub-interface declaration replaces the parent one.
3. Method-level `@RequestHeader`.
4. Parameter `@RequestHeader` arguments (highest).

Name comparison is case-insensitive per RFC 9110, and a same-name declaration **replaces** the earlier one rather than appending — the wire never carries duplicate header names from the merge. A `null` parameter argument skips the header (ADR-0006) while a static declaration with the same name stays in place.

### 3. Default parameter merge

1. Class-level `@RequestDefaultParam` across the hierarchy.
2. Verb annotation `params()` and method-level `@RequestDefaultParam` (exact-name replace).
3. Dynamic `@RequestParam` arguments replace a default with the same name (exact, case-sensitive — query keys are case-sensitive).

A `null` scalar argument falls back to the default. Bare-`Map` keys collide with defaults by their flat name (`size` replaces `size`); prefixed entries (`f[size]`) are distinct names and never touch defaults.

### 4. `ControllerValidator` additions

- Any `@RequestHeader` or `@RequestDefaultParam` on a class or method with a blank `name()` produces a validation problem at proxy creation.
- Amendment #212: a parameter-level binding (`@RequestParam`, `@RequestHeader`, `@PathVariable`) with a blank `name()` whose fallback is unavailable (`Parameter.isNamePresent() == false`, i.e. compiled without `-parameters`) produces an actionable problem naming the annotation, instead of silently sending `arg0` on the wire. `Map` parameters are exempt — their names come from the entries.

### 5. Deviations from the reference

- The reference resolves static declarations once into `MethodMetadata` at parse time; `main` recomputes the merge per `build()` inside `HttpTemplateBuilder`, because `MethodMetadata` caching is FEAT-34 and is not ported yet. Semantics are identical; the per-invocation resolution cost is accepted transitively until FEAT-34/35 land.
- The Amendment #212 `@Cookie` variant is not ported — `@Cookie` does not exist in `main` yet (arrives with FEAT-31).

---

## Verification Gates

1. **WireMock suite green:** 180 tests, including new `StaticHeadersAndParamsTest` (10 tests: static header sent, repeatable declarations, inheritance with case-insensitive override and preserved defaults, parameter override with null-fallback, method-level default override, `Map`-vs-default precedence for bare and prefixed keys, urlencoded defaults, auth-header merge, static-wins-over-ambient) and `ControllerValidationTest` (+2: blank static names, Amendment #212 with runtime compilation both with and without `-parameters`).
2. **IDE inspections clean.**
3. **Zero runtime dependencies in the generated POM.**
