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

## Honest limitations

- **Loopback, single machine, single thread.** No TLS handshake cost, no real
  network RTT, no noisy-neighbor packet dynamics. Absolute µs values are
  machine-specific; the *relative* picture is what transfers.
- **Noise.** The machine is shared; per-client spread between runs reached tens
  of percent. Trust group order of magnitude, not 10 %-level gaps.
- **API shapes differ.** Raw clients here perform the same request but no
  interface mapping, retry, interceptors, progress, or error mapping. These
  numbers compare transport floors, not feature sets.
- **One JSON payload shape.** Small DTO and a small text body; large payloads,
  streaming, SSE and multipart are not measured here.
- **JMH with 1 fork has limited statistical power** by design (quick runs); use
  `-f 2 -wi 5 -i 10` for publishable runs on a quiet machine.

## Reproducing

```bash
# full JMH suite (defaults: -f 1 -wi 2 -i 3 -tu us -bm avgt)
./gradlew :benchmarks:jmh

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
  streaming, progress) runs on top. Wire the engine explicitly:
  `JRestlyClient.builder().httpEngine(new OkHttpEngine())`. `ServiceLoader`
  auto-detection is the planned next step of
  [ADR-0058](docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md);
  until it lands, the explicit constructor is the supported path.

---

*The in-memory dispatch overhead (`DispatchOverheadBenchmark`, ~5–9 µs/op for a
mocked round-trip through the proxy and pipeline vs ~1.3 µs/op for a bare Jackson
binding of the same payload) is measured with the same harness and is updated
alongside it.*
