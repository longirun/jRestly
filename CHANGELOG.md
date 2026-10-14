# Changelog

User-facing changes per version. History prior to 0.7.0 (cycles 0.5.0 →
0.6.1) lives in [ROADMAP.md](ROADMAP.md).

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
