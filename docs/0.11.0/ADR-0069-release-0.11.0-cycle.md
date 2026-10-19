# ADR-0069: The 0.11.0 cycle — Architectural Decomposition, API Conciseness & Streaming Performance Verification

- **Status:** accepted
- **Date:** 2026-10-19 (session #322)
- **Scope:** release cycle 0.11.0 (version bump from 0.10.0)
- **Companion ADRs of the cycle:** ADR-0066, ADR-0067, ADR-0068 (this directory)

## Context

Following the 0.10.0 cycle (ADR-0065), which established enterprise maturity, pluggable engines, and RFC 9111 HTTP caching, the 0.11.0 development cycle focused on internal pipeline elegance, modern API ergonomics, and comprehensive real-world streaming verification (sessions #242–#321).

The maintainer reiterated the core philosophy:
1. **Zero-Dependency Core:** Preserving the pure Java 21 foundation (`java.net.http.HttpClient`) with zero external runtime dependencies.
2. **Modern API Ergonomics:** Eliminating boilerplate, aligning with modern Java conventions and industry standards without sacrificing declarative clarity.
3. **Rigorous Empirical Verification:** Proving performance and memory efficiency claims on realistic streaming scenarios rather than relying solely on synthetic benchmarks.

Across sessions #242–#321, three major architectural milestones were designed, implemented, and empirically validated:

### 1. Internal Pipeline Decomposition & Architectural Cleanliness (ADR-0066)
- **HttpTemplate Decomposition:** The monolithic `HttpTemplate` (>2,500 lines) was decomposed into focused, single-responsibility collaborators:
  - `HttpTemplateBuilder`: Eager parameter resolution, path template interpolation, header construction, and query formatting.
  - `RequestBuilder`: Conversion of high-level request specifications into immutable `WireRequest` models.
  - `RequestExecutor`: Synchronous and asynchronous dispatch pipelines coordinating retries, transparent 401 token refresh, and cross-origin redirect safety.
  - `ResponseHandler`: Stream-safe response decoding, SSE line parsing, typed error mappings (`@OnError`), and metrics emission.
- **Zero Performance & Allocation Regression:** Confirmed via synthetic benchmarks (session #287) — dispatch latency remained at ~6.6 µs/op and allocations remained minimal.

### 2. Public API Conciseness & Modern Ergonomics (ADR-0067)
- **Concise Verb Annotations (`value()` aliases):** Added `value()` alias across `@Get`, `@Post`, `@Put`, `@Delete`, and `@Patch`, enabling natural `@Get("/items")` instead of verbose `@Get(path = "/items")`. Migrated 99 codebase files and all documentation.
- **Standard Brace Placeholders (`{id}`):** Replaced legacy `${id}` placeholders with standard `{id}` path templates, matching RFC 6570, Retrofit, JAX-RS, Spring, and OpenFeign conventions.
- **Unnamed Parameter Bindings:** Enabled bare `@PathVariable Long id` and `@RequestParam String query` declarations relying on the `-parameters` compiler flag, removing redundant string literals.
- **Static Query Parameters in Paths & TargetUrl Fix:** Added first-class support for static queries in verb paths (`@Get("/search?format=full")`) and resolved a subtle URL composition bug where `@TargetUrl` appended duplicate `?` delimiters.
- **Map Parameter Semantics Documentation:** Clarified and verified `name[key]` namespace prefixing for explicitly named map parameters versus flat parameter mapping for bare `@RequestParam Map<?, ?>`.

### 3. Real-World Scenario: Feed Fetch & Parse Benchmark (ADR-0068)
- **End-to-End Fetch-and-Parse Harness:** Built a deterministic, multi-client benchmark measuring throughput, allocations (B/op), and CPU consumption across 11 arms and 3 dataset sizes (`rss-20` 21.6 KB, `rss-200` 211 KB, `rss-1000` 1.05 MB) with a shared StAX XML parser.
- **Streaming Memory Triumph:** Demonstrated that jRestly's zero-copy streaming (`@Get InputStream`) saves **1.02 MB/op** on the default JDK engine and **>2.15 MB/op** on external engines (Apache HC5 and OkHttp) on 1 MB payloads compared to buffered approaches.
- **Competitive Supremacy on Large Payloads:** `jRestly + engine-apache5 (streaming)` achieved **30.4 ops/s** and **3.38 MB/op**, outperforming both raw `OkHttp` (24.6 ops/s, 3.42 MB/op) and `Retrofit 2` (27.2 ops/s, 4.47 MB/op).
- **Negligible Declarative Core Overhead:** Measured overhead of jRestly's dynamic proxy and dispatch pipeline against raw `java.net.http.HttpClient` at just **+6.3 KB** on 20 KB feeds and **+42 KB** on 1 MB feeds.
- **Publication:** Full comparative matrices and methodology published in `BENCHMARKS.md`.

### 4. Quality Control & Test Suite Expansion
- **Test Suite Growth:** Grew from 1,311 tests in 0.10.0 to **1,710 tests** (+399 tests, +30.4%) across all 5 modules:
  - `core`: 1,630 tests
  - `engine-okhttp`: 25 tests
  - `engine-apache5`: 25 tests
  - `codec-jackson-xml`: 15 tests
  - `benchmarks`: 15 tests
- **JaCoCo Coverage Records:**
  - **BRANCH coverage: 90.64%** (4,018 / 4,433 branches, breaching the 90% threshold for the first time in project history)
  - **INSTRUCTION coverage: 98.19%**
  - **LINE coverage: 97.84%**
  - **COMPLEXITY coverage: 88.90%**
  - **METHOD coverage: 100.00%** (1,455 / 1,455)
  - **CLASS coverage: 100.00%** (149 / 149)
- **Zero Warnings:** All code and tests compile under `-Xlint:all -Werror` and `-Xdoclint:all -Werror`.

## Decision

1. **Version: `0.10.0` → `0.11.0`** — a semver *minor*: packages major ergonomic improvements (`{id}`, `value()`, bare bindings), internal decomposition, and extensive benchmark infrastructure while strictly preserving full backward compatibility.
2. **Multi-Module Version Alignment:** The root project and all published submodules (`engine-okhttp`, `engine-apache5`, `codec-jackson-xml`) are updated to `0.11.0`.
3. **Versioning Mechanics:** Follows established project policy — version updated in `build.gradle`, named in the release commit message, **no git tag** (coordinates remain `v0.6.1` on JitPack until the maintainer publishes tags).
4. **Release Notes:** Comprehensive `0.11.0` entry in `CHANGELOG.md` highlighting API ergonomics, internal decomposition, streaming performance findings, and coverage metrics.
5. **Quality Gates Verified:**
   - `./gradlew test` passes 1,710 / 1,710 tests cleanly.
   - `./gradlew javadoc` completes with 0 warnings under `-Werror`.
   - Core POM maintains 0 runtime dependencies.

## Consequences

- The 0.11.0 release establishes jRestly as both modern in design and verified in performance: clean declarative syntax, modular core, zero-dependency foundation, and proven streaming efficiency.
- ADR-0069 serves as the definitive milestone record for Cycle 0.11.0; the next free ADR number is **0070**.
