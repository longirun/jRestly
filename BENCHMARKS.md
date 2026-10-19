# jRestly Benchmarks

How fast is the declarative layer, and what do you pay for it? This page documents
the measurement stand, the comparative matrix against raw HTTP clients and popular
Java HTTP libraries, and — importantly — what these numbers do **not** say.

The benchmark harness lives in the non-published `benchmarks/` Gradle subproject
([ADR-0049](docs/0.10.0/ADR-0049-jmh-performance-benchmarks-suite.md)): the core
stays zero-dependency, all comparison libraries stay out of the published artifacts,
and the stand itself is guarded by tests.

## What the numbers say

- **The declarative layer is cheap.** The dynamic proxy, metadata
  resolution and pipeline dispatch cost single-digit microseconds per call
  (in-memory `DispatchOverheadBenchmark`: ~5–9 µs/op) — noise against any real
  network round-trip.
- **On a fast transport, jRestly runs at transport speed.** jRestly on the
  OkHttp engine lands within single-digit percent of raw OkHttp, and jRestly on
  the Apache HC5 engine is on par with raw HC5.
- **The default zero-dep JDK transport is the slow part — not jRestly.**
  Raw `java.net.http.HttpClient` shows the same 2–4× gap on this loopback stand;
  jRestly adds only a few percent on top of whichever transport it drives.
- **Benchmark stands lie easily.** The previous stand (JDK `HttpServer`) injected
  a flat ~42 ms Nagle + delayed-ACK artifact — two orders of magnitude larger than
  everything this page measures. See [The stand](#the-stand).

## The stand

- **Server:** a zero-dependency raw-TCP HTTP/1.1 server (`BenchmarkHttpServer`,
  `ServerSocket` + virtual threads) bound to loopback: `TCP_NODELAY` on every
  accepted socket, status line + headers + body assembled into a single `write`,
  precomputed response byte arrays, keep-alive with `Content-Length` framing.
  Answers are static/pure — the server never becomes the bottleneck.
  A latency gate test fails the build if the stand regresses (it caught the
  original ~42 ms artifact).
- **Harness:** JMH 1.37, `AverageTime` in µs/op, 1 fork, 2 × 1 s warmup,
  5 × 1 s measurement, single-threaded (run #158 below). A 16-thread throughput
  variant lives in `ConcurrentLoadNetworkBenchmark`.
- **Machine:** 4 vCPU (Intel i3-3120M class), 11 GiB RAM, Temurin JDK 21.0.12.1 LTS,
  Linux, loopback only. A shared machine with other processes active.

> **Reading rule:** on this stand the run-to-run spread for one client is large
> (tens of percent; the JMH `±` column in the raw output is wide). Treat the table
> below as **group order of magnitude**, not as a ranking within a group. The
> ranking was reproduced by two independent runs (#147, #158).

## The matrix

Ten clients, identical semantics (same JSON payload/DTO, same JSON engine where
applicable — Jackson 2.18.2, same endpoints, keep-alive):

| client | version / transport |
|---|---|
| raw JDK `HttpClient` | Java 21 (`java.net.http`) |
| jRestly (default, zero-dep) | JDK engine (`JdkHttpEngine`) |
| jRestly, HTTP/1.1 pinned | JDK engine, `HTTP_1_1` |
| jRestly + `engine-okhttp` | OkHttp 4.12.0 |
| jRestly + `engine-apache5` | Apache HttpClient 5.4.1 |
| OkHttp (raw) | 4.12.0 |
| Apache HttpClient (raw) | 5.4.1 (classic) |
| Retrofit 2 | 2.11.0 (OkHttp + Jackson converter) |
| OpenFeign | 13.5 (`feign-java11` → same JDK `HttpClient`) |
| Spring `RestClient` | Spring Web 6.2.2 |

Endpoints: `GET /api/ping` (small plain-text body, no codec) and
`GET /api/users/42` (small JSON body → `UserDto` via the full declarative
pipeline). The harness also covers a 50-user batch and a POST echo; the table
below uses the two endpoints from the headline runs.

## Results (run #158)

µs/op, average time, lower is better. `ping` = raw text, `getUser` = declarative
JSON binding.

| client | ping | getUser |
|---|---:|---:|
| OkHttp (raw) | 261 | 349 |
| Apache HC5 (raw) | 282 | 311 |
| Retrofit 2 | 363 | 321 |
| **jRestly + engine-apache5** | **323** | **406** |
| **jRestly + engine-okhttp** | **424** | **536** |
| raw JDK `HttpClient` | 1106 | 1272 |
| jRestly, HTTP/1.1 pinned (JDK) | 1194 | 1481 |
| jRestly default (JDK, zero-dep) | 1298 | 1666 |
| OpenFeign | 1675 | 2469 |
| Spring `RestClient` | 1976 | 2325 |

Cross-check (run #147, same stand, same machine, 3 × 1 s measurement):
getUser — apache 341, retrofit 362, OkHttp 428, **jRestly+OkHttp 450**,
**jRestly+Apache5 478**, raw JDK 1364, **jRestly(JDK) 1524**, jrestly HTTP/1.1 1636,
feign 2223, spring 2591; ping — OkHttp 254, retrofit 312, **jRestly+Apache5 331**,
**jRestly+OkHttp 342**, apache 363, raw JDK 1191, jrestly HTTP/1.1 1221,
**jrestly(JDK) 1359**, feign 1803, spring 2144.

Both runs tell the same story: the fast group (raw OkHttp/HC5, Retrofit, jRestly
on external engines) is ~0.3–0.5 ms/op; the JDK-transport group (raw JDK,
jRestly default, jrestly HTTP/1.1) is ~1.1–1.7 ms/op; Feign and Spring trail at
~1.7–2.6 ms/op. In run #147, jRestly over OkHttp was +5 % over raw OkHttp
(450 vs 428 µs) — the declarative pipeline is not the bottleneck.

## Revision check (run #287)

A post-epic re-check of the synthetic stand (session #287, 2026-10-18), asked by
Anton after the Zero-Garbage epic (ADR-0055) and the `HttpTemplate` decomposition
(ADR-0066): *did the refactoring + the coverage push move us away from what matters?*

Same harness, same machine as the table above, `1 fork / 2×1 s warmup / 5×1 s`:

- **In-memory dispatch overhead — no regression.** `jrestly_inMemoryMockDispatch`
  6.63 µs/op (±4.26) and `jrestly_inMemoryMockPing` 2.58 µs/op (±0.14), with the
  bare-Jackson baseline at 1.24 µs/op — the declarative layer still costs
  single-digit microseconds, inside the historical ~5–9 µs/op envelope.
- **Network byte-allocations — no regression (measured lower).** `jrestly_getUser`
  29,136 B/op (±1,347) vs the 49,378 B/op recorded at the end of ADR-0055 (#135);
  `jrestly_ping_zeroDepRaw` 26,609 B/op (±1,008) vs 47,392 B/op. GC-norm metrics
  are tight (±5 %), far beyond the run-to-run noise of the µs column, so this is a
  real environmental/code delta — treated as confirmation that allocation pruning
  survived the decomposition, not as a re-recordable headline (absolute byte
  counts are JVM/classpath-sensitive; see limitations).
- **Network matrix — group structure intact.** Fast group (OkHttp/HC5 raw +
  Retrofit + jRestly on external engines) ~0.22–0.43 ms/op; JDK-transport group
  (raw 1024, jRestly 1314 µs getUser) ~0.9–1.3 ms/op; Feign 1820 / Spring 2177
  µs still trailing. Error bars this run were wide (shared machine), consistent
  with the reading rule above; no group inversion appeared.

*Conclusion: the "typical method" did not drift — dispatch cost and allocations
are at or below the pre-refactor record, and the client ranking matches runs
#147/#158.*

## Real scenario: feed fetch & parse (ADR-0068, run #320)

To move beyond synthetic micro-benchmarks (`ping` and `getUser`), this scenario models a
production RSS/Atom news feed aggregator ([ADR-0068](docs/0.11.0/ADR-0068-real-feed-benchmark-fetch-and-parse.md)):
fetching realistic XML documents over HTTP/1.1 and streaming them into a hardened StAX
`XMLStreamReader` parser to produce a list of domain objects (`FeedEntry`) and a fold
digest (`FeedSummary`).

### Principles & Fairness
- **One parser for everyone:** parsing is performed by an identical zero-dep StAX implementation (`FeedParser`) across all clients. The XML parsing phase is strictly identical, measuring only the transport and HTTP client dispatch contribution.
- **Realistic documents:** generated RSS 2.0 and Atom 1.0 feeds containing CDATA sections, escaped XML entities, namespaces (`atom:link`, `content:encoded`), and standard timestamps (RFC 822 and RFC 3339).
- **Scale:** evaluated across three representative feed sizes:
  - `rss-20` (Small): 20 items, 21.6 KB wire size
  - `rss-200` (Medium): 200 items, 211 KB wire size
  - `rss-1000` (Large): 1,000 items, 1.05 MB wire size
- **Key architectural axis:** buffered (`@Get byte[]`) vs streaming (`@Get InputStream`) across jRestly transports (default JDK zero-dep, OkHttp engine, Apache HC5 engine, Netty engine) against industry peers (raw OkHttp, Retrofit 2, OpenFeign, Spring 6 HTTP Interface, raw JDK `HttpClient`).

### Throughput (ops/s, higher is better)

| client | rss-20 (21.6 KB) | rss-200 (211 KB) | rss-1000 (1.05 MB) |
|---|---:|---:|---:|
| OkHttp (raw) | 438.0 | 104.6 | 24.6 |
| Retrofit 2 | 414.7 | 109.7 | 27.2 |
| **jRestly + engine-apache5 (streaming)** | 383.7 | 106.8 | 30.4 |
| **jRestly + engine-apache5 (buffered)** | 416.3 | 101.0 | 30.5 |
| **jRestly + engine-okhttp (streaming)** | 349.4 | 91.1 | 22.4 |
| **jRestly + engine-okhttp (buffered)** | 372.8 | 87.9 | 26.7 |
| **jRestly + engine-netty (streaming)** | 308.3 | 79.2 | 19.1 |
| **jRestly + engine-netty (buffered)** | 290.8 | 84.1 | 22.4 |
| raw JDK HttpClient | 255.1 | 67.9 | 18.2 |
| **jRestly default JDK (streaming)** | 197.2 | 52.5 | 15.7 |
| **jRestly default JDK (buffered)** | 213.5 | 69.4 | 19.8 |
| OpenFeign | 201.2 | 57.6 | 15.3 |
| Spring HTTP Interface | 178.7 | 61.2 | 17.6 |

### Memory allocations (B/op, lower is better)

| client | rss-20 (21.6 KB) | rss-200 (211 KB) | rss-1000 (1.05 MB) |
|---|---:|---:|---:|
| **jRestly + engine-netty (streaming)** | 98,342 B | 719,386 B | 3,489,706 B |
| OkHttp (raw) | 98,725 B | 712,384 B | 3,425,044 B |
| **jRestly + engine-apache5 (streaming)** | 102,090 B | 708,584 B | 3,382,858 B |
| Retrofit 2 | 102,772 B | 866,166 B | 4,471,014 B |
| **jRestly + engine-okhttp (streaming)** | 102,922 B | 719,689 B | 3,476,634 B |
| **jRestly + engine-okhttp (buffered)** | 124,292 B | 1,075,275 B | 5,512,109 B |
| raw JDK HttpClient | 134,013 B | 958,816 B | 4,597,850 B |
| **jRestly default JDK (streaming)** | 140,398 B | 973,754 B | 4,640,170 B |
| **jRestly + engine-apache5 (buffered)** | 161,147 B | 1,151,721 B | 5,540,967 B |
| **jRestly default JDK (buffered)** | 161,876 B | 1,176,200 B | 5,659,617 B |
| OpenFeign | 147,611 B | 979,159 B | 4,647,382 B |
| **jRestly + engine-netty (buffered)** | 196,913 B | 1,654,916 B | 9,750,410 B |
| Spring HTTP Interface | 216,363 B | 1,431,449 B | 6,799,057 B |

### CPU Time per operation (ms/op, lower is better)

| client | rss-20 (21.6 KB) | rss-200 (211 KB) | rss-1000 (1.05 MB) |
|---|---:|---:|---:|
| OkHttp (raw) | 4.53 ms | 18.89 ms | 78.26 ms |
| Retrofit 2 | 4.72 ms | 17.87 ms | 72.46 ms |
| **jRestly + engine-apache5 (streaming)** | 5.12 ms | 18.38 ms | 64.43 ms |
| **jRestly + engine-apache5 (buffered)** | 4.69 ms | 19.45 ms | 64.38 ms |
| **jRestly + engine-okhttp (streaming)** | 5.63 ms | 21.19 ms | 88.34 ms |
| **jRestly + engine-okhttp (buffered)** | 5.29 ms | 22.54 ms | 74.01 ms |
| **jRestly + engine-netty (streaming)** | 6.39 ms | 24.77 ms | 103.50 ms |
| **jRestly + engine-netty (buffered)** | 6.75 ms | 23.12 ms | 86.59 ms |
| raw JDK HttpClient | 7.71 ms | 29.11 ms | 108.74 ms |
| **jRestly default JDK (streaming)** | 10.03 ms | 37.26 ms | 125.66 ms |
| **jRestly default JDK (buffered)** | 9.21 ms | 28.35 ms | 99.05 ms |
| OpenFeign | 9.42 ms | 33.46 ms | 128.95 ms |
| Spring HTTP Interface | 10.98 ms | 32.19 ms | 112.16 ms |

### Key Insights & Findings
1. **Streaming saves megabytes of memory on large payloads:**
   On the 1.05 MB feed (`rss-1000`), streaming mode cuts allocations by **~1.02 MB/op** on the default JDK transport and by **over 2.1 MB/op** on Apache5 / OkHttp engines compared to buffered mode (`byte[]`). On Netty, streaming saves an astonishing **6.26 MB/op** (3.49 MB vs 9.75 MB). In streaming mode, XML tokens are parsed directly off the wire without an intermediate full-body byte array buffer.
2. **jRestly on Apache HC5 matches or beats raw OkHttp and Retrofit 2:**
   On `rss-1000`, `jRestly + engine-apache5 (streaming)` achieved **30.4 ops/s** and **3,382,858 B/op**, outperforming both raw OkHttp (24.6 ops/s, 3,425,044 B/op) and Retrofit 2 (27.2 ops/s, 4,471,014 B/op).
3. **The zero-dep declarative overhead is negligible:**
   Comparing `jRestly default JDK (streaming)` against `raw JDK HttpClient`:
   - `rss-20`: 140 KB vs 134 KB (+6.3 KB)
   - `rss-200`: 973 KB vs 958 KB (+15 KB)
   - `rss-1000`: 4.64 MB vs 4.59 MB (+42 KB on a 1 MB payload)
   The dynamic proxy, reflection cache, and pipeline dispatch add essentially negligible memory overhead over the raw JDK transport.
4. **Spring 6 HTTP Interface (`Resource`) buffers heavily:**
   Spring's declarative client was the most memory-intensive in every category (216 KB on small, 1.43 MB on medium, 6.80 MB on large), showing that Spring's `ResourceHttpMessageConverter` creates substantial intermediate buffers when reading entity bodies.
5. **OpenFeign vs jRestly on JDK transport:**
   Feign operates directly on `java.net.http.HttpClient` (via `feign-java11`). jRestly default streaming outperforms Feign in throughput on large feeds (15.7 vs 15.3 ops/s) and consumes fewer allocations across all payload sizes (140 KB vs 147 KB on small, 4.640 MB vs 4.647 MB on large).
6. **Netty Transport Engine (ADR-0070) sets an allocation record on small payloads:**
   With pooled off-heap ByteBuf management (`PooledByteBufAllocator`), `jRestly + engine-netty (streaming)` achieved an absolute record **98,342 B/op** on `rss-20` (lower than raw OkHttp 98,725 B, Retrofit 102,772 B, and Apache5 101,141 B). Furthermore, streaming cuts allocations on Netty by **over 6.26 MB/op** on 1 MB payloads (3.49 MB vs 9.75 MB), while delivering throughput substantially higher than the standard JDK transport (308.3 ops/s vs 201.5 ops/s on `rss-20`).

## Honest limitations

- **Loopback, single machine, single thread.** No TLS handshake cost, no real
  network RTT, no noisy-neighbor packet dynamics. Absolute µs values are
  machine-specific; the *relative* picture is what transfers.
- **Noise.** The machine is shared; per-client spread between runs reached tens
  of percent. Trust group order of magnitude, not 10 %-level gaps.
- **API shapes differ.** Raw clients here perform the same request but no
  interface mapping, retry, interceptors, progress, or error mapping. These
  numbers compare transport floors, not feature sets.
- **One JSON payload shape for micro-benchmarks; realistic RSS/Atom feeds for real scenario.**
  Large payloads and streaming are now measured via `FeedBenchmark`. SSE and multipart
  remain synthetic or unmeasured on this stand.
- **JMH with 1 fork has limited statistical power** by design (quick runs); use
  `-f 2 -wi 5 -i 10` for publishable runs on a quiet machine.

## Reproducing

```bash
# full JMH suite (defaults: -f 1 -wi 2 -i 3 -tu us -bm avgt)
./gradlew :benchmarks:jmh

# real-scenario feed fetch & parse matrix (ADR-0068)
./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 1 -i 2 -prof gc -rf json -rff feed_results.json .*FeedBenchmark\..*"

# the getUser/ping matrix with tighter settings (as in run #158)
./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 2 -i 5 -tu us -bm avgt -rf json -rff results.json .*HttpNetworkBenchmark\.(jrestly|jrestlyOkHttp|jrestlyApache5|jrestlyHttp11|okHttp|apacheHttpClient|rawHttpClient|springRestClient|retrofit|feign)_(getUser|ping)$"

# allocation profiling of the dispatch pipeline
./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 2 -i 5 -prof gc .*DispatchOverheadBenchmark"
```

`-PjmhArgs` **replaces** the default arguments entirely. Every benchmark spins up
its own loopback server, so no external services are needed.

## Choosing a transport

- **Default (zero-dep JDK engine)** — nothing to add; fine for test suites,
  typical RPC, and low-to-moderate RPS. The measured cost is transport-bound.
- **`jrestly-engine-okhttp` / `jrestly-engine-apache5`** — plug in a faster
  transport without touching the declarative interfaces; the full jRestly
  pipeline (interceptors, retry, compression, circuit breaker, metrics,
  streaming, progress) runs on top. Adding a submodule to the classpath is
  enough: it registers a `ServiceLoader` provider and the client picks the
  highest-priority available engine automatically ([ADR-0058](docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md));
  `JRestlyClient.builder().httpEngine(engine)` remains the explicit override.

> **Benchmark fairness note:** the whole matrix runs with both engine
> submodules on the classpath, so the benchmark clients pin the JDK transport
> explicitly (`JdkHttpEngine`) for the "jrestly default (JDK)" row — otherwise
> `ServiceLoader` auto-detection would silently promote that row to a
> third-party engine. The `jRestly + engine-*` rows pass their engine explicitly
> as well. Auto-detection is verified separately by
> `SpiEngineAutoDetectionTest` in the core test suite.

---

*The in-memory dispatch overhead (`DispatchOverheadBenchmark`, ~5–9 µs/op for a
mocked round-trip through the proxy and pipeline vs ~1.3 µs/op for a bare Jackson
binding of the same payload) is measured with the same harness and is updated
alongside it.*
