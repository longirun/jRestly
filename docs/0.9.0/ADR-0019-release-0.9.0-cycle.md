# ADR-0019: The 0.9.0 cycle — packaging the ergonomics & modern APIs set

- **Status:** accepted
- **Date:** 2026-10-14 (session #93)
- **Scope:** release cycle 0.9.0 (version bump from 0.8.0)
- **Companion ADRs of the cycle:** ADR-0015, ADR-0016, ADR-0017, ADR-0018 (this directory)

## Context

Following the 0.8.0 cycle (ADR-0014), the maintainer set the project's strategic frame
(mailbox, 2026-10-14 09:10):
1. **Invariants:** Zero-dependency core, transparent calls without `Response<T>` wrappers (ADR-0004),
   and refusal of all-in-one frameworks (ADR-0003).
2. **Niche:** Ideal declarative HTTP client for modern Java (test suites today, real-world
   service integrations in production tomorrow).
3. **Ergonomics for modern APIs:** Deepening caller ergonomics for modern endpoints
   (request parameter flexibility, data streaming, LLM integration, request pipeline tuning).

Across sessions #64–#91, the branch completed all four planned ergonomic epics (Direction 3),
each backed by extensive TDD suites, comprehensive Javadoc, and documentation:

1. **ADR-0015: Response streaming — `InputStream` return type** (sessions #64–#71):
   Zero-dep byte-stream streaming directly into user code without whole-body buffering.
   JSON codec bypassed for success bodies, preserved for `@OnError` and unexpected status
   error contracts.
2. **ADR-0016: Server-Sent Events & line streaming** (sessions #72–#77):
   `Stream<String>` (NDJSON / line feeds) and `Stream<ServerSentEvent>` (WHATWG specification,
   multi-line data, LLM terminal `[DONE]` detection). Lazy parsing, automatic network stream
   cleanup via `stream.onClose(...)`.
3. **ADR-0017: Request parameter & header flexibility** (sessions #78–#83):
   Nullable headers and parameters gracefully omitted, dynamic `Map<?, ?>` query/header expansion,
   class-level and method-level static `@RequestHeader` and `@RequestDefaultParam` with `@Repeatable`,
   interface inheritance, and deterministic override precedence.
4. **ADR-0018: Request pipeline — Interceptors & hooks** (sessions #84–#91):
   Pre-execution `RequestInterceptor` (header injection, authentication, URL rewriting via
   `WireRequest` withers) and post-execution `ResponseInterceptor` (metrics, audit, logging).
   Fault-isolated execution, thread-safe attribute propagation via `RequestContext`/`ResponseContext`,
   zero-cost empty path, and complete symmetry across synchronous and asynchronous twins.

The entire test suite has grown from 256/256 (36 classes) in 0.8.0 to **420/420** (51 classes),
with zero failures and zero errors. Permanent build gates (`javac -Xlint:all -Werror` and
`javadoc -Xdoclint:all -Werror`) remain clean (0 warnings). The generated POM preserves
zero `<dependency>`.

Maintainer process rules remain identical to ADR-0012 and ADR-0014: no git tags (a tag
triggers a release build; the `agent-loop` branch is not merged outward); the version lives
in `build.gradle` and the release commit message.

## Decision

1. **Version: `0.8.0` → `0.9.0`** — a semver *minor*: the release ships major ergonomic
   capabilities (byte/line/SSE streaming, dynamic maps, static headers, request/response interceptor
   pipeline) while preserving full backward compatibility for existing callers. No breaking changes,
   no mandatory migrations.
2. **Versioning mechanics:** ADR-0012 Decision 2, unchanged — the bump lands in `build.gradle`
   on the gate step, the version is named in that step's **commit message**, **no git tag**;
   the tag-based JitPack coordinates stay at `v0.6.1` until the maintainer tags.
3. **Release notes:** a `0.9.0` entry in `CHANGELOG.md`, written in navigator form
   (1–2 lines per epic + pointer to source of truth: ADR-0015..0018, README sections, Javadoc;
   no mechanics restated). The unpublished `0.7.0` and `0.8.0` entries remain untouched.
4. **Scope is packaging, not features:** exactly the four ergonomic sets (ADR-0015–0018).
   No new features, no refactor, **no README install coordinate change** (coordinates stay at `v0.6.1`).
   The POM gate is unchanged — zero `<dependency>` — with the version reading `0.9.0`.
   Gates verify the tree:
   - `compileJava` / `compileTestJava` 0 warnings under `-Xlint:all -Werror`;
   - `javadoc` 0 warnings under `-Xdoclint:all -Werror`;
   - `./gradlew test --rerun` **420/420** (51 classes);
   - generated POM **0.9.0** with 0 `<dependency>`.
5. **Shape: one epic, four steps** (DRAFT), mirroring the ADR-0012 and ADR-0014 shape:
   1. concept — this ADR + DRAFT epic (session #93);
   2. `CHANGELOG.md` — the `0.9.0` entry (navigator form);
   3. version bump in `build.gradle` + the gates above + version in commit message;
   4. distillation — a `Cycle 0.9.0 — COMPLETE ✅` section in `ROADMAP.md` + DRAFT cleanup.

## Consequences

- All four ergonomic epics of Direction 3 are formally packaged into the 0.9.0 minor release.
- jRestly provides first-class support for modern API integrations: LLM streaming token feeds,
  large binary downloads, flexible dynamic queries, and enterprise-grade interceptor pipelines.
- `build.gradle` will read `0.9.0` without a tag — maintaining the unpublished development
  version pattern.
- ADR-0019 is the cycle's own record; the next free number is 0020.
- After the cycle: the DRAFT active zone will evaluate the next strategic milestones
  (e.g., candidate test-javadoc, or new architecture directions).
