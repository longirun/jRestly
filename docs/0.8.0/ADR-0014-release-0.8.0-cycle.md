# ADR-0014: The 0.8.0 cycle — packaging the warnings & modernization set

- **Status:** accepted
- **Date:** 2026-10-14 (session #60)
- **Scope:** release cycle 0.8.0 (version bump from 0.7.0)
- **Companion ADR of the cycle:** ADR-0013 (this directory)

## Context

Since 0.7.0 — itself unpublished: no git tag, the version in `build.gradle`
and the release commit message only (ADR-0012 Decision 2) — the branch has
accumulated exactly one unreleased set: the warnings & modernization epic
(ADR-0013, sessions #41–#58) plus its deliberate one-line follow-up, the
`-Xlint:all -Werror` gate on both compile tasks (session #59). ADR-0013's
scope names the destination explicitly — "the fixes ship with the next
release" — and the `docs/0.8.0/` folder has carried the provisional 0.8.0
label since the epic's opening.

What the release ships (verified against the tree, session #60):

- the twelve compiler warnings fixed (9 main / 3 test; suppression targeted,
  each carrying a one-line rationale — ADR-0013 D1);
- the 237 javadoc warnings fixed — the whole normative main surface
  documented; the shipped `javadocJar` goes from mostly undocumented to
  normatively complete;
- `options.release = 21` replacing `sourceCompatibility` /
  `targetCompatibility` — the bytecode target is unchanged, but the
  compilation now also checks calls against the JDK 21 API on newer
  toolchains (ADR-0013 D3);
- both gates permanent in `build.gradle`: `-Xdoclint:all -Werror` (javadoc,
  step 6) and `-Xlint:all -Werror` (both compile tasks, session #59) — each
  negative-tested on both sides;
- four explicit no-arg public constructors (`HttpTemplate`,
  `SimpleModuleInfo.Builder`, `JRestlyClient.Builder`,
  `OnErrorParser`) — **ABI-neutral**: the implicit default constructors
  produced the same bytecode members, so no new API surface; they exist to
  give the "use of default constructor" doclint sites a comment;
- test-only: the shared `TestFutures.causeOf` helper and the
  `TestModuleInfo.getEnvironment` generic override (ADR-0013 D1).

No behavior change: every fix lands on a behavior-neutral seam, and the hot
path was re-audited with no new findings (ADR-0013 D4). No breaking change.

Maintainer's process rules are unchanged from ADR-0012: no git tags (a tag
triggers a release build the `agent-loop` branch is not merged outward);
the version lives in `build.gradle` and the release commit message.

## Decision

1. **Version: `0.7.0` → `0.8.0`** — a semver *minor*, not a patch
   (`0.7.1`): the shipped javadoc artifact gains normative completeness (a
   user-visible artifact change, not a bug fix), the build pins the Java 21
   API surface with `--release 21`, and the three exception classes gain an
   explicit `@Serial` UID — a serialization-compatibility guarantee, not a
   fix. No breaking change, no behavior change; the four explicit
   constructors are ABI-neutral, so ADR-0013's "no new public surface"
   nature holds. ADR-0012 Decision 1's rule — a release carrying more than a
   fix signals a minor, even in 0.x — applies at artifact level here.
2. **Versioning mechanics:** ADR-0012 Decision 2, unchanged — the bump lands
   in `build.gradle` on the gate step, the version is named in that step's
   **commit message**, **no git tag**; the tag-based JitPack coordinates
   stay at `v0.6.1`, the last tag, until the maintainer tags.
3. **Release notes:** a `0.8.0` entry in `CHANGELOG.md`, written from the
   start in the navigator form established by the 0.7.0 entry (1–2 lines per
   change + a pointer to the source of truth — ADR-0013, the ROADMAP
   section, the Javadoc; no mechanics restated). The `0.7.0` entry
   (unpublished) is left untouched — both entries await the maintainer's
   tag; if the first tag is `v0.8.0`, the tagged artifact carries both
   cycles' content and the `0.7.0` entry stays as that content's record (the
   changelog records versions, not tags).
4. **Scope is packaging, not features:** exactly the ADR-0013 set (D1–D4 as
   executed) plus the `-Xlint` follow-up. No new feature, no refactor, **no
   README change** in the cycle (the install blocks and the status line stay
   at `v0.6.1`, the last tag). The POM gate is unchanged — zero
   `<dependency>` — with the version reading `0.8.0`. The gates are already
   armed and permanent, so the cycle gate **verifies, it does not install**:
   `compileJava` / `compileTestJava` 0 warnings under the permanent lint
   gate, javadoc 0 under the permanent doclint gate, `./gradlew test --rerun`
   **256/256** (36 classes), generated POM **0.8.0** / 0 `<dependency>`.
5. **Shape: one epic, four steps** (DRAFT), mirroring the ADR-0012 shape:
   1. concept — this ADR + the DRAFT epic (session #60);
   2. `CHANGELOG.md` — the `0.8.0` entry (navigator form);
   3. version bump in `build.gradle` + the gates above + the version in the
      commit message;
   4. distillation — a `Cycle 0.8.0 — COMPLETE ✅` section in the ROADMAP
      (links, not restatement) + DRAFT cleanup.

## Consequences

- The `javadocJar` ships with the first normative completion of the main
  surface, and the two `-Werror` gates keep it: a new public/protected
  member without a comment, or a new compiler warning, fails the build
  instead of decaying back into noise.
- `build.gradle` reads `0.8.0` without a tag — the same "unpublished
  development version" pattern 0.7.0 established; the README's `v0.6.1`
  install coordinates remain the only obtainable ones until the maintainer
  tags.
- The `0.7.0` and `0.8.0` changelog entries are both unpublished; the
  maintainer's tag publishes them, a single `v0.8.0` tag included (the
  changelog keeps both records; the `0.7.0` entry's "unpublished" note stays
  accurate until the tag).
- ADR-0014 is the cycle's own record; the next free number is 0015 (for the
  next cycle's `docs/` folder).
- After the cycle: the DRAFT active zone is down to one candidate —
  test-javadoc (ADR-0013 Non-goals): the test sources get the same javadoc
  treatment if the polish is wanted.
