# ADR-0085: `ResilienceComposer` — the Resilience Slice of the Builder Wave

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.13.0 — structural wave 2 (ADR-0081), step (d): resilience wiring

---

## 1. Context & Motivation

ADR-0081 queued a four-step decomposition of `HttpTemplateBuilder`: URI
assembly, header/cookie merge, body/multipart assembly, resilience wiring.
ADR-0082 recorded step (a), ADR-0083 step (b), ADR-0084 step (c). This ADR
records **step (d)**, the last slice of the wave.

After step (c) the builder was 850 physical lines / WMC 205 / 66 missed
branches. The remaining resilience concern was cohesive but spread across the
class:

1. `resolveRetryPolicy` — method-level `@Retry` replaces the client-level
   policy; otherwise the module policy applies;
2. `resolveTimeout` — the per-method `@Timeout`, or unset;
3. `resolveCircuitBreaker` / `getCircuitBreakerFromAnnotation` — enabled
   annotation wins, disabled annotation opts out, otherwise the client-level
   breaker applies; the annotation config is materialised through the module
   registry (or the static default registry);
4. `resolveRateLimiter` / `getRateLimiterFromAnnotation` — the same precedence
   and registry rules for `@RateLimiter`.

Every input is already in scope at `build()` (`ModuleInfo`, `MethodMetadata`).
The engine-options record assembly (`HttpEngineOptions`) deliberately stays in
the builder: it is a data transfer of already-resolved values, not a policy
resolution.

## 2. Key Decisions

### Decision 1 — one stateless home

`ru.jrestly.http.ResilienceComposer` is a package-private `final` class with a
private constructor, following `UrlComposer` (ADR-0082), `HeaderComposer`
(ADR-0083), `BodyComposer` (ADR-0084) and the C11 collaborator pattern:

| member | moved from | responsibility |
|---|---|---|
| `retryPolicy(ModuleInfo, MethodMetadata)` | builder `resolveRetryPolicy` | method `@Retry` wins; otherwise the client-level policy |
| `timeout(MethodMetadata)` | builder `resolveTimeout` | declared `@Timeout` duration, or `null` |
| `circuitBreaker(ModuleInfo, MethodMetadata)` | builder `resolveCircuitBreaker` | annotation precedence, disabled opt-out, client fallback |
| `rateLimiter(ModuleInfo, MethodMetadata)` | builder `resolveRateLimiter` | annotation precedence, disabled opt-out, client fallback |
| `circuitBreakerFromAnnotation` / `rateLimiterFromAnnotation` (private) | builder `get*FromAnnotation` | config mapping plus registry lookup and `getOrCreate` |

The two static default registries (`CircuitBreakerRegistry.ofDefaults()` and
`RateLimiterRegistry.ofDefaults()`) moved with the annotation mappers. The
class holds no mutable fields, cache, or thread-local.

### Decision 2 — seams move, they do not duplicate

The old members were **deleted**, not wrapped. `HttpTemplateBuilder.build`
now reads:

```java
result.setRetryPolicy(ResilienceComposer.retryPolicy(moduleInfo, methodMetadata));
result.setTimeout(ResilienceComposer.timeout(methodMetadata));
result.setCircuitBreaker(ResilienceComposer.circuitBreaker(moduleInfo, methodMetadata));
result.setRateLimiter(ResilienceComposer.rateLimiter(moduleInfo, methodMetadata));
```

No deprecated delegate remains. `RetryPolicy` stays public in the `http`
package; the composer returns the same instances rather than copying them, so
client-level policies keep their identity (`assertSame` pins this).

### Decision 3 — invariants replace unreachable guards

`build()` dereferences `moduleInfo.getEnvironment()` while composing the URL
(before any resilience call), so `moduleInfo` is non-null at every collaborator
call site. The old `moduleInfo != null` guards on the circuit-breaker and
rate-limiter fallbacks were therefore unreachable and disappeared (−2 branch
slots). `SimpleModuleInfo` also normalises null registries to fresh defaults in
its own `build()`, so the static-registry fallback remains only for custom
`ModuleInfo` implementations that keep the interface's `null` registry
defaults — it is still specified by a test.

The `methodMetadata != null` checks are **kept**: `MethodMetadata` may be null
for methods without HTTP metadata, and the collaborator is directly testable
with `null`.

### Decision 4 — behavior pins stay byte-for-byte

* a method-level `@Retry` replaces the client-level policy entirely, including
  its attempt count; no merge;
* `@Timeout` resolves to the annotated `Duration`, and to `null` when absent;
* an enabled `@CircuitBreaker`/`@RateLimiter` annotation wins over a
  client-level instance and builds its config from the annotation values;
* a disabled annotation (`enabled = false`) returns `null` even when the client
  configures a breaker/limiter — declarative opt-out wins;
* without an annotation the client-level breaker/limiter is returned unchanged
  (identity preserved);
* annotation names are cached in the module registry; with no module registry,
  the static default registry is used and the same name yields the same
  instance (`assertSame`).

### Decision 5 — the collaborator owns its spec

`ResilienceComposerTest` was written first against the not-yet-existing class
(compile-red: `cannot find symbol ResilienceComposer`), then completed until the
collaborator reached **22/22 branches, 38/38 lines, 18/18 complexity,
122/122 instructions, 0 missed** — the C11 bar. The suite has **13 tests**.
The fixtures declare `@Get` on every method because `MethodMetadata.parse`
requires an HTTP verb; that was a fixture correction, not an expectation
change against the implementation.

## 3. Measurements (ADR-0081 scorecard)

Physical lines from `wc -l`; WMC, branches, and lines from the JaCoCo XML of
the green gate.

| class | C1 lines before → after | C2 WMC before → after | missed branches before → after |
|---|---:|---:|---:|
| `http.HttpTemplateBuilder` | 850 → **779** (−71) | 205 → **186** (−19) | 66 → **56** (−10) |
| `http.ResilienceComposer` (new) | — → **124** | — → **18** | — → **0** |

The builder now has 293 branch slots (237 covered + 56 missed). The library-wide
branch universe shrank from 4,458 to 4,450 (−8 net) even while the new class
added 22 slots. Library totals for the gate: **1,903/1,903** tests (root 1,739 =
1,726 + 13; submodules 164), 0 failures/errors. JaCoCo: **Branch 92.04%
(4,096/4,450) — new record**, **Complexity 90.55% (3,372/3,724) — new record**,
Instruction 98.37%, Line 98.05%, Class/Method 100%.

The line drop exceeds the six removed methods because the builder also lost two
static registry fields and six resilience imports; the composer is intentionally
more documented than the inline code it replaces.

## 4. Compatibility & Verification

* **Public API:** untouched — class and every member are package-private.
* **Gates:** `./gradlew test` green across all eight modules; `:javadoc` clean
  under `-Werror`.
* **Allocation:** no new collections on the hot path; four direct static calls
  replace four instance calls; no extra allocation for the common
  client-level fallback path.
* **Targeted suites:** `ResilienceComposerTest` (13), `HttpTemplateBuilderTest`,
  `CircuitBreakerTest`, `RateLimiterTest`, `RetryPolicy*`, `Timeout*`, and the
  WireMock resilience suites — all green after the call-site migration.

## 5. Consequences

* The ADR-0081 navigation map points resilience wiring at
  `http/ResilienceComposer`; the policies themselves stay in `resilience/*`.
* **Structural wave 2 is complete.** The builder fell from 1,516 lines / WMC
  407 / 110 missed branches (ADR-0081 baseline) to 779 / 186 / 56 — a 49% line
  reduction and a 54% WMC reduction with the public API intact.
* `ResilienceComposer` joins `UrlComposer`, `HeaderComposer`, and `BodyComposer`
  as a green reference collaborator.
* Next candidates from ADR-0081: split `ControllerValidator.validateMethod`
  (C5 target ≤ 20), then reassess the remaining builder complexity.
* Next free ADR: **0086**.
