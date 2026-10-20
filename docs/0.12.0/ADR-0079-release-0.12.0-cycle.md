# ADR-0079: The 0.12.0 cycle — Netty Transport, Polyglot Codec Ecosystem & Deep Zero-Garbage Dispatch Audits

- **Status:** accepted
- **Date:** 2026-10-20 (session #338)
- **Scope:** release cycle 0.12.0 (version bump from 0.11.0)
- **Companion ADRs of the cycle:** ADR-0070, ADR-0071, ADR-0072, ADR-0073, ADR-0074, ADR-0075, ADR-0076, ADR-0077, ADR-0078 (this directory)

## Context

Following the 0.11.0 milestone (ADR-0069), which achieved internal pipeline decomposition, modern syntax ergonomics (`{id}`, `value()`, bare bindings), and empirical streaming proof on live feeds, the 0.12.0 cycle focused on three architectural pillars across sessions #323–#338:
1. **High-Performance Transport Expansion:** Introducing an asynchronous, non-blocking Netty transport engine taking full advantage of pooled off-heap buffers and event-loop multiplexing.
2. **Polyglot Codec Ecosystem:** Expanding beyond JSON and XML with official, modular adapters for YAML (RFC 9512) and Protocol Buffers (`protobuf-java`) adhering strictly to the `PayloadCodec` SPI and zero-dep core boundaries.
3. **Java 21 Modernization & Deep Zero-Garbage Dispatch Audits:** Auditing sealed types and adopting Java 21 pattern matching, while systematically eliminating intermediate allocations across URI composition, header merging, tracing, response decoding, logging gates, and multipart publisher graphs.

The maintainer reaffirmed the governing principles:
- **Zero-Dep Core:** The core jar relies purely on Java 21 (`java.net.http.HttpClient`) with 0 external runtime dependencies.
- **TDD & Parity:** Every behavioral and structural modification is accompanied by red-first tests and verified against regression suites.
- **Allocation & Inspection Rigor:** Hot paths operate with minimal allocation overhead, zero compiler warnings under `-Werror`, and clean documentation under `-Xdoclint:all -Werror`.

---

## Architectural Milestones of the 0.12.0 Cycle

### 1. Netty Transport Engine: `jrestly-engine-netty` (ADR-0070)
- **Asynchronous Non-Blocking Transport:** Built `jrestly-engine-netty` (Netty 4.1.118.Final) as a first-class `HttpEngine` SPI provider (priority 60).
- **Architecture & Memory Management:** Implemented connection pooling via `AbstractChannelPoolMap` with dynamic route isolation (`EndpointKey`), keep-alive reuse, push-to-pull streaming bridge (`NettyChunkedInputStream`) with reactive backpressure (`setAutoRead(false)` / `setAutoRead(true)`), and zero memory leaks verified under `-Dio.netty.leakDetection.level=PARANOID`.
- **Benchmark Supremacy:** Integrated into `FeedBenchmark` (13 arms, 3 payload sizes). On small payloads (`rss-20`), `jrestly + engine-netty (streaming)` achieved **98,342 B/op** — an absolute record across all measured frameworks (less than raw OkHttp 98,725 B/op and Apache HC5 101,141 B/op thanks to Netty's `PooledByteBufAllocator`). Throughput reached **308.3 ops/s** (+53% over default JDK 201.5 ops/s) with CPU time reduced to **6.39 ms/op** (vs JDK 9.75 ms/op and Feign 9.42 ms/op). Full tables published in `BENCHMARKS.md`.

### 2. Java 21+ Pattern Matching & Sealed Architecture (ADR-0071)
- **Non-Sealed Extensibility Invariant:** Formally established that public SPI contracts (`HttpEngine`, `PayloadCodec`, `RequestInterceptor`, `ResponseInterceptor`, `ClientMetricsListener`, `TokenManager`, `CircuitBreaker`, `RateLimiter`) and execution contexts (`RequestContext`, `ResponseContext`, `CallMetrics`, `HandledException`) remain `non-sealed` to ensure clean mockability (Mockito) and unrestricted user extensibility.
- **Pattern Matching for Switch (JEP 441):** Refactored dispatch, failure classification, and JSON serialization routines across `CircuitBreakerConfig`, `ProblemDetail`, `HttpTemplateBuilder`, and `HttpTemplate` using typed pattern matching and `when` pattern guards without preview flags, preserving zero compiler warnings under `-Werror`.

### 3. Zero-Allocation URI Composition & RFC 3986 Fast-Paths (ADR-0072)
- **Single-Pass Assembly:** Rewrote `HttpTemplate.createUri` and `appendQuery` to eliminate multi-pass string manipulations, replacing temporary `StringBuilder` instances and substring slicing with direct `CharSequence` slicing.
- **RFC 3986 Unreserved Fast-Path (`UriEncoder`):** Added `ru.jrestly.http.UriEncoder` detecting unreserved ASCII characters (`[a-zA-Z0-9_.*-]`). Numeric identifiers, booleans, UUIDs, ISO dates, and standard slug parameter names are invariant under percent-encoding: `encode(s)` returns the exact same string instance with 0 allocations, and `encodeTo(s, sb)` appends directly to the target buffer.
- **Precomputed Path Placeholders:** Precomputed and cached `placeholderToken` (e.g. `"{id}"`) at controller initialization within `MethodMetadata.ParameterDescriptor`.

### 4. Polyglot Codec Submodules: YAML & Protocol Buffers (ADR-0073, ADR-0074)
- **YAML Payload Codec (`jrestly-codec-yaml`):** Integrates Jackson `YAMLMapper` 2.18.2 with the same engine generation as JSON and XML adapters. Claims media types `application/yaml`, `text/yaml`, and `*+yaml` (RFC 9512 + RFC 6839). Registered via SPI (`YamlCodecProvider`, priority 50) with classpath auto-discovery. Added `MediaType.APPLICATION_YAML` and `TEXT_YAML` to the core without breaking zero-dep invariants.
- **Protocol Buffers Payload Codec (`jrestly-codec-protobuf`):** Integrates `protobuf-java` 3.25.5. Claims `application/x-protobuf`, `application/protobuf`, and `*+protobuf`. Resolves message parsers for `Class<? extends Message>` using a tiered resolution pipeline: public static `PARSER` → static `getDefaultInstance()` + `getParserForType()` (supporting protobuf well-known types like `Timestamp` and `Struct`) → fail-fast `IllegalArgumentException`, cached via `ConcurrentHashMap`. Provides hex-dump `prettyPrint` for safe response logging. Added `MediaType.APPLICATION_PROTOBUF` and `APPLICATION_X_PROTOBUF`.

### 5. Deep Zero-Garbage Dispatch Audits & Hot-Path Pruning (ADR-0075 – ADR-0078)
- **Header Merge & Tracing Optimization (ADR-0075):** Replaced `HashSet` merge tracking with direct O(a·s) case-insensitive scan (cheaper for standard small HTTP header lists), added reverse in-place `removeHeaderByName` and `hasHeaderNamed`, eliminated redundant external unmodifiable wrapping in `applyTracePropagator`, and introduced shape-driven single-value optimizations in `ResponseHeaders.buildValueMap`.
- **Dispatch-Tail Log Gates & Decompression Strip (ADR-0076):** Enclosed `logPerHop`, `scheduleRetry`, and `scheduleAsyncRetry` message construction in `isLoggable(DEBUG)` guards, eliminating message string allocations on non-debug executions. Replaced Stream pipeline in `handleDecompression` with a pre-sized plain loop, eliminating double defensive copying.
- **Response-Decode Single Resolution & Wire-Byte Size Line (ADR-0077):** Resolved the payload codec exactly once per response on DTO decoding legs in `ResponseBody`, eliminating duplicate Content-Type header scanning and codec registry lookups. Eliminated full-payload UTF-8 string materialization in the response size log, reporting true wire byte size (`bytes.length`).
- **Multipart Size-Only Seam (ADR-0078):** Introduced public seam `MultipartBodyPublisher.contentLength(boundary, parts)` allowing the INFO log and `MetricsNotifier.requestBytes` to probe exact multipart body lengths without constructing the underlying publisher graph or allocating per-part header arrays. Guaranteed probe-not-build semantics and full parity with JDK 21 `Files.exists` directory behavior.

---

## Quality Gates & Verification

- **Test Suite Expansion:** Grew from 1,710 tests (in 0.11.0) to **1,833 tests** (+123 tests, +7.2%) across all 8 modules:
  - `core`: 1,671 tests
  - `engine-okhttp`: 25 tests
  - `engine-apache5`: 25 tests
  - `engine-netty`: 26 tests
  - `codec-jackson-xml`: 15 tests
  - `codec-yaml`: 15 tests
  - `codec-protobuf`: 19 tests
  - `benchmarks`: 37 tests
- **JaCoCo Coverage Records (Historical All-Time Peak):**
  - **BRANCH coverage: 90.81–90.85%** (4,071–4,073 / 4,483 covered, on-disk 4,071, surpassing the previous record of 90.64%)
  - **INSTRUCTION coverage: 98.13%**
  - **LINE coverage: 97.78%**
  - **COMPLEXITY coverage: 89.06–89.11%**
  - **METHOD coverage: 100.00%**
  - **CLASS coverage: 100.00%**
- **Compiler & Linter Rigor:** Clean compilation across all 8 modules under `-Xlint:all -Werror` and `-Xdoclint:all -Werror`.
- **Zero-Dep POM Integrity:** The published root POM contains strictly 0 runtime `<dependency>`.

---

## Decision

1. **Version Bump:** `0.11.0` → `0.12.0` (semver minor). Packages major ecosystem additions (`engine-netty`, `codec-yaml`, `codec-protobuf`), modern Java 21 pattern matching, and extensive zero-allocation hot-path optimizations while preserving 100% backward compatibility of the public API.
2. **Multi-Module Alignment:** Updated `version = '0.12.0'` in root `build.gradle` and all six published submodules:
   - `engine-okhttp`
   - `engine-apache5`
   - `engine-netty`
   - `codec-jackson-xml`
   - `codec-yaml`
   - `codec-protobuf`
3. **Documentation:** Release notes published in `CHANGELOG.md` under `## 0.12.0 — 2026-10-20 (unpublished)`.
4. **Git Tagging:** Follows the established project policy — no git tag is created (coordinates remain `v0.6.1` on JitPack until the maintainer tags).

---

## Consequences

- Cycle 0.12.0 establishes jRestly as a premier, battle-tested HTTP client library: multi-engine (JDK, OkHttp, Apache HC5, Netty), polyglot codec support (JSON, XML, YAML, Protobuf), uncompromising zero-allocation dispatch, and unmatched branch coverage (>90.8%).
- ADR-0079 serves as the definitive packaging milestone for Cycle 0.12.0.
- The next free ADR number is **0080**, opening Cycle 0.13.0.
