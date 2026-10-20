# ADR-0086: `AnnotationChecks` — Shared Rules for Both Validation Passes

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.13.0 — structural wave 2 follow-up (ADR-0081), `ControllerValidator` C5 split

---

## 1. Context & Motivation

ADR-0081 left one named hot spot after the builder wave:
`ControllerValidator.validateMethod` was the **top method in the library by
cyclomatic complexity (121)**, far above the C5 red threshold of 20. The class
was 871 physical lines / WMC 279 / 10 missed branches, and the method carried
three unrelated jobs at once:

1. verb discovery and early exit;
2. path, parameter-name, payload and side-channel checks;
3. annotation policy checks (`@Timeout`, `@Compress`, `@Trace`,
   `@CircuitBreaker`, `@RateLimiter`, `@Retry`, `@SetAuthDetails`,
   `@ExpectStatus`, `@FollowRedirects`).

The annotation rules were also **duplicated**: `collectClassProblems` (the
interface pass) and `validateMethod` (the method pass) each re-implemented the
same declarations, media, error-mapping, interceptor and metrics checks. The
two copies already had to stay byte-for-byte identical — `ControllerValidator`
formats a bare prefix (`Api` or `get()`) into the same message. Any future fix
in one pass could silently drift from the other.

This ADR records the extraction prompted by ADR-0081's C5 target. It does not
introduce a feature; it removes duplicated rules and gives `validateMethod` a
readable orchestration shape.

## 2. Key Decisions

### Decision 1 — one stateless rule collaborator

`ru.jrestly.AnnotationChecks` is a package-private `final` class with a private
constructor, following the C11 collaborator pattern used by
`UrlComposer`/`HeaderComposer`/`BodyComposer`/`ResilienceComposer`:

| member | responsibility |
|---|---|
| `declarations` | non-blank `@RequestHeader` / `@RequestDefaultParam` names |
| `policies` | `@Timeout`, `@Compress`, `@Trace`, `@CircuitBreaker`, `@RateLimiter` |
| `media` | `@Accept`, `@ContentType`, `@Cookie`, `@QueryCollectionFormat`, conflicting `@OnError` status maps |
| `parameter` | one parameter: payload binding vs request type, illegal annotation combinations, CRLF, part names, dynamic URL type |
| `parameterCounts` | method-wide cardinality: at most one dynamic URL / side-channel hook / `@RequestBody`; no body on `@Get`; form/multipart payload presence |
| `interceptorsAndMetrics` | `@WithInterceptor` / `@WithMetrics` class checks |
| `hasHttpBindingAnnotation` (private) | the six HTTP parameter-binding annotations |

The class holds no mutable state, cache, or thread-local. It reads annotations
and appends problems in declaration order.

### Decision 2 — both passes call the same implementation

`collectClassProblems` now delegates directly:

```java
AnnotationChecks.declarations(current, prefix, problems);
AnnotationChecks.policies(current, prefix, problems);
AnnotationChecks.media(current, prefix, problems);
AnnotationChecks.interceptorsAndMetrics(current, prefix, problems);
```

`validateMethod` uses the same four rules plus the method-only
`validateVerbDefaultParams` and `validateMethodPolicies`. The old inline
blocks were deleted, not wrapped; no deprecated delegate remains.

`ControllerValidator` keeps the rules that need its own vocabulary or are
called directly by tests: `validateCompress`, `validateCircuitBreaker`,
`validateRateLimiter`, `validateRetry`, `validateExpectStatus`,
`validateFollowRedirects`, path/path-variable/parameter-name checks, and
interceptor/metrics class checks. `AnnotationChecks` calls those package-private
methods instead of duplicating their bodies.

### Decision 3 — `validateMethod` becomes orchestration

The method is split into small named steps:

| step | C5 complexity |
|---|---:|
| `validateVerbAnnotation` — exactly one HTTP verb, early exit | 8 |
| `validateVerbDefaultParams` — verb-level `params = @RequestDefaultParam` | 3 |
| `validateMethodPolicies` — `@Retry`, `@SetAuthDetails`, `@ExpectStatus`, `@FollowRedirects` | 6 |
| `validateMethod` — orchestration only | **3** |

`validateVerbAnnotation` returns `false` when no verb or multiple verbs are
present, so the remaining checks do not run on a method whose verb is invalid
(previous behavior: the same early return).

### Decision 4 — message and ordering pins stay byte-for-byte

The helpers take a **bare prefix** (`Api` for an interface, `get()` for a
method) and format `prefix + ": ..."` exactly as before. The ordering of
problems is preserved by invoking:

1. declarations → verb defaults → policies → method-only policies → media;
2. per-parameter checks in parameter order → method-wide counts →
   interceptors/metrics.

`ControllerValidationTest` (911 lines, black-box through `validate(...)`)
passes unchanged. This is the compatibility proof that the extraction did not
reorder or reword user-visible validation output.

### Decision 5 — the collaborator owns its spec

`AnnotationChecksTest` was written first against the not-yet-existing class
(compile-red: `cannot find symbol AnnotationChecks`), then completed until the
10-test suite was green. It pins the prefix shape, exact problem text,
multiplicity counts, side-channel rejections and the verb-default path through
the public `ControllerValidator.validate(...)`. Fixture corrections during
authoring (blank cookie/CRLF value, a duplicate `ResponseHeaders` fixture) were
test-data fixes, not expectation changes against the implementation.

## 3. Measurements (ADR-0081 scorecard)

Physical lines from `wc -l`; WMC, branches, and lines from the JaCoCo XML of
the green gate.

| class | C1 lines before → after | C2 WMC before → after | missed branches before → after |
|---|---:|---:|---:|
| `ControllerValidator` | 871 → **545** (−326) | 279 → **130** (−149) | 10 → **3** (−7) |
| `AnnotationChecks` (new) | — → **326** | — → **107** | — → **3** |

The **duplicated rules are counted once**, so the combined WMC fell from 279
to 237 (−42) even though the extracted class has its own Javadoc and helpers.
Library-wide branch slots fell from 4,450 to **4,360** (−90), and complexity
slots from 3,724 to **3,682** (−42): the extraction deleted the second copy of
every shared branch.

Gate totals: **1,913/1,913** tests (root 1,749 = 1,739 + 10; submodules 164),
0 failures/errors. JaCoCo: Branch 91.95% (4,009/4,360), Complexity 90.52%
(3,333/3,682), Instruction 98.37%, Line 98.05%, Class/Method 100%. The branch
percentage moves by −0.09 pp because 87 covered duplicate slots disappeared;
the missed-branch count itself fell from 354 to **351**.

`validateMethod` is now **3** (from 121), and its three largest remaining
collaborators are `parameter` (36), `media` (25) and `parameterCounts` (21) —
all cohesive rule tables rather than mixed orchestration.

## 4. Compatibility & Verification

* **Public API:** untouched — `AnnotationChecks` and every member are
  package-private.
* **Gates:** `./gradlew test` green across all eight modules; `:javadoc` clean
  under `-Werror`.
* **Behavior:** `ControllerValidationTest` passes without edits; every message
  and problem order is unchanged.
* **Allocation:** no new collection on the validation path; the collaborator
  adds only static calls. Class-level validation allocates the same `problems`
  list and the same `LinkedHashMap` for duplicate `@OnError` statuses.
* **Targeted suites:** `AnnotationChecksTest` (10), `ControllerValidationTest`,
  `ControllerValidationEdgeCaseTest`, and the full annotation/validation
  matrix — all green.

## 5. Consequences

* The ADR-0081 navigation map now points annotation validation at
  `annotation/*`, `ControllerValidator` (orchestration) and
  `AnnotationChecks` (shared rules).
* The last named C5 red method from ADR-0081 is closed; no method in
  `ControllerValidator` exceeds complexity 13.
* `AnnotationChecks` joins the green reference collaborators; future rules for
  both passes have one obvious home.
* Next candidates from ADR-0081: reassess the remaining `HttpTemplate` /
  `MethodMetadata` complexity, and keep the release-gate scorecard current.
* Next free ADR: **0087**.
