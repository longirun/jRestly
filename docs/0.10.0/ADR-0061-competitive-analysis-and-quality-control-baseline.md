# ADR-0061: Competitive Analysis, Strategic Feature Gaps & Quality Control Baseline

## Status

Accepted (2026-10-17, Session #236)

## Context

Following the completion of foundational milestones up to ADR-0060 (Zero-Garbage Dispatch Pipeline, Multi-Engine SPI with OkHttp/Apache5, Multi-Codec SPI with JSON/XML, Virtual Threads, and 100% fail-fast declarative annotation validation across 1258 tests), project sponsor Anton requested a comprehensive architectural assessment (Mailbox [2026-10-17 15:18]):
1. **Quality Control:** Evaluate and elevate code quality controls beyond compiler warnings and unit test suites.
2. **Competitive Landscape:** Benchmark and compare jRestly against industry standards (Retrofit, OpenFeign, Spring 6 HTTP Interfaces, OkHttp) to ensure maturity for high-load production use.
3. **Vital Gaps vs. Bloat:** Identify must-have production capabilities that are currently missing, while auditing existing features to prevent feature creep and bloat.

## Decision

### 1. Competitive Landscape & Feature Comparison Matrix

| Feature / Dimension | jRestly (0.9.0 / 0.10.0) | Retrofit 2 (Square) | OpenFeign (Spring Cloud) | Spring 6 HTTP Interfaces | OkHttp 4/5 (Square) |
|---|---|---|---|---|---|
| **Core Runtime Dependencies** | **Zero-dependency** (pure Java 21) | Requires OkHttp + annotations | Requires feign-core + reflection | Requires Spring Web + Spring Context | Okio, Kotlin stdlib |
| **Target Java Version** | **Java 21 LTS** (Virtual Threads, Records) | Java 8+ | Java 8+ / 11+ | Java 17+ | Java 8+ / Android |
| **Method Signature Return** | **Transparent POJO / CompletableFuture / Streaming** (ADR-0004) | Forced `Call<T>` or CallAdapters | Transparent POJO or `Response` | Transparent POJO, `ResponseEntity`, or Reactive | Low-level `Call` / `Response` |
| **Response Streaming** | Native `InputStream`, `Stream<String>`, `Stream<ServerSentEvent>` | ByteStream via `ResponseBody` | `Response.Body` stream | Reactive `Flux` or `InputStream` | `ResponseBody.byteStream()` |
| **Dispatch Latency & Allocations** | **~5–9 µs/op**, zero-garbage on hot path (ADR-0055) | ~8–15 µs/op | ~30–80 µs/op (reflection heavy) | ~25–60 µs/op | N/A (low-level client) |
| **Validation at Startup** | **100% eager fail-fast** (`ControllerValidator`) | Strict on proxy creation | Mostly runtime / lazy | Startup validation | Runtime builder checks |
| **Transport Engines** | Pluggable SPI: JDK (built-in), OkHttp, Apache HC5 | Hardcoded OkHttp | Pluggable (JDK, OkHttp, Apache) | Pluggable via `RestClient` / `WebClient` | Fixed (OkHttp engine) |
| **Payload Codecs** | Pluggable SPI: JSON (Jackson, Gson, Moshi, NoOp) + XML | Converter.Factory (Jackson, Moshi) | Encoder/Decoder SPI (Jackson, etc.) | HttpMessageConverters | Manual / RequestBody |
| **Dynamic Target URL** | ❌ **Missing (Static Base URL + path only)** | ✅ `@Url` parameter | ✅ `URI baseUri` parameter | ✅ `URI` parameter | ✅ Per-request URL |
| **Transparent HTTP Cache** | ❌ **Missing (manual headers only)** | ❌ (Delegated to OkHttp) | ❌ (Delegated to transport) | ❌ (Delegated to transport) | ✅ Full RFC 9111 HTTP Cache |
| **Resilience & Rate Limiting** | Built-in SPI: `@Retry`, CircuitBreaker, RateLimiter | External (Retrofit adapters) | External (Resilience4j / Spring Cloud) | External | Interceptor / network retry |

---

### 2. Strategic Feature Gap Analysis (Vital Gaps)

#### Gap 1: Dynamic Endpoint / Target URL Override (`@TargetUrl` / `URI` parameter) — **CRITICAL**
- **The Problem:** In real-world enterprise architectures, the final request URL is frequently dynamic and determined at runtime. Examples:
  1. *AWS S3 / GCS Presigned URLs:* Upload or download destinations generated dynamically with expiration tokens.
  2. *HATEOAS & Pagination:* RESTful APIs returning `Link: <https://api.example.com/items?page=2>; rel="next"`.
  3. *OAuth2 / Webhooks / Dynamic Routing:* Callbacks or multi-tenant services directing calls to arbitrary hosts.
- **Current jRestly Limitation:** The base URL is immutable on `ModuleInfo` / `JRestlyClient`, and `@Get(path = ...)` must be statically defined in the interface. Developers are forced to instantiate a new `JRestlyClient` instance per dynamic URL or bypass jRestly entirely.
- **Strategic Direction:** Introduce `@TargetUrl` (or native `java.net.URI` / `String` parameter binding) that overrides the target URL for a specific invocation while retaining the entire declarative pipeline (codecs, headers, interceptors, retry, metrics).

#### Gap 2: RFC 9111 HTTP Caching SPI & In-Memory Store — **HIGH VALUE**
- **The Problem:** Repetitive GET calls against API endpoints with `Cache-Control` (`max-age`, `must-revalidate`, `stale-while-revalidate`) or conditional headers (`ETag`, `Last-Modified`) currently hit the wire every time.
- **Current jRestly Capability:** Manual conditional requests via `ResponseHeaders` and `Optional<T>` (ADR-0023, 304 mapping), but no transparent caching layer.
- **Strategic Direction:** Design a clean `ru.jrestly.cache.HttpCache` SPI adhering to RFC 9111 rules, with a default zero-dependency in-memory LRU cache implementation and extension points for disk-backed caching.

#### Gap 3: Error Ergonomics & Problem Details (RFC 7807 / RFC 9457) — **MEDIUM**
- **Current Capability:** `@OnError(statuses = {404}, errorObject = MyError.class)` throws `HandledException`.
- **Strategic Direction:** Add out-of-the-box support for RFC 7807 `application/problem+json` standard error models and an optional client-wide `ErrorHandler` SPI.

---

### 3. Bloat & Over-Engineering Audit (Scope Preservation)

To remain faithful to **Zero-Dependency Core** (ADR-0001) and **Non-Goals** (ADR-0003: *"We do not build Spring Cloud, a full web framework, or a headless browser"*):
1. **Resilience Boundaries (`CircuitBreaker` & `RateLimiter`, ADR-0043):**
   - *Verdict:* Retain as lightweight SPIs with minimal, zero-dependency in-memory implementations for standalone apps, CLI tools, and test suites.
   - *Constraint:* Do **not** expand into distributed state stores (e.g., Redis rate limiting, Consul coordination) or heavy telemetry frameworks within core. Advanced distributed needs belong in external user-provided SPI implementations.
2. **Cookie Management (`CookieStore`, ADR-0032, ADR-0039):**
   - *Verdict:* Retain standard RFC 6265 session handling.
   - *Constraint:* Do not implement DOM storage, JavaScript execution, or full browser security sandboxes.
3. **Core vs. Submodules:**
   - Keep core strictly zero-dependency on Java 21 `java.net.http.HttpClient`.
   - All third-party integrations (OkHttp, Apache HC5, Jackson XML) remain decoupled submodules discoverable via `ServiceLoader`.

---

### 4. Quality Control Baseline: JaCoCo Code Coverage

To establish rigorous quality control beyond compiler checks (`-Xlint:all -Werror -parameters`, `-Xdoclint:all -Werror`):
1. Integrated **JaCoCo 0.8.12** into the Gradle build lifecycle.
2. Every `./gradlew test` run automatically generates execution data and finalizes with `jacocoTestReport`.
3. **Baseline Code Coverage Metrics (Session #236):**
   - **Instruction Coverage:** **91.37%** (24,973 / 27,332 instructions)
   - **Line Coverage:** **91.15%** (5,386 / 5,909 lines)
   - **Branch Coverage:** **78.92%** (2,879 / 3,648 branches)
   - **Class Coverage:** **98.50%** (131 / 133 classes covered)
   - **Package Instructions Breakdown:**
     * `ru.jrestly.annotation`: 100.00%
     * `ru.jrestly.engine`: 97.26%
     * `ru.jrestly.interceptor`: 94.80%
     * `ru.jrestly.codec`: 94.44%
     * `ru.jrestly.tracing`: 94.06%
     * `ru.jrestly.resilience`: 93.07%
     * `ru.jrestly`: 92.49%
     * `ru.jrestly.compression`: 92.41%
     * `ru.jrestly.http`: 90.91%
     * `ru.jrestly.cookie`: 88.97%
     * `ru.jrestly.auth`: 88.45%
     * `ru.jrestly.metrics`: 88.05%
     * `ru.jrestly.json`: 86.53%

## Consequences

1. **Strategic Roadmap Update:** Dynamic Target URL (`@TargetUrl`) is prioritized as the next major functional epic to eliminate the primary practical barrier for production adoption.
2. **Quality Assurance:** JaCoCo reporting is active in CI/local runs; uncovered branches provide concrete guidance for targeted test hardening.
3. **Architectural Integrity:** Bloat risks are documented and bounded by strict adherence to ADR-0001 and ADR-0003.
