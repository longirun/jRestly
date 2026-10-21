# jRestly

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Zero-Dependency Core](https://img.shields.io/badge/Core_Dependencies-0-success.svg)](#zero-dependency-core)
[![Tests](https://img.shields.io/badge/Tests-2%2C189%20passed-brightgreen.svg)](#quality--engineering-standards)
[![Branch Coverage](https://img.shields.io/badge/Branch_Coverage-97.5%25-brightgreen.svg)](#quality--engineering-standards)

A high-performance, declarative HTTP client for Java 21+ built on the principle that **the method return type IS the contract** ([ADR-0004](docs/0.5.0/ADR-0004-transparent-calls.md)). Zero runtime dependencies in the core, Virtual Thread native, and enterprise compliance-ready out of the box.

Define an interface, get an ultra-fast client — no code generation, no boilerplate envelopes like `Call<T>` or `Response<T>`.

---

## 🌟 Key Features

- **Return Type is the Contract:** Return DTOs, `Optional<T>` (clean 404/204 handling), `CompletableFuture<T>`, or real-time `Stream<T>` without noisy wrapper objects.
- **Zero-Dependency Core:** Only Java 21 `java.net.http.HttpClient` and dynamic proxies. Zero third-party dependencies in `implementation`.
- **Real-Time AI/LLM Streaming (SSE & NDJSON):** First-class support for `Stream<T>` and `Stream<TypedServerSentEvent<T>>` with automatic `[DONE]` termination (OpenAI, Anthropic, Ollama, DeepSeek standard), keep-alive filtering, and automatic reconnection.
- **Pluggable High-Speed Transports:** 4 engines supported via SPI: default zero-dep JDK (virtual threads), OkHttp 4 (`jrestly-engine-okhttp`), Apache HC5 (`jrestly-engine-apache5`), and Netty 4 (`jrestly-engine-netty` with pooled memory & zero leaks).
- **Polyglot Serialization:** Pluggable `PayloadCodec` SPI supporting JSON (Jackson, Gson, Moshi, zero-dep JSON parser), XML (`jrestly-codec-jackson-xml`), YAML (`jrestly-codec-yaml`), and Protocol Buffers (`jrestly-codec-protobuf`).
- **Enterprise Observability & Compliance:** Automated PII and credential sanitization for 152-FZ / GDPR / PCI-DSS compliance, unified `[traceId]` correlation prefix for Kibana/ELK, and W3C Distributed Tracing (`traceparent`).
- **Resilience & Fault Tolerance:** Declarative `@Retry` with exponential backoff and jitter, `@Timeout`, Circuit Breaker, Rate Limiter, and `@OnError` exception mapping.
- **Transparent RFC 9111 HTTP Cache:** Zero-allocation in-memory cache, `304 Not Modified` conditional revalidation (ETag, If-Modified-Since), and unsafe method invalidation.

---

## 📚 Documentation
- **[Complete Developer & Architecture Guide](docs/USER_GUIDE.md)**: In-depth usage, annotations reference, resilience patterns, and enterprise setups.
- **[Performance & Comparative Benchmarks](BENCHMARKS.md)**: Thorough evaluation against raw JDK, OkHttp, Retrofit 2, Apache HC5, Spring 6 RestClient, and OpenFeign.

---

- Declarative interface-based API via `java.lang.reflect.Proxy`
- Dynamic endpoint override via `@TargetUrl`: call runtime-determined URLs (AWS S3 presigned URLs, HATEOAS Link pagination, webhooks, multi-tenant routing) directly from interface methods
- HTTP methods: `@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`
- Path variables (`@PathVariable` + `{var}`), query params and form fields (`@RequestParam`, `@RequestDefaultParam`), headers (`@RequestHeader`), dynamic `Map<?, ?>` parameters and headers, repeatable class-level defaults and static headers, request body (`@RequestBody`)
- Content types: `application/json` (default), `application/x-www-form-urlencoded`, `multipart/form-data`, and pluggable media types (`application/xml`)
- Transparent RFC 9111 HTTP cache: zero-dependency `HttpCache` SPI with bounded LRU `InMemoryHttpCache`, conditional `304 Not Modified` revalidation (`ETag`, `If-Modified-Since`), `Cache-Control` directives, and zero network attempts on fresh cache hits
- Problem Details for HTTP APIs (RFC 7807 / RFC 9457): zero-dep `ProblemDetail` model and parser, auto-recognition on unexpected 4xx/5xx responses, and declarative `@OnError(errorObject = ProblemDetail.class)`
- Response streaming via `InputStream` (and `CompletableFuture<InputStream>`): direct streaming via `BodyHandlers.ofInputStream()` without whole-body buffering; bypasses the JSON codec on success; error responses consume the stream and preserve typed error mapping
- Server-Sent Events & line streaming via typed `Stream<TypedServerSentEvent<T>>`, `Stream<ServerSentEvent>`, and `Stream<String>` (and `CompletableFuture<Stream<...>>`): parse real-time event streams and LLM tokens on the fly with auto-reconnection (`Last-Event-ID` replay) and idle `stallTimeout` guards
- Request pipeline: interceptors & hooks: `RequestInterceptor` (transform outgoing requests, inject auth tokens, correlation IDs) and `ResponseInterceptor` (metrics, latency, audit logging, failure observation), uniform sync/async twins execution with failure isolation
- Resilience & observability: client-side `CircuitBreaker`, `RateLimiter`, W3C TraceContext correlation IDs, and rich `ClientMetricsListener`
- Multipart uploads via `@MultipartFormFile(partName = …)`: any number of files from disk, memory, or arbitrary streams (`FileContent`), streamed with bounded buffering; per-part `Content-Type` derived from the file extension, overridable per parameter
- Per-method typed error mapping: `@OnError(statuses = {…}, errorObject = X.class)` throws `HandledException` with the deserialized body
- Strict status validation: any status not in `@OnError` and not in `@ExpectStatus` (or not 2xx when `@ExpectStatus` is absent) throws `UnexpectedStatusException` with the raw body. Use `@ExpectStatus(statuses = {202})` for strict 2xx contracts
- Per-method redirect chasing: `@FollowRedirects(count = N)`
- Retry with backoff: client-wide `retry(RetryPolicy)` (off by default) or per-method `@Retry` (self-contained override); transport failures and the transient status family retried with exponential backoff, cap and full jitter, honoring `Retry-After`, plus an opt-in payload-decode retry
- HTTP version pinning: client-wide `httpVersion(HttpClient.Version.HTTP_1_1)` on the builder, per-method override `@HttpVersion(HttpClient.Version.HTTP_1_1)` (default is `HTTP_2`)
- Auth state machine: `@Anonymous` (skip auth refresh), `@Authorization` (this is the login call), `@SetAuthDetails(headerName = …)` (capture token from response, reuse on subsequent calls)
- Zero-dependency core on `java.net.http.HttpClient` (`System.Logger`, no runtime dependencies); bring your own JSON codec — Jackson 2, Gson, or Moshi is autodetected from the classpath
- Pluggable transports: optional submodules for OkHttp 4 (`jrestly-engine-okhttp`) and Apache HC5 (`jrestly-engine-apache5`) discovered via `ServiceLoader`
- Zero-garbage hot path: cached `MethodMetadata` descriptors precomputed eagerly, resulting in ~5–9 µs dispatch latency and 0 allocations on steady-state calls

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.longirun:jRestly:v0.6.1")
    // JSON codec is not bundled — bring your own; Jackson 2, Gson, or Moshi is autodetected
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.longirun:jRestly:v0.6.1'
    // JSON codec is not bundled — bring your own; Jackson 2, Gson, or Moshi is autodetected
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.longirun</groupId>
    <artifactId>jRestly</artifactId>
    <version>v0.6.1</version>
</dependency>
<!-- JSON codec is not bundled — bring your own; Jackson 2, Gson, or Moshi is autodetected -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.2</version>
</dependency>
```

> Requires Java 21+ (compiled with `-parameters` flag).
>
> The core jar has no runtime dependencies: JSON serialization goes through the `ru.jrestly.json.JsonCodec` SPI. Jackson 2, Gson, or Moshi on the classpath is autodetected; a custom codec can be wired explicitly via `JRestlyClient.builder().jsonCodec(...)`. Without a codec the client fails fast with an actionable message.

## Quick start

Define a controller interface for the API you want to call:

```java
import ru.jrestly.annotation.*;
import java.util.List;

public interface ItemApi {

    @Get("/api/items/{id}")
    Item getItem(@PathVariable long id);

    @Get("/api/items")
    List<Item> listItems(@RequestParam int page);

    @Post("/api/items")
    Item createItem(@RequestBody Item item);

    @Delete("/api/items/{id}")
    void deleteItem(@PathVariable long id);

    @Get("/api/items/{id}")
    @OnError(statuses = {404}, errorObject = ApiError.class)
    Item getItemOrThrow(@PathVariable long id);
}
```

Build a client and use it:

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .connectTimeout(10_000)
        .socketTimeout(30_000)
        .build();

ItemApi api = client.get(ItemApi.class);

Item created = api.createItem(new Item("widget", 42));
Item fetched = api.getItem(created.id());
List<Item> page = api.listItems(1);
```

## Dynamic endpoints (@TargetUrl)

When endpoint URLs are determined dynamically at runtime (such as Amazon S3 presigned URLs, HATEOAS Link pagination, webhooks, or dynamic tenant routing), use `@TargetUrl` to override the client's base URL per call:

```java
public interface StorageApi {

    @Get
    InputStream download(@TargetUrl URI presignedUri);

    @Post("/subresource")
    void uploadToWebhook(@TargetUrl String webhookUrl, @RequestBody Payload payload);
}
```

- **Supported types:** `java.net.URI`, `java.net.URL`, and `CharSequence`/`String`.
- **Composition:** Path variables (`{id}`) and `@RequestParam` seamlessly append to the target URL; relative paths in `@Get("...")` normalize slashes cleanly. A static query declared in the verb path (e.g. `@Get("/items?size=10")`) is preserved: it precedes the target's own query, which is merged after it with `&`, and dynamic `@RequestParam` values are appended last.
- **Fail-fast:** Passing `null` immediately throws an `IllegalArgumentException`. Declaring invalid types or multiple `@TargetUrl` parameters fails fast during proxy creation.
- **Performance:** Dynamic URL dispatch is pre-indexed in `MethodMetadata` with zero runtime reflection.

## Transparent HTTP caching (RFC 9111)

jRestly provides zero-dependency, RFC 9111 compliant HTTP client caching with thread-safe in-memory LRU storage and transparent conditional revalidation:

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .inMemoryCache(500) // LRU cache bounded to 500 entries
        .build();
```

- **Transparent revalidation:** When cached entries become stale, jRestly automatically dispatches `If-None-Match` (ETag) and `If-Modified-Since` headers. Upon receiving `304 Not Modified`, cache representation headers are merged (RFC 9111 §4.3.4) and the cached payload is returned transparently.
- **Cache short-circuit:** Fresh cache hits are served directly from memory with `attempts = 0` (zero network calls, circuit breaker bypassed) and dynamically calculated `Age` headers accessible via `ResponseHeaders.age()`.
- **Cache-Control directives:** Honors `max-age`, `no-cache` (forces revalidation), `no-store` (bypasses cache), and `must-revalidate`.
- **Automatic invalidation:** Successful unsafe requests (`POST`, `PUT`, `DELETE`, `PATCH`) automatically invalidate cached entries for the target URI.
- **Zero overhead:** When caching is not configured (`null`), hot-path execution has zero allocations.

## Problem Details for HTTP APIs (RFC 7807 / RFC 9457)

jRestly natively supports RFC 7807 / RFC 9457 Problem Details (`application/problem+json`) with zero external dependencies:

```java
public interface BillingApi {

    @Post("/orders")
    @OnError(statuses = {400, 403}, errorObject = ProblemDetail.class)
    Order createOrder(@RequestBody OrderRequest order);
}
```

- **Zero-dep parser:** `ProblemDetail` represents standard members (`type`, `title`, `status`, `detail`, `instance`) and dynamic extension properties with a built-in JSON parser and serializer.
- **Automatic recognition:** Unexpected 4xx/5xx responses carrying `application/problem+json` or RFC 9457 JSON fields are automatically parsed into `ClientErrorException` and `ServerErrorException`, accessible via `ex.getProblemDetail()`.
- **Direct exception mapping:** Map errors directly to `ProblemDetailException` via `@OnError(errorObject = ProblemDetailException.class)`.

## Payload codecs: JSON, XML & custom formats

The payload format is pluggable through the `ru.jrestly.codec.PayloadCodec` SPI. JSON is the built-in default (the `JsonCodec` adapter), and a codec chosen by the effective `Content-Type` binds request and response bodies — so `@ContentType(MediaType.APPLICATION_XML)` sends and receives real XML instead of JSON under a foreign header.

XML ships as an opt-in submodule, published alongside the core as `jrestly-codec-jackson-xml`. With it on the classpath the codec auto-registers (ServiceLoader) and claims `application/xml` / `text/xml` / `*+xml` — no builder call is needed:

```java
interface PersonApi {
    @Post("/person")
    @ContentType(MediaType.APPLICATION_XML)
    String send(@RequestBody Person person);
}

JRestlyClient client = JRestlyClient.builder().baseUrl(url).build(); // XML codec picked up automatically
```

Any other format (protobuf, CBOR, MessagePack, a home-grown one) is one interface implementation:

```java
client = JRestlyClient.builder()
        .baseUrl(url)
        .codec(new MyProtobufCodec())   // claims its media type(s)
        .build();
```

Selection rules: an explicit `codec(...)` wins over auto-discovered ones; the first registered codec that claims the media type wins; unknown **response** types fall back to JSON (backward compatibility); an unclaimed **request** media type with a bindable DTO fails fast instead of silently sending JSON. Replacing the whole registry with `codecs(CodecRegistry.of(jsonCodec))` disables auto-discovery.

## Response streaming

Declare `InputStream` or `CompletableFuture<InputStream>` as the controller method return type to stream the response body directly without buffering it into memory:

```java
public interface DownloadApi {

    @Get("/files/{name}")
    InputStream download(@PathVariable String name);

    @Get("/files/{name}")
    CompletableFuture<InputStream> downloadAsync(@PathVariable String name);
}
```

- **JSON by return type:** A POJO return type binds the response via the configured JSON codec; `String` returns the raw body text; `InputStream` declares byte binding and bypasses the codec entirely on the success path.
- **Error paths:** If the server returns an `@OnError` status or an unexpected status, the stream is consumed automatically to materialize the error payload (`HandledException` with the decoded error object or `UnexpectedStatusException` with raw text).
- **Resource lifecycle:** The caller is responsible for closing the returned `InputStream` once reading completes. Network timeouts apply until response headers arrive; interruptions during stream consumption surface as an `IOException` at the read site.

## Server-Sent Events & real-time streaming (ADR-0098)

Declare **`Stream<T>`** directly to consume real-time event streams (such as AI/LLM token completions) or newline-delimited payloads (NDJSON / JSON lines). Following the core philosophy — **return type is the contract** ([ADR-0004](docs/0.5.0/ADR-0004-transparent-calls.md) & [ADR-0098](docs/0.14.0/ADR-0098-direct-typed-data-streaming-sse-and-ndjson.md)) — you never have to unwrap envelope objects unless you explicitly need protocol metadata:

```java
public interface OpenAiChatApi {

    // 1. Direct Data Stream (Stream<T>): Cleanest, no envelope unwrapping!
    @Post("/v1/chat/completions")
    @Header("Accept: text/event-stream")
    Stream<ChatChunk> streamChat(@RequestBody ChatCompletionRequest request);

    @Post("/v1/chat/completions")
    CompletableFuture<Stream<ChatChunk>> streamChatAsync(@RequestBody ChatCompletionRequest request);

    // 2. Typed SSE with metadata: when you need id(), event(), retry()
    @Get("/events/typed")
    Stream<TypedServerSentEvent<ChatChunk>> streamTypedEvents();

    // 3. Untyped WHATWG SSE: raw string payload
    @Get("/events/raw")
    Stream<ServerSentEvent> streamRawEvents();

    // 4. NDJSON line streaming
    @Get("/events/ndjson")
    Stream<String> streamLines();
}
```

### Direct AI/LLM Token Streaming Example

Consume real-time LLM inference streams (OpenAI, Anthropic, Ollama, DeepSeek, vLLM) in a clean `try-with-resources` block:

```java
ChatCompletionRequest req = new ChatCompletionRequest("gpt-4o", List.of(new Message("user", "Explain quantum computing")));

try (Stream<ChatChunk> stream = api.streamChat(req)) {
    stream.forEach(chunk -> System.out.print(chunk.contentDelta()));
}
```

- **Direct Stream Decoding (`Stream<T>`, ADR-0098):** Binds incoming streaming chunks directly to your DTO using the configured `PayloadCodec`.
- **Termination Marker Immunity (`[DONE]`):** Open-source LLM terminal markers `data: [DONE]` automatically complete the stream cleanly without throwing JSON parse exceptions.
- **Keep-Alive Filtering:** Heartbeat ping events and SSE comments (`:keepalive`) are omitted, ensuring only valid DTO instances arrive.
- **Resource Hygiene:** Exiting `try-with-resources` closes the underlying HTTP connection and releases socket buffers immediately.
- **Resilience & Reconnection:** Use `TypedServerSentEvent.reconnectingData(supplier, policy)` for automatic reconnection with exponential backoff and `Last-Event-ID` state preservation.
- **Stall Guard Watchdog:** A dedicated virtual-thread stall guard (`jrestly-sse-stall-guard`) detects silent network stalls and triggers reconnection if the server stops emitting chunks.

## Request parameter & header flexibility

jRestly handles real-world HTTP parameter patterns declaratively across interface hierarchies:

```java
@RequestHeader(name = "User-Agent", value = "my-service/1.0")
@RequestDefaultParam(name = "api_version", value = "2026-10-14")
public interface BaseApi {
}

@RequestHeader(name = "Accept", value = "application/json")
public interface SearchApi extends BaseApi {

    @Get("/api/items")
    List<Item> search(
            @RequestParam(name = "filter") Map<String, Object> filters,
            @RequestParam(name = "sort") List<String> sortFields,
            @RequestParam Integer offset,
            @RequestHeader(name = "Accept") String acceptOverride,
            @RequestHeader Map<String, String> dynamicHeaders
    );
}
```

- **Nullable parameters & headers:** Passing `null` as a `@RequestHeader` or `@RequestParam` argument omits it gracefully from the wire request. Collections skip `null` elements without throwing exceptions. Passing `null` to a `@PathVariable` throws an actionable `IllegalArgumentException` indicating that the path placeholder cannot be left blank.
- **Static query in the verb path:** A query declared directly in the path (e.g. `@Get("/items?size=10")`) is sent as-is; dynamic `@RequestParam` values are appended after it with `&`, and it composes cleanly with `{id}` path variables (ADR-0067 P4).
- **Dynamic `Map<?, ?>` parameters:** `@RequestParam Map<?, ?>` expands map entries into query parameters (or form fields) using the map keys as-is (e.g. `status=active`). If `@RequestParam.name()` is specified, the keys are namespaced as `name[key]` (e.g. `filter[status]=active`). Unlike scalar parameters, a blank `name()` on a `Map` does not fall back to the Java parameter name — for a `Map`, `name()` is an optional namespace prefix, not the parameter name (ADR-0017, Amendment #296). Nested collections expand into repeated keys.
- **Dynamic `Map<?, ?>` headers:** `@RequestHeader Map<?, ?>` binds arbitrary dynamic headers. Entries with `null` keys or values are omitted.
- **Class-level & method-level static declarations:** `@RequestHeader` and `@RequestDefaultParam` can be declared directly on controller interfaces or individual methods with `@Repeatable` support. Interface inheritance applies parent declarations first, allowing child interfaces to specialize headers and defaults.
- **Override precedence:** Runtime parameter arguments override default query parameters and static headers (compared case-insensitively per RFC 9110), eliminating key duplication when overriding a default.

## Request pipeline: Interceptors & hooks

jRestly provides a lightweight, zero-dependency execution pipeline for cross-cutting request transformation and response observation (ADR-0018):

- **`RequestInterceptor`**: transforms or decorates the outgoing `WireRequest` before it is placed on the network. Use it for dynamic bearer token injection, distributed tracing headers (`X-Correlation-ID`), or custom query parameters.
- **`ResponseInterceptor`**: post-execution hook called once the HTTP call concludes (whether successful or exceptional). Use it for latency metrics, audit logging, and failure observation.
- **Context & attributes**: `RequestContext` and `ResponseContext` provide invocation metadata (module name, controller interface, method, args) and thread-safe attributes (`setAttribute` / `getAttribute`) to propagate state between request and response phases.
- **Sync & async symmetry**: interceptors execute symmetrically across synchronous calls and `CompletableFuture` asynchronous methods.
- **Failure isolation**: exceptions thrown inside a `ResponseInterceptor` are logged at `WARNING` level and do not break the client call or abort subsequent interceptors.

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        // Request interceptor: dynamic bearer token and tracing correlation ID
        .addRequestInterceptor((request, context) -> {
            String token = tokenProvider.getValidToken();
            String correlationId = UUID.randomUUID().toString();
            context.setAttribute("cid", correlationId);
            return request
                    .withHeader("Authorization", "Bearer " + token)
                    .withHeader("X-Correlation-ID", correlationId);
        })
        // Response interceptor: metrics and latency audit
        .addResponseInterceptor(context -> {
            long millis = context.duration().toMillis();
            int status = context.statusCode();
            String method = context.method().getName();
            logger.info("Call {} completed in {} ms with status {}", method, millis, status);
        })
        .build();
```

For stateless request transformation, you can pass a `UnaryOperator<WireRequest>` directly:

```java
clientBuilder.addRequestInterceptor(req -> req.withHeader("X-App-Client", "v1"));
```

## Enterprise Observability, Tracing & Data Privacy (GDPR, 152-FZ, PCI-DSS)

jRestly provides production-grade observability and regulatory compliance out of the box (ADR-0095, ADR-0096):

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://partner.api.example.com")
        // Correlation ID propagation: ThreadLocal / MDC / OpenTelemetry traceId
        .traceIdSupplier(() -> org.slf4j.MDC.get("traceId"))
        // Or configure a specific correlation header with generator
        .correlationId("X-Request-ID", () -> UUID.randomUUID().toString())
        // Compliance: data masking enabled by default (GDPR, 152-FZ, PCI-DSS)
        .sanitizeSensitiveData(true)
        .build();
```

- **Trace Correlation in Diagnostic Logs:** Outgoing requests and incoming responses carry correlation ID prefixes in log output, preventing log interleaving across millions of parallel Virtual Threads:
  ```text
  Request: [c4b92f3577b34da6] GET https://partner.api.example.com/v1/users?phone=[REDACTED]
  Authorization: Bearer [REDACTED]
  X-Request-ID: c4b92f3577b34da6

  Response: [c4b92f3577b34da6] HTTP/2 200 in 34 ms
  Set-Cookie: [REDACTED]
  ```
- **W3C Distributed Tracing:** Full W3C TraceContext Level 1 support (`traceparent`, `tracestate`) parsed via `TraceIdExtractor`.
- **Sensitive Data & PII Masking:** Case-insensitive masking of sensitive HTTP headers (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `*secret*`, `*token*`), query parameters (`token`, `secret`, `phone`, `email`, `passport`), and request body password fields.
- **Raw Debugging Mode:** For local development, sandbox, or staging troubleshooting, sanitization can be disabled to inspect raw wire traffic:
  ```java
  clientBuilder.sanitizeLogs(false); // Disables redaction for staging/dev troubleshooting
  ```
  Or pass JVM flag `-Djrestly.logging.sanitize=false`.
- **GOST TLS & Russian Banking Compliance:** Full zero-lock-in support for Russian certified cryptographic providers (CryptoPro JCP, ViPNet Java CSP) via `JRestlyClient.builder().sslContext(gostSslContext)` for integrating with SMEV, ESIA, and Bank of Russia APIs.

## Retry with backoff

Retry is **off by default** — without configuration a call makes exactly one attempt, so an existing client sees no change. Configure it client-wide and/or per method (the annotation is a self-contained policy that replaces the client-level one, no merging; a bare `@Retry` is the explicit off-switch on a method of a client that has retry enabled):

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .retry(RetryPolicy.defaults()) // 3 attempts, 200 ms -> 5 s backoff
        .build();

public interface ItemApi {

    @Get("/api/items/{id}")
    @Retry(times = 3, initialBackoffMs = 500)
    Item getItem(@PathVariable long id);
}
```

What gets retried: transport failures (timeouts, connection resets) and responses whose status is in the retryable set (default `{408, 429, 500, 502, 503, 504}`). An `InterruptedException` is never retried, whatever the transport setting says — the caller's thread was interrupted, so failing fast is the preserved semantics. A status a method declares in `@OnError.statuses` is a **terminal contract** by default — the loop stops and the typed `HandledException` is produced; opt the declared statuses into retry with `retryOnErrorStatuses(true)` / `onErrorStatuses = true`. POST and PATCH are not retried by default (RFC 9110: the retry happens after the request was sent, so a lost response on a successful POST is a classic double-submit); opt in with `retryNonIdempotent(true)` / `nonIdempotent = true` where the endpoint tolerates duplicates.

The third failure kind is a status-acceptable response whose body the codec cannot bind — the bytes arrived in full but do not fit the declared type (a schema mid-deploy, a one-off malformed payload). It is retried under a **separate opt-in**, `retryDecodeFailures(true)` / `retryDecodeFailures = true` (default off), not under the general enable: the decode probe runs on every success response, and when the retried response turns out to be the final one its payload is decoded twice (probe, then the real decode) — an allocation the transport and status axes never pay on the success path. The scope stays the success path: a declared `@OnError` status is terminal as before, and when attempts are exhausted the final failure is the codec's own unchecked exception — the same one a caller sees without retry. The axis is uniform across sync and `CompletableFuture` methods.

The wait between attempts grows exponentially (200 ms → 5 s cap by default) with full jitter; a `Retry-After` header on a failed response (delta-seconds or HTTP-date) sets a deterministic lower bound on the wait (status retries only — a transport failure has no response headers). The final response is classified exactly once — auth capture, logging and `@OnError` decoding never run on a discarded attempt. Synchronous and `CompletableFuture`-returning methods retry identically; on the async circuit the wait runs on `CompletableFuture.delayedExecutor` and does not park the calling thread.

## Multipart uploads

`@MultipartFormFile(partName = …)` names the **part**, never the file — the file's name on the wire comes from its value. Parameter types fall into two buckets:

- **Disk sugar** — `File`, `Path`, or a `String` treated as a path. The wire filename is taken from the path; the file is streamed from disk.
- **`FileContent`** — the universal value type, for any source: a lazy disk read (`FileContent.of(path)`), in-memory bytes (`FileContent.of("report.txt", bytes)`), or a stream (`FileContent.of("metrics.jsonl", () -> Files.newInputStream(path))`). `FileContent` is an open interface, so custom implementations can be plugged in; the static factories are the blessed entry points.

```java
public interface UploadApi {

    @Post("/api/upload", requestType = RequestType.MULTIPART_FORM_DATA)
    String upload(@MultipartFormFile(partName = "file") FileContent file);

    @Post("/api/attachments", requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadAll(@MultipartFormFile(partName = "attachment") List<FileContent> files);
}
```

`RequestType` lives in `ru.jrestly.http`; multipart is an explicit opt-in per method.

Collections, arrays, and varargs are accepted, and sources mix freely within one parameter (`File`, `Path`, `String`, `FileContent`): one part per element, wire filename = element's own name, part order follows parameter order, then element order. A `null` argument is skipped; an empty collection yields zero parts.

### File Content-Type

The part `Content-Type` is derived from the file-name extension (~20 common types, matched case-insensitively); unknown extensions fall back to `application/octet-stream`. Override it for every part of a parameter with `@MultipartFormFile(partName = …, contentType = "application/xml")`.

### Streaming and request framing

The body is composed from per-part publishers — buffering is bounded to a single chunk no matter how large the files are. When every part has a known length (disk files, byte arrays, streams with an explicit `contentLength`), the request carries an exact `Content-Length`; otherwise it falls back to `Transfer-Encoding: chunked`.

> **Chunked caveat:** some servers mishandle length-less multipart bodies — over h2c an unknown-length upload can be torn down with `RST_STREAM`. Prefer `FileContent.of(name, source, contentLength)` to force exact framing, or pin the method to `@HttpVersion(HttpClient.Version.HTTP_1_1)` where chunked encoding is well-defined.

### Migrating from `MultipartWriter`

`MultipartWriter.write(boundary, parts)` (whole-body `byte[]` assembly) was removed in 0.6.0. If you bypassed the annotations and assembled parts by hand, switch to the annotation flow with `FileContent` values. Annotation-only consumers get 0.6.0 with zero breaking changes — except one deliberate wire change: file parts now carry a derived `Content-Type` instead of a blanket `application/octet-stream`.

## Performance & benchmarks

The declarative layer (dynamic proxy, metadata resolution, pipeline dispatch) costs single-digit microseconds per call — visible only without a network, and noise against a real round-trip. The transport is pluggable ([ADR-0052](docs/0.10.0/ADR-0052-http-engine-spi-and-jdk-engine.md)): the zero-dep core runs on `java.net.http.HttpClient` by default, and adding the `jrestly-engine-okhttp` or `jrestly-engine-apache5` module ([ADR-0058](docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md)) plugs a faster transport into the same declarative client — the full jRestly pipeline included. The module is picked up automatically via `ServiceLoader`; `builder.httpEngine(...)` remains the explicit override.

Full methodology (loopback raw-TCP stand, JMH), comparative tables against raw `java.net.http.HttpClient`, OkHttp, Apache HC5, Retrofit, Feign and Spring `RestClient`, and honest limitations: **[BENCHMARKS.md](BENCHMARKS.md)**.

## License

Apache License 2.0. See [LICENSE](LICENSE).

Copyright 2026 Anton Sinyagovsky.
