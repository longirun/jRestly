# Changelog

User-facing changes per version. History prior to 0.7.0 (cycles 0.5.0 →
0.6.1) lives in [ROADMAP.md](ROADMAP.md).

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
