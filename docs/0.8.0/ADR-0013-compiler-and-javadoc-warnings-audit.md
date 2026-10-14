# ADR-0013: Compiler and javadoc warnings — audit and scope

- **Status:** accepted
- **Date:** 2026-10-13 (session #41)
- **Scope:** post-0.7.0-cycle cleanup; the fixes ship with the next release.
  The `docs/0.8.0/` folder is created at the epic's opening per the DRAFT
  plan ("ADR-0013 in the next cycle's docs/ folder"); the version label is
  provisional until the next cycle is opened (candidate #1 in the DRAFT
  free-mode zone, deferred since session #30)
- **Nature:** cleanup without new public surface — every fix lands on existing
  classes, seams and build files

## Context

The post-0.7.0 DRAFT kept one live candidate: a warnings / refactoring audit
("compilation warnings, Java 21 idioms in old fragments, test polish; not a
release cycle — pure cleanup without new surface"). The candidate noted that
IDE inspections are unavailable in the agent environment — the audit is
bounded to `gradle`/`javac`/`javadoc` output.

This session is the audit itself: measure everything, look at every site,
decide the scope. The fixes are the following steps of this epic.

Measurement (all with `--rerun` on the warm cache, branch `agent-loop`,
HEAD = the session #40 commit):

- `compileJava` / `compileTestJava` with `-Xlint:all` (via an init script —
  the default build has **zero** compiler warnings, because `-Xlint` is not
  enabled anywhere): **9 in main, 3 in test**.
- `javadoc` — **100 warnings**, both with the default doclint and with an
  explicit `-Xdoclint=all`; the breakdown is 61 `no comment`, 24
  `no @param` (incl. 1 `no @param for <T>`), 14 `no @return`, 1
  `use of default constructor`. The default build passes them (no
  `-Werror`), so they are noise today, not a gate.
- Full `./gradlew build --rerun`: green, 0 warnings under the default
  configuration (javadoc up-to-date from the run above).
- Java 21 idioms scan of `src/main`: no legacy fragments found — pattern
  matching `instanceof` is used everywhere (17 sites), arrow-form switch in
  `MoshiCodec.copyValue` / `JacksonCodec`, 7 records in `ru.jrestly.http`.
  Zero `var` — a style preference, deliberately not enforced (below).
  Exactly one IDE-only `//noinspection` in main code
  (`HandledException.getDetails`) — invisible to `javac`.
- Hot-path allocation scan: no new findings — the known fixes stand
  (`BODY_LOGGING_METHODS` as a static `Set`, shared `RetryPolicy.OFF`
  constant, no per-request `Arrays.asList`).

## Findings

### Compiler warnings (12)

Main (9):

| Site | Warning | Substance |
|---|---|---|
| `ControllerValidationException` | `[serial]` ×2 | no `serialVersionUID`; `Class<?> controller` field is non-serializable |
| `HandledException` | `[serial]` ×3 + `[unchecked]` | no `serialVersionUID`; `Object details` (an arbitrary user DTO) and `List<Header>` (the `Header` record is not `Serializable`) non-transient; the unchecked cast in `getDetails()` is suppressed only by an IDE-only `//noinspection` |
| `UnexpectedStatusException` | `[serial]` | no `serialVersionUID` |
| `AbstractHttpClient:63` | `[rawtypes]` | `new Class[] {classInterface}` — the proxy array is declared raw |
| `AbstractHttpClient:27` | `[this-escape]` | protected hook `createConnection()` called from the constructor; no override in the repo, but the hook is part of the public abstract class's API |

Test (3):

| Site | Warning | Substance |
|---|---|---|
| `TestModuleInfo:27` | `[unchecked]` | the override `Environment<Header> getEnvironment()` does not carry the interface's `<T>` parameter |
| `RetryAsyncTest:131` | `[unchecked]` | the private `causeOf` helper's `(T) exception.getCause()` cast — no suppression |
| `CompletableFutureReturnTest:191` | `[unchecked]` | the same helper, copy-pasted |

Test-polish note: the `causeOf` helper is duplicated verbatim in two test
classes (3 call sites each).

### Javadoc warnings (100)

All in `src/main` (the `javadoc` task documents main sources only). By file:
`HttpTemplate` 17, `AbstractHttpClient` 15, `FileContent` 11,
`WireRequest` 7, `HttpMethod` 6 (the enum and its five constants have no
comments at all), `MultipartPart` 5, `HandledException` 5, `AuthProvider` 5,
`Environment` 4, `ControllerValidationException` 4, `WireResponse` 3,
`AuthHeaderProvider` 3, `Delete` 3, `GsonCodec` 2, `Param` 2, `Header` 2,
`ControllerValidator` 2, `Get` 2, `FollowRedirects` 1, `ExpectStatus` 1.

**Session #44 (re-measure) — the "100" is a floor, not a total.** The
javadoc tool caps warnings per run at 100 (`Log.defaultMaxWarnings`);
without `-Xmaxwarns` the count saturates at exactly 100, which the audit
mistook for the total. With `-Xmaxwarns 999` the true audit baseline is
**237 warnings across 44 files** — the table above is the tool's first
100, still a valid gate snapshot (re-measure, don't re-read — see
Consequences). Re-measurement after step 4 (session #45): **173
remaining**; every step-4 surface (the three exceptions, `AuthProvider`,
`Environment`, `FileContent`, `MultipartPart`, the five verb annotations)
is at 0.

The bulk is `no comment` on public/protected members (the documented
contract surfaces — `JsonCodec`, `RetryPolicy`, `@Retry`, `ModuleInfo`, the
codec adapters — are already normatively documented and stay out of this
list) plus missing `@param`/`@return` on the wire-model records,
`FileContent`'s factories and the HTTP-verb annotations' attributes.

## Decisions

**D1 — Every compiler warning is fixed; suppression is targeted, never
blanket, and always carries a one-line rationale.**

- **Serial (the three exception classes):** `@Serial private static final
  long serialVersionUID = 1L` on each, plus a class-level
  `@SuppressWarnings("serial")` with the rationale: the exceptions are not
  part of the wire contract, and `details` / `controller` are arbitrary user
  values whose serializability the library cannot promise. `Header` is left
  as-is — making the public record `Serializable` would be an API change
  without a caller asking for it (0.x discipline).
- **`[rawtypes]` (`AbstractHttpClient:63`):** `new Class[]` →
  `new Class<?>[]`. One line.
- **`[this-escape]` (`AbstractHttpClient:27`):** the hook stays (a
  protected method on a public abstract class is API — external subclasses
  exist by the class's own contract); a targeted
  `@SuppressWarnings("this-escape")` with the rationale (constructor-time
  connection creation is the hook's purpose). Session #42 (the fix step):
  javac reports the warning at the **escape site** — the constructor's
  `createConnection()` call — not inside the called hook, so the suppression
  lands on the constructor; the hook stays annotation-free.
- **`[unchecked]` (`HandledException.getDetails`):** the IDE-only
  `//noinspection` is replaced by the javac-visible
  `@SuppressWarnings("unchecked")` (house idiom — the same shape as
  `AbstractHttpClient.get`).
- **Test overrides:** `TestModuleInfo.getEnvironment` matches the interface's
  `<T>` signature (fixture field is `Environment<Header>`; the cast sits
  behind the method-level suppression); the two `causeOf` helpers are
  collapsed into one shared helper in `ru.jrestly.fixtures` with a single
  `@SuppressWarnings("unchecked")` — test polish, no behavior change.
  Session #43 (the fix step): the helper is `TestFutures.causeOf` — a
  `public static` on a final utility class (a private one would not reach
  the two test classes across class boundaries).

**D2 — All 100 javadoc warnings are fixed, and the build starts enforcing
them.** The cleanup is permanent only if a gate keeps it: `build.gradle`
gains `javadoc { options.addStringOption('Xdoclint', 'all');
options.addBooleanOption('Werror', true) }`. `javadocJar` already runs in
every `build`, so the gate adds no new step — it turns today's noise into a
contract: every new public/protected member ships with a comment. The javadoc
task covers main sources only; test sources are out of scope.

Session #42 — the `-Werror` question (asked in the mailbox, session #41) was
delegated back to the agent ("continue as you see fit"). Decision: **keep
`-Werror`**. Rationale: `javadocJar` already runs in every `build`, so the
gate costs no extra step; the payoff is permanence — a public member without
a comment fails the build instead of decaying back into noise. Reverting is
one line in `build.gradle` if it ever proves too strict for 0.x.

Session #45 — the option form above is a trap: `addStringOption('Xdoclint',
'all')` writes **two** arguments (`-Xdoclint` + `all`), and the javadoc tool
parses the bare `all` as a package name — the task dies with
"No source files for package all". The gate (step 6) uses the
single-argument form `options.addStringOption('Xdoclint:all')`, which the
tool accepts as one option (verified directly: `-Xdoclint all` fails,
`-Xdoclint:all` works).

**D3 — Java 21 idioms: no legacy fragments exist; two build-level
modernizations instead.**

- `compileJava` / `compileTestJava` switch from
  `sourceCompatibility`/`targetCompatibility = 21` to
  `options.release = 21` — the same target, but it also checks calls against
  the JDK 21 API on newer toolchains (no accidental JDK-22 API in a 21
  library). The `-parameters` arguments stay.
- `var` is deliberately **not** enforced — a preference, not a warning;
  forcing it would be churn without a finding.

**D4 — Hot path: no new findings.** The audit re-checked the per-request
path (`HttpTemplateBuilder.build`, `HttpTemplate.exchange`, the retry
loops); the earlier fixes (static `Set` for the log gate, shared `OFF`
policy constant) cover it.

**Non-goals (recorded, not silently skipped):** IDE inspections (not
available in the agent environment — `javac`/`javadoc` only); unused-import
detection (`javac` does not report it); test-source javadoc; the Gradle
toolchain (the source/target pin is deliberate — no auto-provisioning).

## Consequences

- The epic is six steps (this concept/audit step is done):
  1. `[x]` audit + scope (this ADR, session #41);
  2. main code + build: the five main-code warning sites + `release = 21`;
  3. test code: the `TestModuleInfo` override + the shared `causeOf` helper;
  4. javadoc, normative surface: the three exceptions, `AuthProvider`,
     `Environment`, `FileContent`, `MultipartPart`, the verb annotations'
     `@return`;
  5. javadoc, the rest: `HttpTemplate`, `AbstractHttpClient`, the wire-model
     records, `HttpMethod`, `AuthHeaderProvider`, `ControllerValidator`,
     `GsonCodec`, the remainder;
  6. gate: `-Xdoclint=all` + `-Werror` in `build.gradle`, a full re-audit
     (`-Xlint:all` → 0 javac warnings, `javadoc` → 0), distillation.
- After the epic: `./gradlew build` is warning-free under the default
  configuration **and** under lint. The `-Xlint` follow-up (deliberately
  separate from this audit) is done — session #59: `-Xlint:all -Werror` on
  both `compileJava` and `compileTestJava`, mirroring the javadoc gate's
  shape. The audit's seven suppressions (five main: serial ×3, this-escape,
  unchecked; two test: unchecked ×2) are forgiven by the gate by design —
  each already carries a one-line rationale. Negative-tested on both sides:
  a rawtypes probe fails `compileJava` and `compileTestJava` respectively
  ("warnings found and -Werror specified"); with the probe removed the
  build is green (256/256).
- The per-file javadoc table above is the work list for steps 4–5; it is a
  gate snapshot (session #41, `-Xdoclint=all`), to be re-measured, not
  re-read, by the step that touches it.
