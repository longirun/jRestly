# ADR-0080: Concurrent-Load Scaling Check and Structural Health Audit

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Anton's mailbox request (2026-10-20): the library has no
  multi-threaded performance evidence yet — measure it "по уже использованным
  критериям" under different loads; and re-validate code quality ("победили ли
  год-классы и прочие болячки?").

## Decision 1 — concurrency load is a first-class benchmark mode

We reuse the existing stand instead of inventing a new one:

- **Network path:** `ConcurrentLoadNetworkBenchmark` (`getUser`, JSON DTO,
  shared client, `Mode.Throughput`) — the same clients and criteria as the
  single-threaded matrix (BENCHMARKS.md, runs #147/#158).
- **Dispatch path:** `DispatchOverheadBenchmark` (in-memory mock, no sockets) —
  isolates the declarative layer.
- **Load levels:** 1, 4, 16 and 64 threads, selected with the JMH CLI option
  `-t`, which overrides the class-level `@Threads(16)` (verified in JMH 1.37
  bytecode: `options.getThreads().orElse(entry.getThreads().orElse(1))`).
  No new benchmark classes are needed.

Reproducer:

```
./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 1 -i 2 -t 64 \
  -rf json -rff /tmp/scaling_network_t64.json \
  .*ConcurrentLoadNetworkBenchmark\..*GetUser"
```

### Methodological finding: AverageTime is not a valid load metric here

The host advertises 4 vCPUs but the cgroup quota is **2 CPUs**
(`cpu.max = 200000 100000`), and the machine is shared. At 64 JMH threads the
cgroup is throttled for most of each 100 ms period. In `Mode.AverageTime` the
throttled wall-clock wait is charged to every operation: even
`new String(byte[])` inflated from 23 ns to ~4.3 µs (185×). The dispatch
AverageTime table from this run is therefore **not published as scaling data** —
it measures cgroup throttling, not the library. `Mode.Throughput` (network
benchmark) is the right metric under a CPU quota: it stays meaningful when
threads are oversubscribed. This is recorded as a stand rule.

### Results (run #340, ops/s, higher is better)

Same host, loopback, keep-alive, `-f 1 -wi 1 -i 2`, shared client per arm.

| client | 1 thread | 4 threads | 16 threads | 64 threads |
|---|---:|---:|---:|---:|
| Apache HC5 (raw) | 2144 | 1192 | 1160 | 1928 |
| Retrofit 2 | 1489 | 1872 | 1664 | 1773 |
| jRestly + `engine-apache5` | 1601 | 1166 | 1157 | 1464 |
| jRestly + `engine-okhttp` | 1290 | 1257 | 1223 | 1435 |
| jRestly HTTP/1.1 pinned | 781 | 776 | 1029 | 1125 |
| raw JDK `HttpClient` | 537 | 549 | 702 | 888 |
| jRestly (default JDK) | 478 | 439 | 601 | 695 |
| Spring 6 `RestClient` | 338 | 327 | 330 | 356 |
| OpenFeign | 321 | 313 | 492 | 581 |

Interpretation, with the host caveat above:

1. **No collapse and no cross-group inversion under load.** The group order is
   the same as the single-threaded matrix: fast transports first, JDK transport
   next, Spring/Feign last. jRestly sits on top of whichever transport it drives.
2. jRestly + Apache5/OkHttp stay within ~10–25% of the fastest raw client at
   every load level; the default JDK engine tracks raw JDK within ~10–20%.
3. Throughput does not scale linearly beyond one thread because the 2-CPU quota
   is already saturated by client + server + GC. The table demonstrates
   **stability under oversubscription**, not linear scaling; a true scaling
   curve needs a quiet host with ≥8 dedicated cores.
4. 64 threads run to completion with zero errors/exceptions — a useful
   smoke-level thread-safety signal for the shared client (proxy cache,
   metadata cache, codec, cookie handling).

## Decision 2 — structural health: honest scorecard, not a victory lap

God-classes are **not** fully defeated. Facts from the current tree
(168 main-source files, 24,967 lines, JaCoCo XML from the #338 gate):

| class | lines | complexity | missed branches |
|---|---:|---:|---:|
| `http.HttpTemplate` | 2,286 | 461 | 80 |
| `http.HttpTemplateBuilder` | 1,516 | 407 | 110 |
| `SimpleModuleInfo` | 1,320 | ~90 (Builder) | 5 |
| `http.MethodMetadata` | 1,140 | 257 | 28 |
| `JRestlyClient` | 951 | 73 (Builder) | 0 |
| `problem.ProblemDetail` | 922 | 126 (+80 nested parser) | 32 |
| `ControllerValidator` | 871 | 279 | 10 |
| `http.ResponseHeaders` | 852 | 142 | 6 |

- **`HttpTemplate`:** 2,825 → 2,286 lines (−19%) and six collaborators were
  extracted (ADR-0066: `RetryAfter`, `Redirects`, `ProblemDetailResolver`,
  `CacheDispatch`, `ResponseBody`, `MetricsNotifier`). It remains the largest
  class and the #1 class by complexity — **still a god class**, now with a
  clearer seam map.
- **`HttpTemplateBuilder` is the new #1 missed-branch hotspot (110)** and #2 by
  complexity (407): URI assembly, header merge, body/multipart assembly and
  resilience wiring still live in one class. This is the natural target for the
  next structural wave (a second ADR, not silently bundled into this one).
- **`ControllerValidator.validateMethod` is the single most complex method
  (121)** — it aggregates many independent eager checks. It is a validator
  pipeline in one method; splitting checks into small named validators is the
  likely follow-up.
- `SimpleModuleInfo` / `JRestlyClient` are mostly builder surface (fluent
  setters), not algorithmic god classes; they are documented as lower priority.
- **"Прочие болячки":** dead code/dead branches were pruned in the 0.12.0
  audits; three real concurrency defects were fixed earlier (#181 proxy-cache
  `HashMap` race, #183 torn auth-header pair, #187 single-flight token clobber);
  `ResponseHeaders` collections are immutable; hot-path allocation work is
  documented in ADR-0055/0075–0078. Coverage at the #338 gate: Branch
  **90.81–90.85%**, Instruction 98.13%, Line 97.78%, Method/Class **100%**;
  Javadoc clean under `-Werror`; core POM has zero runtime dependencies.
- Concurrency architecture: a fresh `HttpTemplate` per invocation; shared state
  is limited to `ConcurrentHashMap` caches (`MethodMetadata.CACHE`,
  `AbstractHttpClient.controllers`) and thread-local trace/cookie holders. No
  hot-path monitor.

## Consequences

- `BENCHMARKS.md` gains a "Concurrency scaling (run #340)" section with the
  table, the reproducer and the cgroup caveat.
- A true scaling curve is a backlog item: rerun on a quiet ≥8-core host with
  `-wi 3 -i 3` and `-prof gc`.
- Next structural target: `HttpTemplateBuilder` decomposition (new ADR in
  0.13.0); `ControllerValidator.validateMethod` split is the second candidate.
- Next free ADR: **0081**.

## Amendment #341 — Netty arm added to the concurrency matrix

The #340 matrix accidentally omitted the Netty engine: `ConcurrentLoadNetworkBenchmark`
predates `engine-netty` and was never updated when the engine landed (only
`FeedBenchmark` got Netty arms in #325). Anton caught the gap. The benchmark now
measures **four** jRestly transports — JDK (default and HTTP/1.1-pinned), OkHttp,
Apache5 and Netty — in both `getUser` and `ping`, and `BenchmarkSmokeTest`
asserts every arm. Fresh numbers and the interpretation are published in
`BENCHMARKS.md` ("Netty in the concurrency matrix, run #341"). Lesson recorded:
when a new transport ships, the checklist is **all** benchmark matrices
(single-thread, concurrent, feed), not just the newest one.
