# Changelog

User-facing changes per version. History prior to 0.7.0 (cycles 0.5.0 →
0.6.1) lives in [ROADMAP.md](ROADMAP.md).

## 0.7.0 — 2026-10-13 (unpublished)

> *Unpublished — no git tag. Per the maintainer's versioning rule the
> version lives in `build.gradle` and the release commit message; the
> tag-based coordinates (JitPack) remain at `v0.6.1` until the maintainer
> tags.*

### Added

- **`CompletableFuture<T>` controller return types.** Controller methods
  may declare `CompletableFuture<T>` as their return type (for any wire
  type `T` the synchronous twin supports, including `Void`, `String`, raw
  and generic). The call is dispatched non-blockingly through
  `HttpClient.sendAsync` and the future completes with exactly what the
  synchronous twin would return; failures arrive as the same exception
  types (`HandledException`, `UnexpectedStatusException`, transport
  `RuntimeException`) as the cause of the exceptional completion
  (`join()` → `CompletionException` cause, standard JDK semantics).

- **Configurable `LocalDateTime` wire format in `JacksonCodec`.**
  `JacksonCodec.LocalDateTimeFormat` — `SPACE`, the classic
  `yyyy-MM-dd HH:mm:ss` (the default), or `ISO_8601` with the `T`
  separator — and a `defaults(LocalDateTimeFormat)` factory. The no-arg
  `defaults()` delegates to `SPACE`, so existing consumers stay
  byte-identical. Both modes share the optional fractional-seconds tail
  (printed only when the value carries sub-second precision) and the same
  lenient parser — both the `T` and the space separator parse in every
  mode, so a legacy space-based server keeps working in ISO mode on read.

- **Gson and Moshi codec adapters.** `GsonCodec` and `MoshiCodec`
  alongside `JacksonCodec`: Jackson 2, Gson, or Moshi on the classpath is
  now enough to get a working client (probe chain jackson → gson → moshi,
  first hit wins; an explicit `jsonCodec(...)` always wins over the
  probe). The public API and the POM are unchanged — the adapters are
  `compileOnly`.
  - `GsonCodec` requires Gson 2.8.3+ (validated against 2.14.0). Note:
    since Gson 2.14, `java.time` values serialize in Gson's frozen
    pre-2.14 wire form (non-ISO, `{"date":{...},"time":{...}}`; `Date` as
    a locale string with second precision) — bring an explicit `Gson`
    with registered adapters if you need a different form.
  - `MoshiCodec` (Moshi 1.x, validated against 1.15.2) uses a plain
    builder: there is no standard `java.time` add-on in the Moshi 1.x
    world, so `java.time`/`java.util.Date` values go through an explicit
    `Moshi` with user-registered adapters; concrete `java.util`
    collection classes (`Map.of(...)`, `HashMap`) are rejected with a
    clear `JsonDataException` naming the type.

- **Retry with backoff.** A failed call is repeated with exponential
  backoff (capped) and full jitter for transport failures and the
  transient status family (`408`, `429`, `500`, `502`, `503`, `504`).
  **Off by default** — zero behavior change until enabled.
  - Two configuration levels, the method wins: a client-level
    `RetryPolicy` (`retry(...)` on `JRestlyClient.Builder` /
    `SimpleModuleInfo.Builder`, via the new
    `ModuleInfo.getRetryPolicy()`) and a per-method `@Retry` — a
    self-contained policy that replaces the client's entirely (a bare
    `@Retry`, `times = 1`, is the explicit off-switch).
  - GET/PUT/DELETE are retried; POST/PATCH only via
    `retryNonIdempotent` (the retry lands after the request was sent). A
    `Retry-After` header sets a deterministic lower bound on the wait;
    `InterruptedException` is never retried (fail fast).
  - `@OnError.statuses` remain a terminal contract by default (the
    declared contract wins); `retryOnErrorStatuses` opts them into
    retry, and the final failure stays a typed `HandledException`.
  - One policy covers both sync and `CompletableFuture` methods; the
    async wait runs on `CompletableFuture.delayedExecutor` (no parked
    virtual thread per retry).

- **Opt-in payload-decode retry.** A status-acceptable response whose body
  the codec cannot bind — the bytes arrived in full but do not fit the
  declared type — is the retry loop's third failure-kind (transport /
  status / decode), retried under a separate opt-in
  `retryDecodeFailures` (default `false`; a `RetryPolicy` builder
  attribute and an `@Retry` attribute). "Enable retry" stays a zero
  behavior change on the success path. On exhaustion the final failure is
  the codec's own unchecked exception — no new type, no wrapping. Uniform
  across sync and async.

### Fixed

- **PUT request bodies now appear in the INFO request log** (previously
  only POST, PATCH and DELETE bodies were logged; GET/HEAD stay out
  deliberately).
- **A `void`-returning method with a non-empty body** on an expected
  status now gets the `Void` form's semantics (null, body discarded)
  instead of reaching the codec — which threw a codec exception under
  Gson and Moshi (lenient default Jackson had masked the quirk).
- **`Retry-After` accepts the HTTP-date form** — in addition to
  delta-seconds, all three RFC 9110 HTTP-date forms (IMF-fixdate plus the
  obsolete RFC 850 and asctime(3) forms) now bound the retry wait; a past
  date floors the wait at 0. (Applies to users who opted into retry — new
  in this release.)

The core remains zero-dependency: the generated POM has no
`<dependencies>`, and every codec adapter stays `compileOnly`.
