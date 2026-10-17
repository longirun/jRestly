# ADR-0065: The 0.10.0 cycle — Enterprise Maturity, Pluggable Ecosystem & Vital Production Parity

- **Status:** accepted
- **Date:** 2026-10-17 (session #241)
- **Scope:** release cycle 0.10.0 (version bump from 0.9.0)
- **Companion ADRs of the cycle:** ADR-0020..ADR-0064 (this directory)

## Context

Following the 0.9.0 cycle (ADR-0019), which packaged modern API ergonomics (byte/line/SSE streaming, dynamic query/header maps, interceptors), the project embarked on an extensive journey of enterprise hardening, transport pluggability, hot-path allocation elimination, and competitive feature closure (sessions #94–#240).

The maintainer set clear strategic guidelines (mailbox):
1. **Core Invariants:** Zero-dependency core on Java 21 (`java.net.http.HttpClient`), dynamic proxy, transparent methods without mandatory `Response<T>` wrappers (ADR-0004), and rejection of bloated all-in-one frameworks (ADR-0003). External integrations belong in SPI and separate published modules.
2. **Niche & Mission:** Ideal declarative HTTP client for modern Java, bridging fast test harnesses with enterprise-grade production service integrations.
3. **Maturity & Quality:** Rigorous competitive analysis against Retrofit 2, OpenFeign, and Spring 6 HTTP Interfaces, automated code quality control via JaCoCo, and elimination of silent misconfigurations.

Across sessions #94–#240, 45 architectural decisions (ADR-0020 through ADR-0064) were designed, implemented, and verified with comprehensive TDD suites:

### 1. Transport, Security & Pluggable Engines
- **SSL / mTLS & Custom Certificates (ADR-0020):** First-class support for mutual TLS and custom trust stores.
- **Transparent Decompression (ADR-0021, ADR-0057):** Transparent GZIP/Deflate handling, streaming decompression SPI.
- **HTTP Proxy & Authenticator (ADR-0037):** Configurable proxy routing and transport-level basic/digest authenticators.
- **HTTP/2 Protocol & Trailers (ADR-0038):** Protocol negotiation, ALPN inspection, and HTTP/2 trailers support.
- **Pluggable HttpEngine SPI (ADR-0052, ADR-0058):** Pluggable engine architecture; default zero-dep `JdkHttpEngine` plus published optional submodules `jrestly-engine-okhttp` (OkHttp 4) and `jrestly-engine-apache5` (Apache HC5) discovered via `ServiceLoader`.

### 2. Contract Ergonomics & Metadata Access
- **Metadata Access (`ResponseHeaders`) (ADR-0022):** Non-invasive access to status, response headers, and trailers without wrapping return types.
- **Conditional Requests & `Optional<T>` Returns (ADR-0023):** Automatic handling of `304 Not Modified` and `404 Not Found` mapping to `Optional.empty()`.
- **Form Data & Multipart Flexibility (ADR-0024):** Streaming multipart parts, mixed collections, and form-urlencoded parameter bindings.
- **URI Template Expansion & Parameter Arrays (ADR-0025):** RFC-compliant URI template interpolation with eager validation.
- **Per-method Timeouts (`@Timeout`) (ADR-0027):** Fine-grained request timeouts overriding client defaults.
- **Declarative Interceptors (`@WithInterceptor`, `@Order`) (ADR-0029, ADR-0044):** Class- and method-level interceptor bindings with deterministic declaration-order priority.
- **Progress Tracking (`UploadProgressListener`, `DownloadProgressListener`) (ADR-0030, ADR-0033):** Zero-overhead transfer progress listeners.
- **Declarative `@Accept` & `@ContentType` (ADR-0031):** Type-safe media-type annotations.
- **Session & Cookie Management (`CookieStore` SPI) (ADR-0032, ADR-0039):** Built-in cookie handling with pluggable persistent storage SPI.
- **Query Collection Formats (`@QueryCollectionFormat`) (ADR-0034):** Support for EXPLODE, CSV, SSV, TSV, PIPES, and BRACKETS collection formats.
- **Request Body Streaming (ADR-0035):** First-class support for `BodyPublisher`, `InputStream`, and `Supplier` bodies.
- **Zero-Dep Auth SPI & Token Manager (ADR-0046):** Transparent 401 token refresh state machine.

### 3. Resilience, Observability & Performance
- **Observability SPI (`ClientMetricsListener`, `CallMetrics`) (ADR-0036):** Metrics hooks for duration, status, attempt counts, and wire byte sizes.
- **Exponential Backoff Jitter & Retry-After (ADR-0040):** Full jitter, decorrelated jitter, and RFC 9110 `Retry-After` bounds.
- **Request Compression SPI (ADR-0041):** Automatic GZIP / Deflate body compression with size thresholds.
- **Distributed Tracing & Correlation ID SPI (ADR-0042):** W3C TraceContext and custom correlation ID header propagation.
- **Circuit Breaker & Rate Limiter SPI (ADR-0043):** Thread-safe in-memory sliding window circuit breaker and token bucket rate limiter.
- **Interceptor Short-Circuit SPI (ADR-0047):** Synthetic short-circuit responses bypassing network execution.
- **Zero-Garbage Hot-Path Dispatch (ADR-0051, ADR-0054, ADR-0055):** `MethodMetadata` caching, descriptor precomputation, zero-reflection invocation (~5–9 µs overhead).
- **Public JMH Benchmarks (ADR-0049, ADR-0050, `BENCHMARKS.md`):** Comprehensive comparative performance harness against raw transports and competitive declarative clients.

### 4. Ecosystem & Vital Production Parity
- **SSE Stream Resilience & Streaming Trailers (ADR-0059):** Auto-reconnecting SSE (`ServerSentEvent.reconnecting`), `Last-Event-ID` replay, idle `stallTimeout` guard.
- **Pluggable Payload Formats (`PayloadCodec` SPI & `codec-jackson-xml`) (ADR-0060):** Media-type-aware codecs, claiming `application/xml` via opt-in published submodule.
- **Quality Control Baseline & JaCoCo 0.8.12 (ADR-0061):** Competitive matrix against Retrofit/Feign/Spring; JaCoCo integration with >89% instructions and >98% class coverage.
- **Vital Gap #1: Dynamic Endpoint Override via `@TargetUrl` (ADR-0062):** S3 presigned URLs, HATEOAS Link pagination, webhooks, and dynamic multi-tenant routing.
- **Vital Gap #2: Transparent RFC 9111 HTTP Cache SPI & In-Memory Store (ADR-0063):** Cache-Control parser, ETag/If-None-Match conditional 304 revalidation, bounded LRU cache, and RFC 9111 §4.3.4 representation header merging.
- **Vital Gap #3: Problem Details for HTTP APIs (RFC 7807 / RFC 9457) & Error Ergonomics (ADR-0064):** Zero-dep `ProblemDetail` model and JSON parser, automated recognition in `ClientErrorException`/`ServerErrorException`, and declarative `@OnError` mapping.

The entire test suite has grown from 420/420 tests (in 0.9.0) to **1311/1311 tests** (0 failures, 0 errors) across 5 modules:
- `core`: 1231 tests
- `engine-okhttp`: 25 tests
- `engine-apache5`: 25 tests
- `codec-jackson-xml`: 15 tests
- `benchmarks`: 15 tests

Permanent build gates (`javac -Xlint:all -Werror`, `javadoc -Xdoclint:all -Werror`, and `jacocoTestReport`) are completely green.

## Decision

1. **Version: `0.9.0` → `0.10.0`** — a semver *minor*: this release packages monumental feature additions across transports, caching, error handling, security, and resilience while preserving 100% backward compatibility for existing callers. The zero-dependency core is strictly maintained.
2. **Multi-Module Version Alignment:** The root project and all published submodules (`codec-jackson-xml`, `engine-okhttp`, `engine-apache5`) are updated to `0.10.0`.
3. **Versioning Mechanics:** Follows established project policy — version updated in `build.gradle`, named in the release commit message, **no git tag** (coordinates remain `v0.6.1` on JitPack until the maintainer publishes tags).
4. **Release Notes:** A comprehensive `0.10.0` entry in `CHANGELOG.md` written in navigator form pointing to ADRs, Javadocs, and README sections.
5. **Gates:** Clean verification of the entire project tree:
   - `compileJava` / `compileTestJava` 0 warnings under `-Xlint:all -Werror`;
   - `javadoc` 0 warnings under `-Xdoclint:all -Werror`;
   - `./gradlew test` **1311/1311** across all 5 modules;
   - `./gradlew jacocoTestReport` passing with ~89.4% instruction coverage and 98.6% class coverage;
   - Generated POMs contain 0 runtime `<dependency>` for core.

## Consequences

- The 0.10.0 minor release elevates jRestly from a lightweight client to a mature, enterprise-grade, high-performance declarative HTTP client library of world-class caliber.
- Complete feature parity with existing industry leaders (Retrofit, Feign, Spring HTTP Interfaces) is attained, while maintaining jRestly's unique advantages: zero-dependency core, virtual threads native performance, transparent domain method signatures, and zero-reflection hot-path execution.
- ADR-0065 serves as the definitive milestone record for Cycle 0.10.0; the next free ADR number is **0066**.
