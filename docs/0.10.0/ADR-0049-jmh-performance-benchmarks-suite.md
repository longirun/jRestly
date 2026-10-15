# ADR-0049: JMH Performance Benchmarks Suite & Comparative Profiling

## Status
Accepted

## Context
As `jRestly` reaches architectural maturity, production readiness and performance become paramount questions for prospective users and maintainers. In mailbox discussions, the maintainer specifically inquired:
> "3. Тестирование производительности и бенчмарки. Особенно интересно сравнение с ретрофитом и прочими схожими библиотеками и фреймворками."
> "2. Твоя ниша — идеальный декларативный HTTP-клиент для современной Java. На сегодня я им пользуюсь для автотестов, но никто не говорит, что когда-нибудь (если библиотека станет достаточно зрелой, надежной и производительной) я не захочу использовать ее для вызовов сторонних интеграций в реальных продуктах."

To substantiate `jRestly`'s performance characteristics, we need a rigorous, repeatable JMH (Java Microbenchmark Harness) benchmarking suite that measures:
1. **Dispatch & Abstraction Overhead:** The CPU nanoseconds and memory allocation costs incurred by the dynamic proxy, request metadata resolution, and response deserialization pipeline in isolation.
2. **End-to-End Network Throughput and Latency:** Performance across real HTTP/1.1 transport (against an embedded, high-throughput JDK `HttpServer` over loopback TCP sockets) comparing:
   - Raw Java 21 `java.net.http.HttpClient` (unwrapped baseline),
   - `jRestly` declarative client,
   - Square `Retrofit 2` (OkHttp engine),
   - Netflix/OpenFeign (`feign-core` with `feign-java11` engine sharing the same underlying `HttpClient`).
3. **Zero-Dependency Raw Mode Performance:** Throughput of unparsed text/binary responses (`String`, `byte[]`) where `jRestly` operates without third-party JSON codecs.

### Key Invariants
1. **Zero-Dep Core Invariant:** The root `jRestly` library must remain 100% zero-dependency at runtime. The root POM publication must contain exactly 0 dependencies. Benchmarking libraries (`jmh`, `retrofit`, `feign`, `okhttp`) must never pollute the core JAR, POM, or production classpath.
2. **Isolation via Subproject:** All benchmarking code, harness scripts, and third-party comparison dependencies reside in an isolated `benchmarks/` Gradle subproject.
3. **CI/Build Verifiability:** The benchmark suite must include automated smoke tests (`BenchmarkSmokeTest`) executed during `./gradlew test` to ensure that benchmarks never drift out of sync with API changes.
4. **Reproducibility:** A dedicated Gradle task `task jmh(type: JavaExec)` allows developers and CI runners to execute targeted or full JMH runs with configurable parameters.

## Decision

### 1. Subproject Structure
- Add `benchmarks` subproject to `settings.gradle`:
  ```groovy
  rootProject.name = 'jRestly'
  include 'benchmarks'
  ```
- Subproject layout:
  ```
  benchmarks/
  ├── build.gradle
  └── src/
      ├── main/java/ru/jrestly/benchmark/
      │   ├── dto/UserDto.java
      │   ├── server/BenchmarkHttpServer.java
      │   ├── client/
      │   │   ├── JRestlyBenchmarkClient.java
      │   │   ├── RawHttpClientBenchmarkClient.java
      │   │   ├── RetrofitBenchmarkClient.java
      │   │   └── FeignBenchmarkClient.java
      │   ├── DispatchOverheadBenchmark.java
      │   └── HttpNetworkBenchmark.java
      └── test/java/ru/jrestly/benchmark/
          └── BenchmarkSmokeTest.java
  ```

### 2. Comparative Client Configurations
All comparative clients are configured with identical semantics:
- Target endpoint: loopback HTTP server with keep-alive connections.
- Identical JSON payload and data model (`UserDto` record with `id`, `name`, `email`, `active`, `tags`, `createdAt`).
- Same underlying JSON engine (`jackson-databind` 2.18.2).
- Equivalent timeout and connection settings.

### 3. Execution & Profiling Support
- The `benchmarks` Gradle configuration defines:
  ```groovy
  tasks.register('jmh', JavaExec) {
      classpath = sourceSets.main.runtimeClasspath
      mainClass = 'org.openjdk.jmh.Main'
      // options configurable via -PjmhArgs="..."
  }
  ```
- Support for JMH GC profiler (`-prof gc`) to measure allocations per invocation (`B/op`).

## Consequences

### Positive
- Transparent, scientifically rigorous performance comparison against industry standards (Retrofit, OpenFeign, and raw JDK HttpClient).
- Strict zero-dependency core is preserved; root publication POM remains 100% dependency-free.
- Continuous regression prevention through `BenchmarkSmokeTest`.
- Clear visibility into GC churn and execution latency of `jRestly`'s dynamic proxy pipeline.

### Neutral / Trade-offs
- Adding a Gradle subproject requires configuring cross-project dependencies (`implementation project(':')`).
- JMH benchmarks require dedicated warm-up and measurement iterations for statistically significant results.
