# ADR-0070: Netty Transport Engine Submodule (`jrestly-engine-netty`)

- **Status:** accepted
- **Date:** 2026-10-19 (session #323, accepted in #325)
- **Scope:** Cycle 0.12.0 flagship epic — high-performance non-blocking transport engine
- **Context:** Inquiry and direction from maintainer Anton (mailbox 2026-10-19 18:45: *"А как думаешь если добавить возможность использовать netty? Видимо это даст нам возможность выжать максимум? Интересно, а с ним какие будут результаты?"*)
- **Companion ADRs:** ADR-0052 (HttpEngine SPI), ADR-0058 (External Engine Submodules: OkHttp & Apache5), ADR-0068 (Feed Fetch & Parse Real-World Benchmark)

---

## 1. Context & Problem Statement

Following the completion of the 0.11.0 cycle (ADR-0069), `jRestly` established both architectural maturity and empirical validation:
1. **Zero-Garbage Dispatch Pipeline & Core Overhead:** Synthetic benchmarks (session #287) and real scenario profiling (ADR-0068) verified that `jRestly`'s declarative dynamic proxy, routing, and dispatch pipeline introduce virtually zero measurable overhead over the raw transport:
   - +6.3 KB allocations on 20 KB feeds;
   - +42 KB allocations on 1.05 MB feeds;
   - ~6.6 µs/op dispatch overhead.
2. **Transport as the Decisive Performance Factor:** The `FeedBenchmark` matrix (ADR-0068 run #320) revealed that the actual network transport and memory model dominate throughput and memory efficiency:
   - On 1 MB payloads, `jRestly + engine-apache5 (streaming)` beat raw `OkHttp` and `Retrofit 2` (30.4 ops/s vs 24.6 and 27.2 ops/s; 3.38 MB/op vs 3.42 and 4.47 MB/op).
   - Conversely, the default JDK `HttpClient` yielded 15.7 ops/s and 4.64 MB/op.
3. **The Limits of Socket-Based Classic Transports:** Both `OkHttp 4` and `Apache HttpClient 5 (classic)` rely on blocking sockets (`java.net.Socket`), synchronous I/O, and on-heap byte buffers. Under high concurrency or massive data streams, these run into:
   - Heap allocation churn from intermediate byte arrays, placing pressure on the JVM garbage collector;
   - Thread contention or thread pool saturation when multiplexing thousands of concurrent connections.

**Netty** (`io.netty`) is the industry gold standard for ultra-high-throughput, asynchronous, non-blocking networking on the JVM:
- **Off-Heap Pooled Memory (`ByteBuf`):** Netty's `PooledByteBufAllocator` manages off-heap direct buffers via a jemalloc-inspired pool. This virtually eliminates JVM heap allocation churn during socket reads and writes.
- **EventLoop Architecture:** A small, fixed set of I/O threads (typically `2 * cores`) multiplexes hundreds or thousands of channels via non-blocking selectors (`NIO`, `epoll`, `io_uring`).
- **Zero-Copy Transfers:** Netty allows passing network buffers directly to downstream consumers without intermediate heap byte array duplication.

The question raised by Anton: *Can we plug Netty into jRestly to squeeze maximum throughput and minimal heap memory, and what will the empirical results look like compared to Apache5 and OkHttp?*

---

## 2. Invariants & Architectural Boundaries

1. **Zero-Dependency Core:**
   - The root project (`ru.jrestly:jrestly`) remains 100% pure Java 21 with **zero external dependencies** in its published POM.
   - No Netty classes, interfaces, or types are referenced anywhere in `src/main/java` of the core module.
2. **Isolated Submodule Packaging:**
   - Netty transport support is packaged in a dedicated, published submodule: `ru.jrestly:jrestly-engine-netty` (`engine-netty/`).
   - Users opt in by adding a single Gradle/Maven dependency:
     ```groovy
     implementation 'ru.jrestly:jrestly-engine-netty:0.12.0'
     ```
3. **Pluggable Discovery via SPI:**
   - Like `engine-okhttp` and `engine-apache5`, `jrestly-engine-netty` implements `ru.jrestly.engine.HttpEngineProvider` via Java `ServiceLoader` (`META-INF/services/ru.jrestly.engine.HttpEngineProvider`).
   - Declares `priority() = 60` (higher than the default 50 of classic engines) or configurable explicitly via `JRestlyClient.builder().httpEngine(new NettyHttpEngine())`.
4. **Exact Parity with Core Contract:**
   - Must honor all `HttpEngineOptions` semantics: timeouts, version selection, streaming responses (`WireResponse.stream()`), upload/download progress listeners, passive compression (ADR-0021/0058), and proper resource cleanup.

---

## 3. Engineering Design of `NettyHttpEngine`

### 3.1 Dependencies
`engine-netty` will depend on curated Netty 4.1.x components (target `4.1.118.Final`):
- `io.netty:netty-codec-http`: HTTP/1.1 encoder, decoder, chunked transfer handling, HTTP headers, and status types.
- `io.netty:netty-handler`: TLS/SSL engine integration via `SslHandler` and `SslContext`.
- `io.netty:netty-transport`: Channels, EventLoopGroups, and ChannelPool infrastructure.
- `io.netty:netty-buffer`: Pooled off-heap allocator.

### 3.2 Client Connection Pooling (`ChannelPoolMap`)
Unlike Netty servers which bind to a single local port, an HTTP client communicates with arbitrary remote endpoints (`scheme://host:port`).
- `AbstractChannelPoolMap<InetSocketAddress, FixedChannelPool>`:
  - Dynamically creates and caches a `FixedChannelPool` per remote target host and port.
  - Sizing configured from `ModuleInfo` or default (e.g. 50 connections per route, 200 total).
  - Health checks: `ChannelPoolHandler.channelAcquired()` verifies `channel.isActive()`.
  - HTTP Keep-Alive: Upon completion of a response, healthy connections are returned to the pool for reuse.

### 3.3 Channel Pipeline Composition
For an acquired connection, the pipeline is configured with:
1. `SslHandler` (if `https://`): Initialized from `ModuleInfo.getSslContext()` using Netty's `SslContextBuilder`.
2. `HttpClientCodec`: Encodes `HttpRequest` and decodes incoming `HttpResponse` / `HttpContent` frames.
3. `HttpContentDecompressor` *(passive transport mode)*:
   - Following ADR-0021 and ADR-0058, transparent decompression is disabled by default to maintain parity with `JdkHttpEngine`. Decompression is managed deterministically by the declarative layer.
4. Mode-specific response handling:
   - **Buffered Mode (`!options.streamResponse()`):** `HttpObjectAggregator(maxContentLength)` aggregates chunks into a `FullHttpResponse`, returning a `byte[]` payload in `WireResponse`.
   - **Streaming Mode (`options.streamResponse()`):** Forwarded directly to the streaming bridge without aggregation.

### 3.4 Push-to-Pull Streaming Bridge (`NettyChunkedInputStream`)
Netty is push-based (reactive event loops push `HttpContent` chunks to channel handlers), while Java's `@Get InputStream` consumption (such as our StAX `FeedParser`) is pull-based (`in.read(buf, 0, len)`).
To bridge this safely without memory exhaustion:
1. **Bounded Chunk Queue:** A bounded `LinkedBlockingQueue<ByteBuf>` (e.g. capacity = 4-8 chunks).
2. **Backpressure via AutoRead:**
   - While the queue has capacity, `channel.config().setAutoRead(true)` reads incoming network packets.
   - If the parser processes slowly and the queue reaches capacity, `channel.config().setAutoRead(false)` suspends socket reads at the TCP level (triggering TCP zero-window backpressure to the server).
   - As the reader drains chunks, `setAutoRead(true)` resumes reads.
3. **Zero Buffer Leaks (`ByteBuf.release()`):**
   - Each chunk retained from Netty must have its reference count decremented via `ByteBuf.release()` immediately after its bytes are transferred to the caller's buffer.
   - The stream's `close()` method drains any unread chunks from the queue and releases them, ensuring zero off-heap leaks.

### 3.5 Asynchronous Native Execution
- `sendAsync(request, options)` returns a `CompletableFuture<WireResponse>` completed directly by Netty's channel handler callbacks upon receiving the response headers/body.
- No auxiliary worker threads or blocking loops are spawned.
- Synchronous `send(request, options)` simply delegates to `sendAsync(request, options).get(timeout)`.

---

## 4. Implementation Phases

- **Phase 1: Submodule Scaffolding & Build Configuration (Session #323) — COMPLETE ✅**
  - Register `engine-netty` in `settings.gradle`.
  - Create `engine-netty/build.gradle` matching the multi-module standards (Java 21, `-parameters`, `-Werror`, `javadoc -Werror`, `maven-publish`).
  - Draft ADR-0070 (this document).
- **Phase 2: Core Implementation & Parity Test Suite (Session #324) — COMPLETE ✅**
  - Implement full pipeline in `NettyHttpEngine`: request building, headers/query/methods, status.
  - Implement connection pooling via Netty `FixedChannelPool` and `AbstractChannelPoolMap` with keep-alive channel reuse.
  - Implement push-to-pull `NettyChunkedInputStream` streaming bridge with credit-based backpressure, thread unblocking on `close()`, and leak-free off-heap buffer release under `PARANOID` level.
  - Implement `PublisherInputStream` for multipart bodies (`MultipartBodyPublisher`) and streaming request body publishers.
  - Full 21-test parity test suite in `NettyHttpEngineParityTest` against WireMock (streaming, progress listeners, chunked transfer, trailers, passive compression, call timeouts, cancellation).
  - All 26 tests in `:engine-netty:test` green, zero off-heap leaks.
- **Phase 3: Integration into `FeedBenchmark` & Performance Verification (Session #325) — COMPLETE ✅**
  - Added `project(':engine-netty')` to `benchmarks/build.gradle`.
  - Added 12th/13th benchmark arms to `FeedBenchmark`: `jrestlyNetty_buffered` and `jrestlyNetty_streaming`.
  - Extended sanitary parity tests `FeedBenchmarkTest` and `FeedBenchmarkClientsTest` (+1 test, total suite 1,737/1,737 tests green).
  - Executed JMH benchmark with `-prof gc` and CPU probe across all four engines (JDK, OkHttp, Apache5, Netty) and 5 competitors.
  - Empirical verification:
    * **Record Heap Allocations on Small Payloads:** `jRestly + engine-netty (streaming)` achieved an absolute record **98,342 B/op** on `rss-20` (lower than raw OkHttp 98,725 B and Apache5 101,141 B) due to Netty's off-heap pooled buffer allocator (`PooledByteBufAllocator`).
    * **Streaming Memory Savings:** Streaming on Netty cuts heap allocations by **6.26 MB/op** on 1 MB feeds (`rss-1000`: 3.49 MB vs 9.75 MB).
    * **Throughput & CPU Superiority over JDK:** Netty achieved **308.3 ops/s** and **6.39 ms CPU/op** on `rss-20`, substantially beating default JDK transport (201.5 ops/s, 9.75 ms CPU/op) and OpenFeign (201.2 ops/s, 9.42 ms CPU/op).
  - Published comprehensive results in `BENCHMARKS.md`.

---

## 5. Consequences & Risks

### Positive Consequences
- **Maximized Throughput:** Native non-blocking I/O with EventLoops allows `jRestly` to reach the highest possible requests-per-second on concurrent workloads.
- **Minimal Heap Pressure:** Off-heap buffer pooling allows processing large multi-megabyte payloads with minimal heap garbage collection overhead.
- **Completeness of Ecosystem:** `jRestly` offers the full spectrum of transports: 100% zero-dependency JDK (default), industry-standard OkHttp, enterprise Apache HttpClient 5, and high-performance Netty.

### Risks & Mitigations
- **Off-Heap Resource Leaks:** Netty pooled buffers must be explicitly released.
  *Mitigation:* Comprehensive unit tests executed with `-Dio.netty.leakDetection.level=PARANOID` to catch any unreleased `ByteBuf` in test runs.
- **Backpressure Complexity:** Push-to-pull streaming could block event loops if improperly bridged.
  *Mitigation:* Dedicated channel handler passing chunks across the bounded queue; reader threads execute on caller/virtual threads and never stall the Netty I/O EventLoop.
