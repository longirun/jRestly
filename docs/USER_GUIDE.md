# jRestly: Developer & Architecture Guide

A modern, declarative HTTP client for Java 21+ built on the principle that **the method return type IS the contract** ([ADR-0004](0.5.0/ADR-0004-transparent-calls.md)). Zero external dependencies in the core, virtual-thread native, extensible via SPI.

---

## Table of Contents
1. [Core Philosophy](#core-philosophy)
2. [Quick Start](#quick-start)
3. [Declaring API Contracts](#declaring-api-contracts)
   - [HTTP Methods & Paths](#http-methods--paths)
   - [Parameters & Headers](#parameters--headers)
   - [Request Bodies & Multipart](#request-bodies--multipart)
4. [Return Types: The Contract](#return-types-the-contract)
   - [Direct DTOs & Records](#direct-dtos--records)
   - [Optional Handling (404/204)](#optional-handling-404204)
   - [Asynchronous Calls (CompletableFuture)](#asynchronous-calls-completablefuture)
   - [Real-Time Streaming (SSE & NDJSON)](#real-time-streaming-sse--ndjson)
   - [Raw & Line Streaming](#raw--line-streaming)
5. [Pluggable Transports (Engines)](#pluggable-transports-engines)
6. [Polyglot Data Formats (Codecs)](#polyglot-data-formats-codecs)
7. [Resilience & Fault Tolerance](#resilience--fault-tolerance)
8. [Enterprise Observability & Data Privacy (152-FZ, GDPR, PCI-DSS)](#enterprise-observability--data-privacy)
9. [HTTP Caching (RFC 9111)](#http-caching-rfc-9111)

---

## Core Philosophy

Traditional HTTP libraries force verbose wrappers around every single call: `Call<User>`, `ResponseEntity<User>`, `Observable<User>`, or manual deserialization calls.

jRestly eliminates wrapper noise entirely:
- **Return type is the contract:** If your method returns `User`, you receive a deserialized `User`. If it returns `Stream<ChatChunk>`, you receive a live, decoded token stream. If it returns `Optional<User>`, HTTP 404/204 cleanly yields `Optional.empty()`.
- **Zero-Dep Core:** The core runtime requires only Java 21 (`java.net.http.HttpClient`) and Java Dynamic Proxies. Zero third-party dependencies in `implementation`.
- **Open SPI, Composition over Inheritance:** All adapters (`HttpEngine`, `PayloadCodec`, `RequestInterceptor`, `ResponseInterceptor`, `HttpCache`, `ClientMetricsListener`) are open `non-sealed` interfaces.

---

## Quick Start

### 1. Add Dependency (Gradle / Maven)
```groovy
// Core client (Zero dependencies)
implementation 'ru.jrestly:jrestly:0.14.0'

// Optional JSON Codec (e.g., Jackson)
implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
```

### 2. Define Your Declarative Interface
```java
public interface GitHubClient {
    @Get("/users/{username}")
    User getUser(@PathVariable("username") String username);

    @Get("/users/{username}/repos")
    List<Repository> listRepos(
            @PathVariable("username") String username,
            @Query("sort") String sort
    );

    @Post("/user/repos")
    Repository createRepo(@Body NewRepoRequest request);
}
```

### 3. Create the Client & Call the API
```java
GitHubClient client = JRestlyClient.builder()
        .baseUrl("https://api.github.com")
        .header("Authorization", "Bearer " + token)
        .build()
        .create(GitHubClient.class);

// Direct call — returns User directly!
User user = client.getUser("octocat");
System.out.println("Followers: " + user.followers());
```

---

## Declaring API Contracts

### HTTP Methods & Paths
All standard HTTP verbs are available as method-level annotations:
- `@Get("/path")`
- `@Post("/path")`
- `@Put("/path")`
- `@Delete("/path")`
- `@Patch("/path")`
- `@Head("/path")`
- `@Options("/path")`

Base URLs and class-level path prefixes are seamlessly composed:
```java
@Path("/api/v1")
public interface BillingApi {
    @Get("/invoices/{id}")
    Invoice getInvoice(@PathVariable long id);
}
```

### Parameters & Headers
- `@PathVariable("id")`: Path segment replacement (`/items/{id}`).
- `@Query("status")`: Query parameter (`?status=active`). Supports arrays and collections with configurable format (CSV, SSV, TSV, PIPES, MULTI).
- `@Header("X-Tenant-ID")`: Parameter-level custom header injection.
- `@Headers({"Accept: application/json", "X-App: Demo"})`: Static headers declared at method or interface level.

### Request Bodies & Multipart
- `@Body`: Encodes the provided object through the configured `PayloadCodec` (JSON, XML, YAML, Protobuf).
- `@FormUrlEncoded`: Submits HTML form key-value pairs (`application/x-www-form-urlencoded`).
- `@Multipart`: Submits RFC 7578 multipart/form-data. Supports `FileContent.from(path)`, `byte[]`, `InputStream`, and metadata parts.

---

## Return Types: The Contract

### Direct DTOs & Records
Standard business domain objects and Java 21 `record` types decode automatically:
```java
@Get("/orders/{id}")
OrderRecord getOrder(@PathVariable String id);
```

### Optional Handling (404/204)
When a resource might not exist, simply declare `Optional<T>`. When the server returns `404 Not Found` or `204 No Content`, jRestly returns `Optional.empty()` instead of throwing exceptions:
```java
@Get("/users/{id}")
Optional<User> findUserById(@PathVariable long id);
```

### Asynchronous Calls (CompletableFuture)
To execute calls non-blockingly, wrap the target return type in `CompletableFuture<T>`:
```java
@Get("/analytics/report")
CompletableFuture<ReportDto> generateReportAsync();
```

### Real-Time Streaming (SSE & NDJSON)

jRestly provides first-class support for real-time AI/LLM streaming (OpenAI, Anthropic, Ollama, DeepSeek) and event-driven microservices ([ADR-0098](0.14.0/ADR-0098-direct-typed-data-streaming-sse-and-ndjson.md)):

#### 1. Direct Data Stream (`Stream<T>`)
Directly receive decoded event payloads without wrapper envelopes:
```java
public interface AiAssistantApi {
    @Post("/v1/chat/completions")
    @Header("Accept: text/event-stream")
    Stream<ChatChunk> streamChat(@Body ChatPrompt prompt);
}

// Consuming the live token stream:
try (Stream<ChatChunk> stream = api.streamChat(prompt)) {
    stream.forEach(chunk -> System.out.print(chunk.content()));
}
```
* **Built-in `[DONE]` termination:** Streams automatically stop when the standard LLM terminator `data: [DONE]` is received.
* **Keep-alive immunity:** SSE comment lines (`:keepalive`) and heartbeat pings are omitted.
* **Resource cleanup:** Closing the stream in `try-with-resources` closes the underlying TCP socket immediately.

#### 2. Streaming with Metadata (`Stream<ServerSentEvent>` & `Stream<TypedServerSentEvent<T>>`)
When event identifiers (`id:`) or event types (`event:`) are needed:
```java
@Get("/market/tickers")
Stream<TypedServerSentEvent<TickerPrice>> streamTickers();
```

#### 3. Automatic Reconnection & Resume
```java
Stream<ChatChunk> resilientStream = TypedServerSentEvent.reconnectingData(
        lastEventId -> api.streamChat(prompt, lastEventId),
        ReconnectPolicy.builder()
                .maxAttempts(5)
                .initialBackoff(Duration.ofMillis(200))
                .exponentialBackoff(2.0, Duration.ofSeconds(5))
                .build()
);
```

### Raw & Line Streaming
- `Stream<String>`: Reads the response line by line (ideal for NDJSON and raw text logs).
- `InputStream`: Unbuffered raw binary stream for large file downloads.
- `byte[]`: Buffered binary payload.
- `String`: Raw response text.

---

## Pluggable Transports (Engines)

jRestly supports 4 pluggable transports via ServiceLoader or explicit configuration:

| Module | Engine | Key Advantages |
|---|---|---|
| *Core (default)* | `JdkHttpEngine` | 100% zero-dependency, lightweight, native Virtual Threads. |
| `jrestly-engine-okhttp` | `OkHttpEngine` | Exceptional low-latency for microservices, mature HTTP/2 stack. |
| `jrestly-engine-apache5` | `Apache5HttpEngine` | Enterprise connection pool tuning, high-throughput batching. |
| `jrestly-engine-netty` | `NettyHttpEngine` | Non-blocking EventLoop, ByteBuf buffer pooling, high-throughput streaming (1 MB+). |

```java
// Explicit transport selection:
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://service.internal")
        .httpEngine(new NettyHttpEngine())
        .build();
```

---

## Polyglot Data Formats (Codecs)

jRestly supports multiple wire formats through its `PayloadCodec` SPI:
- **JSON:** Built-in Jackson, Gson, Moshi adapters, and zero-dep JSON parser.
- **XML (`jrestly-codec-jackson-xml`):** StAX-based high-speed XML binding.
- **YAML (`jrestly-codec-yaml`):** SnakeYAML-based structured configuration streaming.
- **Protocol Buffers (`jrestly-codec-protobuf`):** Ultra-fast schema-driven binary serialization (~55 ns decode).

---

## Resilience & Fault Tolerance

Declarative resilience annotations protect against downstream service failures:

```java
public interface PaymentGateway {
    @Post("/charges")
    @Retry(maxAttempts = 3, backoffMs = 200, jitter = 0.5)
    @Timeout(millis = 1500)
    ChargeResult processCharge(@Body ChargeRequest request);
}
```

- **Circuit Breaker:** Fail-fast state transitions (CLOSED, OPEN, HALF_OPEN) with configurable failure thresholds.
- **Rate Limiter:** Token-bucket rate limiting preventing upstream quota exhaustion.
- **Error Mapping (`@OnError`):** Declarative mapping of HTTP error codes (e.g., 400, 422, 429) to typed custom exception models.

---

## Enterprise Observability & Data Privacy

Designed for enterprise banks, telecommunications, and high-compliance environments (GDPR, 152-FZ, PCI-DSS) ([ADR-0095](0.13.0/ADR-0095-enterprise-observability-audit-and-data-privacy.md), [ADR-0096](0.14.0/ADR-0096-production-correlation-tracing-and-raw-logging.md)):

### 1. Automatic Data Masking (Sanitization)
Sensitive headers (`Authorization: Bearer [REDACTED]`, `Cookie`, `X-Api-Key`), URI query parameters (`?token=[REDACTED]`, `?phone=[REDACTED]`), and credentials in JSON payloads are redacted by default in all log output.

```java
// Safe by default; can be switched to raw mode on staging/dev:
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.bank.com")
        .sanitizeSensitiveData(true) // Default is true (Compliance safe)
        .build();
```

### 2. Log Correlation Tracing (`[traceId]`)
All request and response log lines are automatically stamped with a unified `[traceId]` prefix, allowing millions of concurrent virtual threads to be cleanly correlated in Kibana/ELK:
```
Request:  [c4b92f3577b34da6] GET https://api.bank.com/accounts
Response: [c4b92f3577b34da6] HTTP/2 200 in 28 ms
```

### 3. W3C Distributed Tracing
Automatic propagation of W3C `traceparent` and `tracestate` headers across microservice hops.

---

## HTTP Caching (RFC 9111)

Built-in HTTP caching layer respecting standard HTTP semantics:
```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://catalog.store.com")
        .cache(new InMemoryHttpCache(1000))
        .build();
```
- **Conditional Revalidation:** Sends `If-None-Match` (ETag) and `If-Modified-Since`. Automatically updates cached entries upon `304 Not Modified`.
- **Unsafe Invalidation:** Automatically invalidates cached representations upon successful `POST`, `PUT`, `DELETE`, or `PATCH` requests to the same resource.
