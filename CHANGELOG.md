# Changelog

User-facing changes per version. History prior to 0.7.0 (cycles 0.5.0 →
0.6.1) lives in [ROADMAP.md](ROADMAP.md).

## 0.12.0 — 2026-10-20 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **Netty transport engine (`jrestly-engine-netty`)** — High-performance, asynchronous non-blocking HTTP client engine based on Netty 4.1.118.Final and the `HttpEngine` SPI (priority 60). Leverages connection pooling via `AbstractChannelPoolMap`, EventLoop I/O, and an asynchronous push-to-pull streaming bridge (`NettyChunkedInputStream`) with reactive backpressure and zero buffer leaks under `-Dio.netty.leakDetection.level=PARANOID`. Demonstrates benchmark-leading memory allocations on small payloads (**98,342 B/op** on 20 KB feeds vs raw OkHttp 98,725 B/op and Apache HC5 101,141 B/op) and **308.3 ops/s** throughput (+53% over default JDK). Details: [ADR-0070](docs/0.12.0/ADR-0070-http-engine-netty-transport.md) and [BENCHMARKS.md](BENCHMARKS.md).
- **YAML payload codec submodule (`jrestly-codec-yaml`)** — Official YAML payload adapter powered by Jackson `YAMLMapper` 2.18.2 for the `PayloadCodec` SPI (priority 50). Full media type support for `application/yaml`, `text/yaml`, and `*+yaml` (RFC 9512 + RFC 6839) with classpath auto-discovery. Core gains named constants `MediaType.APPLICATION_YAML` and `TEXT_YAML` while remaining strictly zero-dependency. Details: [ADR-0073](docs/0.12.0/ADR-0073-codec-yaml-submodule.md).
- **Protocol Buffers payload codec submodule (`jrestly-codec-protobuf`)** — Official Protocol Buffers binary adapter powered by `protobuf-java` 3.25.5 for the `PayloadCodec` SPI (priority 50). Full media type support for `application/x-protobuf`, `application/protobuf`, and `*+protobuf`. High-performance cached parser resolution supporting both protoc-generated messages (public static `PARSER`) and hand-crafted / well-known types (`Timestamp`, `Struct`) via static factory methods. Safe hex-dump `prettyPrint` for response logging. Core gains `MediaType.APPLICATION_PROTOBUF` and `APPLICATION_X_PROTOBUF`. Details: [ADR-0074](docs/0.12.0/ADR-0074-codec-protobuf-submodule.md).
- **Java 21+ pattern matching & non-sealed architectural guarantees** — Modern Java 21 pattern matching for `switch` (JEP 441) and pattern guards across dispatch, failure classification, and JSON serialization. Public SPIs and execution contexts are formally guaranteed non-sealed for seamless Mockito mocking and user extensibility. Details: [ADR-0071](docs/0.12.0/ADR-0071-java-21-pattern-matching-and-sealed-architecture.md).
- **Zero-allocation URI composition & RFC 3986 unreserved fast-path** — Single-pass URI assembly eliminating intermediate substring allocations, precomputed `{id}` path placeholders cached in `MethodMetadata`, and `ru.jrestly.http.UriEncoder` providing zero-allocation pass-through for unreserved ASCII identifiers (UUIDs, numbers, booleans, ISO dates, slugs). Details: [ADR-0072](docs/0.12.0/ADR-0072-zero-allocation-uri-composition-and-fast-paths.md).
- **Multipart size-only probe seam (`MultipartBodyPublisher.contentLength`)** — Public seam measuring exact multipart body size without building publisher trees, allocating per-part header buffers, or opening stream sources. Applied to response logging and metric emission with full parity to JDK 21 `Files.exists` directory behavior. Details: [ADR-0078](docs/0.12.0/ADR-0078-multipart-size-only-seam.md).
- **Record test coverage & JaCoCo quality gate** — Test suite grown to **1,833 tests** (+123 tests) across 8 modules. JaCoCo branch coverage reached an all-time record of **90.81–90.85%** (4,071–4,073 / 4,483 covered branches), instruction coverage **98.13%**, line coverage **97.78%**, and 100% method and class coverage.

### Changed

- **Header merge & W3C tracing allocation pruning** — Replaced hash set tracking with direct case-insensitive O(a·s) scans in header merging, eliminated redundant unmodifiable wrapping in W3C tracing propagators, and optimized `ResponseHeaders.buildValueMap` with singleton `emptyMap()` and compact `List.of()` shapes. Details: [ADR-0075](docs/0.12.0/ADR-0075-header-merge-invariants-and-allocation-pruning.md).
- **Dispatch-tail log gating & single-pass decompression** — Gated message construction in `logPerHop`, `scheduleRetry`, and `scheduleAsyncRetry` behind `isLoggable(DEBUG)` checks to eliminate message string allocations on default log levels. Streamlined decompression header stripping to a pre-sized plain loop. Details: [ADR-0076](docs/0.12.0/ADR-0076-dispatch-tail-log-gates-and-decompression-strip-loop.md).
- **Response decoding single-resolution & wire byte reporting** — Eliminated duplicate codec resolution in DTO response branches (`ResponseBody.bufferedOptional` and `bufferedRaw`), resolving the codec exactly once per response. Response size logging now reports exact wire byte sizes (`bytes.length`) rather than materializing transient UTF-8 strings. Details: [ADR-0077](docs/0.12.0/ADR-0077-dto-decode-single-resolution-and-wire-byte-size.md).
- Core remains strictly zero-dependency on Java 21 `java.net.http.HttpClient` with zero third-party runtime dependencies in published POMs.

## 0.11.0 — 2026-10-19 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **Modern path template syntax (`{id}`)** — RFC 6570 standard brace placeholders `{id}` replace the legacy `${id}` syntax across all HTTP verb annotations, aligning jRestly with industry-standard conventions (Retrofit, JAX-RS, Spring, Feign). Details: [ADR-0067](docs/0.11.0/ADR-0067-public-api-conciseness-review.md).
- **Concise HTTP verb declarations (`value()` attribute)** — `@Get("/path")`, `@Post("/path")`, `@Put("/path")`, `@Delete("/path")`, and `@Patch("/path")` can now be declared concisely without the verbose `path = ...` argument. Details: [ADR-0067](docs/0.11.0/ADR-0067-public-api-conciseness-review.md).
- **Unnamed parameter bindings** — parameter annotations `@PathVariable`, `@RequestParam`, `@RequestHeader`, and `@Cookie` now seamlessly fall back to Java parameter names when compiled with `-parameters`, allowing clean declarations like `@PathVariable Long id` instead of `@PathVariable(name = "id") Long id`. Details: [ADR-0067](docs/0.11.0/ADR-0067-public-api-conciseness-review.md).
- **Static query parameters in paths & `@TargetUrl` query merging** — verb paths can now include static query parameters (`@Get("/items?filter=active")`), properly merged with runtime dynamic `@RequestParam` arguments, and safely combined when paired with `@TargetUrl` without duplicate `?` delimiters. Details: [ADR-0067](docs/0.11.0/ADR-0067-public-api-conciseness-review.md).
- **Real-world streaming benchmark suite & proven memory efficiency** — multi-client scenario benchmark (`FeedBenchmark`) published in `BENCHMARKS.md`, demonstrating that zero-copy streaming (`@Get InputStream`) saves **1.02 MB/op** on the default JDK engine and **>2.15 MB/op** on external engines (Apache HC5, OkHttp) on 1 MB payloads. `jRestly + engine-apache5` streaming achieves **30.4 ops/s** and **3.38 MB/op**, outperforming raw OkHttp and Retrofit 2. Details: [ADR-0068](docs/0.11.0/ADR-0068-real-feed-benchmark-fetch-and-parse.md) and [BENCHMARKS.md](BENCHMARKS.md).
- **Pipeline decomposition & modular architecture** — monolithic `HttpTemplate` decomposed into `HttpTemplateBuilder`, `RequestBuilder`, `RequestExecutor`, and `ResponseHandler` with zero dispatch overhead or allocation penalty. Details: [ADR-0066](docs/0.11.0/ADR-0066-internal-code-structure-and-pipeline-decomposition.md).
- **Record test coverage & JaCoCo quality gate** — test suite expanded to **1,710 tests** (0 failures, 0 errors). JaCoCo branch coverage reached an all-time record of **90.64%** (surpassing the 90% milestone), instruction coverage **98.19%**, line coverage **97.84%**, and 100% method/class coverage.

### Changed

- Core remains strictly zero-dependency on Java 21 `java.net.http.HttpClient` with zero third-party runtime dependencies in published POMs.

## 0.10.0 — 2026-10-17 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **Dynamic endpoint override (`@TargetUrl`)** — call runtime-determined URLs (AWS S3 presigned URLs, HATEOAS Link pagination, webhooks, multi-tenant routing) directly from interface methods with zero-reflection cached dispatch and eager validation. Details: [ADR-0062](docs/0.10.0/ADR-0062-dynamic-endpoint-target-url-override.md).
- **Transparent RFC 9111 HTTP caching & in-memory store** — zero-dep `HttpCache` SPI with thread-safe `InMemoryHttpCache` (bounded LRU), transparent `304 Not Modified` conditional revalidation (ETag, `If-Modified-Since`), `Cache-Control` directive parsing (`max-age`, `no-cache`, `no-store`), representation header merging (§4.3.4), and zero network attempts on fresh cache hits. Details: [ADR-0063](docs/0.10.0/ADR-0063-transparent-rfc-9111-http-cache.md).
- **Problem Details for HTTP APIs (RFC 7807 / RFC 9457)** — zero-dep `ProblemDetail` model and built-in JSON parser/serializer, automated extraction on unexpected 4xx/5xx responses into `ClientErrorException` and `ServerErrorException`, and declarative `@OnError(errorObject = ProblemDetail.class)` and `@OnError(errorObject = ProblemDetailException.class)`. Details: [ADR-0064](docs/0.10.0/ADR-0064-problem-details-for-http-apis.md).
- **Pluggable payload formats & XML support (`jrestly-codec-jackson-xml`)** — `PayloadCodec` SPI and `CodecRegistry` for content-type aware serialization. Core remains zero-dep; XML payload support (`application/xml`, `text/xml`) available via optional published submodule with `ServiceLoader` auto-detection. Details: [ADR-0060](docs/0.10.0/ADR-0060-pluggable-payload-formats-and-media-type-aware-codec-spi.md).
- **Pluggable HTTP engines (`jrestly-engine-okhttp`, `jrestly-engine-apache5`)** — `HttpEngine` SPI with `ServiceLoader` discovery. Switch transport from default `java.net.http.HttpClient` to OkHttp 4 or Apache HC5 with full feature parity. Details: [ADR-0052](docs/0.10.0/ADR-0052-http-engine-spi-and-jdk-engine.md), [ADR-0058](docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md), and [BENCHMARKS.md](BENCHMARKS.md).
- **SSE stream resilience & streaming trailers** — `ServerSentEvent.reconnecting(...)` with `Last-Event-ID` replay, backoff jitter, idle stall guard (`stallTimeout`), and HTTP/2 lazy trailer completion on streaming responses. Details: [ADR-0059](docs/0.10.0/ADR-0059-sse-stream-resilience-and-streaming-trailer-completion.md).
- **Resilience & observability SPIs** — built-in sliding window `CircuitBreaker`, token bucket `RateLimiter` (ADR-0043), W3C TraceContext correlation ID propagation (ADR-0042), and rich `ClientMetricsListener` / `CallMetrics` (ADR-0036).
- **SSL / mTLS, proxy & cookie persistence** — custom certificates and mutual TLS (ADR-0020), HTTP proxy configuration with transport authentication (ADR-0037), and pluggable `CookieStore` persistence (ADR-0032, ADR-0039).
- **Declarative enhancements** — `@Timeout` per method/interface (ADR-0027), `@WithInterceptor` with `@Order` declaration sorting (ADR-0029, ADR-0044), `@QueryCollectionFormat` (ADR-0034), `@Compress` request compression (ADR-0041), and progress listeners (ADR-0030, ADR-0033).
- **Code quality control & JaCoCo baseline** — automated JaCoCo 0.8.12 coverage gates integrated into the Gradle lifecycle (~89.4% instruction coverage, 98.6% class coverage across 141 classes). Details: [ADR-0061](docs/0.10.0/ADR-0061-competitive-analysis-and-quality-control-baseline.md).

### Changed

- Hot-path dispatch pipeline completely rewritten for zero allocations and zero reflection via cached `MethodMetadata` descriptors (~5–9 µs overhead). Details: [ADR-0051](docs/0.10.0/ADR-0051-method-metadata-caching-and-proxy-dispatch-optimization.md), [ADR-0055](docs/0.10.0/ADR-0055-zero-garbage-dispatch-pipeline-and-hot-path-allocation-pruning.md).
- Eager validation in `ControllerValidator` expanded to 100% fail-fast verification of all annotations and parameter declarations at proxy creation time.

The core remains zero-dependency: the generated POM has 0 runtime `<dependency>`, and all external adapters/engines are optional submodules or `compileOnly`.

## 0.9.0 — 2026-10-14 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **Response byte streaming (`InputStream` return type)** — zero-copy
  streaming of raw response bytes directly into user code without whole-body
  buffering. The JSON codec is bypassed on success, while `@OnError` and
  unexpected status responses consume the stream and preserve typed error
  mapping (`CompletableFuture<InputStream>` twin included). Details: the
  [Response streaming](README.md#response-streaming) README section,
  `HttpTemplate` Javadoc, and
  [ADR-0015](docs/0.9.0/ADR-0015-response-streaming-inputstream-return.md).
- **Server-Sent Events & line streaming (`Stream<ServerSentEvent>`, `Stream<String>`)** —
  lazy streaming of WHATWG Server-Sent Events and newline-delimited payloads
  (NDJSON), tailored for real-time APIs and LLM token feeds with built-in
  terminal `[DONE]` marker detection (`event.isDone()`). Network resources
  are bound to Stream lifecycle with deterministic cleanup via
  `Stream.onClose()` (`CompletableFuture<Stream<...>>` twins included).
  Details: the
  [Server-Sent Events & line streaming](README.md#server-sent-events--line-streaming)
  README section, `ServerSentEvent` Javadoc, and
  [ADR-0016](docs/0.9.0/ADR-0016-sse-line-events.md).
- **Request parameter & header flexibility** — nullable `@RequestParam` and
  `@RequestHeader` arguments are gracefully omitted, dynamic `Map<?, ?>`
  query and header expansion with `name[key]` nesting support, repeatable
  class-level and method-level `@RequestHeader` and `@RequestDefaultParam`
  declarations with interface inheritance, and deterministic override
  precedence. Passing `null` to `@PathVariable` fails fast with an actionable
  exception. Details: the
  [Request parameter & header flexibility](README.md#request-parameter--header-flexibility)
  README section, annotation Javadocs, and
  [ADR-0017](docs/0.9.0/ADR-0017-request-parameter-flexibility.md).
- **Request pipeline: Interceptors & hooks** — pre-execution `RequestInterceptor`
  pipeline for header injection, authentication, and URL rewriting (via
  `WireRequest` builder/withers) and post-execution `ResponseInterceptor`
  pipeline for telemetry, audit, and logging with thread-safe
  `RequestContext`/`ResponseContext` attribute passing. Fault-isolated hooks,
  zero-overhead empty path, and complete symmetry across synchronous and
  asynchronous methods. Details: the
  [Request pipeline: Interceptors & hooks](README.md#request-pipeline-interceptors--hooks)
  README section, `RequestInterceptor` Javadoc, and
  [ADR-0018](docs/0.9.0/ADR-0018-request-pipeline-interceptors.md).

The core remains zero-dependency: the generated POM has no
`<dependency>`, and all external adapters remain `compileOnly`.

## 0.8.0 — 2026-10-14 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Changed

- **The shipped Javadoc is now normatively complete** — the whole
  normative main surface (every public and protected member) carries a
  comment: 237 javadoc warnings → 0 under `-Xdoclint:all`. Details:
  [ADR-0013](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md)
  and the Javadoc itself.
- **Serialization compatibility pinned** — the three exception classes
  (`ControllerValidationException`, `HandledException`,
  `UnexpectedStatusException`) carry an explicit `@Serial`
  `serialVersionUID`. Details:
  [ADR-0013](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md).
- **All 12 compiler warnings fixed** (9 main / 3 test; suppression
  targeted, never blanket, each carrying a one-line rationale). Details:
  [ADR-0013](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md).
- **`--release 21`** — the bytecode target is unchanged; compilation now
  also checks calls against the JDK 21 API on newer toolchains. Details:
  [ADR-0013](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md).
- **Permanent build gates** — `javac -Xlint:all -Werror` (both compile
  tasks) and `javadoc -Xdoclint:all -Werror` run in every build: a new
  public/protected member without a comment, or a new compiler warning,
  fails the build. Details:
  [ADR-0013](docs/0.8.0/ADR-0013-compiler-and-javadoc-warnings-audit.md).

The core remains zero-dependency: the generated POM has no
`<dependency>`.

## 0.7.0 — 2026-10-13 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **`CompletableFuture<T>` controller return types** — non-blocking
  dispatch through `HttpClient.sendAsync`; the future completes with what
  the synchronous twin would return, and failures arrive as the same
  exception types (as the cause of the exceptional completion). Details:
  `HttpTemplate` Javadoc.
- **`JacksonCodec.LocalDateTimeFormat`** — a configurable `LocalDateTime`
  wire format (`SPACE`, the default, or `ISO_8601`); the no-arg
  `defaults()` stays byte-identical for existing consumers. Details:
  `JacksonCodec` Javadoc.
- **Gson and Moshi codec adapters** — the JSON codec is now autodetected
  from the classpath (jackson → gson → moshi; an explicit `jsonCodec(...)`
  always wins). The POM is unchanged (adapters are `compileOnly`).
  Details: [ADR-0007](docs/0.7.0/ADR-0007-gson-moshi-codec-adapters.md)
  and the adapters' Javadoc (including the `java.time` caveats per codec).
- **Retry with backoff** — client-wide `retry(RetryPolicy)` or per-method
  `@Retry`; off by default; transport failures and the transient status
  family, capped exponential backoff with full jitter, `Retry-After`
  honored, uniform across sync and `CompletableFuture` methods. Details:
  the [Retry with backoff](README.md#retry-with-backoff) README section
  and [ADR-0008](docs/0.7.0/ADR-0008-retry-with-backoff.md).
- **Opt-in payload-decode retry** — a status-acceptable response the
  codec cannot bind is retried under a separate opt-in
  (`retryDecodeFailures`, default off); "enable retry" stays a zero
  behavior change on the success path. Details: the README section and
  [ADR-0011](docs/0.7.0/ADR-0011-retry-payload-decode-failure.md).

### Fixed

- PUT request bodies now appear in the INFO request log.
- A `void`-returning method with a non-empty body on an expected status
  now gets the `Void` semantics (null, body discarded) instead of a codec
  exception under Gson and Moshi.
- `Retry-After` accepts the HTTP-date form in addition to delta-seconds
  (all three RFC 9110 HTTP-date forms; retry itself is new in this
  release). Details:
  [ADR-0010](docs/0.7.0/ADR-0010-retry-after-obsolete-http-date-forms.md).

The core remains zero-dependency: the generated POM has no
`<dependencies>`, and every codec adapter stays `compileOnly`.
