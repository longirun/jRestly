# ADR-0012: The 0.7.0 cycle — packaging the six unreleased feature sets

- **Status:** accepted
- **Date:** 2026-10-13 (session #36)
- **Scope:** release cycle 0.7.0 (version bump from 0.6.2)
- **Companion ADRs of the cycle:** ADR-0007 … ADR-0011 (this directory)

## Context

Since the last tag (`v0.6.1`), the branch has accumulated **six unreleased
feature sets**, each distilled into its own `COMPLETE ✅ (unreleased)` section
of the ROADMAP with its design ADR in `docs/0.7.0/`:

1. `CompletableFuture` return type (ADR-0003 shortlist; sessions #5–#8)
2. `java.time` ISO-8601 option A — `JacksonCodec.LocalDateTimeFormat` (session #7)
3. Gson / Moshi codec adapters + probe chain (ADR-0007; sessions #10–#16)
4. Retry with backoff — transport + status axis (ADR-0008; sessions #17–#22)
5. 0.x polish — carry-over quirks + `Retry-After` HTTP-date (ADR-0009, ADR-0010; sessions #23–#29)
6. Payload-decode retry — the third failure-kind (ADR-0011; sessions #31–#35)

The known-gaps backlog that gated the cycle is now exhausted: the asctime
micro-gap is closed (ADR-0010), the remaining ADR-0008 known gaps are accepted
and fully documented (session #29), and payload-decode is closed (ADR-0011).
The ADR-0011 strategy — "feature first, cycle after" — has therefore opened
the cycle by itself: 0.7.0 now packages a **complete** retry contract
(transport + status + decode) with no residual substantive gap.

Maintainer's process rules (mailbox, 2026-10-12 → 2026-10-13): versioning is
at the agent's discretion for development tracking; **git tags are not to be
created** (a tag triggers a release build we do not publish — the `agent-loop`
branch is not merged outward); the version is carried **in the `build.gradle`
version and in the commit message** ("Если считаешь, что нужна версия —
пожалуйста делай ее через билд-гредл версию и в тексте сообщения коммита.
Пожалуйста, забудь про гит-теги").

## Decision

1. **Version: `0.6.2` → `0.7.0`** — a semver *minor*: the release adds public
   surface (`GsonCodec` / `MoshiCodec` + the extended `JsonCodecResolver`
   chain, `RetryPolicy` / `@Retry` / `ModuleInfo.getRetryPolicy()`,
   `JacksonCodec.LocalDateTimeFormat` + `defaults(format)`,
   `CompletableFuture<T>` controller return types) but **no breaking change
   and no behavior change** for an existing consumer — retry is off by
   default, the ISO format is opt-in, the codec comes from the classpath
   probe as before. Not a patch (`0.6.3`): new API surface, even in 0.x,
   signals a feature release, not a fix.
2. **Versioning mechanics:** the bump lands in `build.gradle` on the gate
   step; the version is named in that step's **commit message** (per the
   maintainer). **No git tag** — the tag-free version pattern established by
   0.6.2 ("releases paused", README install blocks keep pointing at `v0.6.1`,
   the last tag, since JitPack coordinates are obtainable by tag only).
3. **Release notes: a new `CHANGELOG.md` at the repository root** — the
   project's first user-facing changelog. The `0.7.0` entry is derived from
   the six ROADMAP `COMPLETE` sections (they remain the source of truth; the
   changelog states user-facing changes, not design rationale). Pre-0.7.0
   history stays in the ROADMAP — noted in the changelog's header rather than
   backfilled.
4. **Scope is packaging, not features:** exactly the six sets above, ADRs
   0007–0011. No new feature, no refactor, **no README change** in the cycle
   (the install blocks and the status line stay at `v0.6.1`, the last tag).
   The POM gate is unchanged — zero `<dependency>` — with the version reading
   `0.7.0`.
5. **Shape: one epic, four steps** (DRAFT):
   1. concept — this ADR + the DRAFT epic (session #36);
   2. `CHANGELOG.md` — create + the 0.7.0 entry;
   3. version bump in `build.gradle` + gate (suite green, POM `0.7.0`, zero
      `<dependency>`) + the version in the commit message;
   4. distillation — a `Cycle 0.7.0 — COMPLETE ✅` section in the ROADMAP
      (the packaging record: scope, versioning decisions, release notes) +
      DRAFT cleanup.

## Consequences

- The project gains its first `CHANGELOG.md` — a user-facing surface a
  mature OSS library is expected to have (the OkHttp / Retrofit / Feign
  bar), without duplicating the ROADMAP's design record.
- `build.gradle` reads `0.7.0` without a tag — the same "unpublished
  development version" pattern 0.6.2 established; the README's
  `v0.6.1` install coordinates remain the only obtainable ones until the
  maintainer merges / tags.
- The six ROADMAP `COMPLETE (unreleased)` sections become the *contents* of
  the 0.7.0 release; the cycle section links them instead of restating them.
- ADR-0012 is the cycle's own record; the next free number is 0013 (for the
  next cycle's `docs/` folder).
