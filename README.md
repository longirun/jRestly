# jRestly

[![](https://jitpack.io/v/longirun/jRestly.svg)](https://jitpack.io/#longirun/jRestly)

A declarative HTTP client for Java. Define an interface, get a working client — no code generation, just a dynamic proxy over `java.net.http.HttpClient` with a zero-dependency core.

> **Status:** early development, current release v0.6.1 — `LocalDateTime` round-trips with sub-second precision and accepts ISO-8601 input; 0.6.0 brought streaming multipart uploads for any number of files from disk, memory, or streams, plus a pluggable JSON codec SPI. See `ROADMAP.md`.

## Features

- Declarative interface-based API via `java.lang.reflect.Proxy`
- HTTP methods: `@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`
- Path variables (`@PathVariable` + `${var}`), query params and form fields (`@RequestParam`, `@RequestDefaultParam`), headers (`@RequestHeader`), dynamic `Map<?, ?>` parameters and headers, repeatable class-level defaults and static headers, request body (`@RequestBody`)
- Content types: `application/json` (default), `application/x-www-form-urlencoded`, `multipart/form-data`
- Response streaming via `InputStream` (and `CompletableFuture<InputStream>`): direct streaming via `BodyHandlers.ofInputStream()` without whole-body buffering; bypasses the JSON codec on success; error responses consume the stream and preserve typed error mapping
- Multipart uploads via `@MultipartFormFile(partName = …)`: any number of files from disk, memory, or arbitrary streams (`FileContent`), streamed with bounded buffering; per-part `Content-Type` derived from the file extension, overridable per parameter
- Per-method typed error mapping: `@OnError(statuses = {…}, errorObject = X.class)` throws `HandledException` with the deserialized body
- Strict status validation: any status not in `@OnError` and not in `@ExpectStatus` (or not 2xx when `@ExpectStatus` is absent) throws `UnexpectedStatusException` with the raw body. Use `@ExpectStatus(statuses = {202})` for strict 2xx contracts
- Per-method redirect chasing: `@FollowRedirects(count = N)`
- Retry with backoff: client-wide `retry(RetryPolicy)` (off by default) or per-method `@Retry` (self-contained override); transport failures and the transient status family retried with exponential backoff, cap and full jitter, honoring `Retry-After`, plus an opt-in payload-decode retry for a status-acceptable response the codec cannot bind — uniform across sync and `CompletableFuture` methods
- HTTP version pinning: client-wide `httpVersion(HttpClient.Version.HTTP_1_1)` on the builder, per-method override `@HttpVersion(HttpClient.Version.HTTP_1_1)` (default is `HTTP_2`)
- Auth state machine: `@Anonymous` (skip auth refresh), `@Authorization` (this is the login call), `@SetAuthDetails(headerName = …)` (capture token from response, reuse on subsequent calls)
- Zero-dependency core on `java.net.http.HttpClient` (`System.Logger`, no runtime dependencies); bring your own JSON codec — Jackson 2, Gson, or Moshi is autodetected from the classpath

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

    @Get(path = "/api/items/${id}")
    Item getItem(@PathVariable(name = "id") long id);

    @Get(path = "/api/items")
    List<Item> listItems(@RequestParam(name = "page") int page);

    @Post(path = "/api/items")
    Item createItem(@RequestBody Item item);

    @Delete(path = "/api/items/${id}")
    void deleteItem(@PathVariable(name = "id") long id);

    @Get(path = "/api/items/${id}")
    @OnError(statuses = {404}, errorObject = ApiError.class)
    Item getItemOrThrow(@PathVariable(name = "id") long id);
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

## Response streaming

Declare `InputStream` or `CompletableFuture<InputStream>` as the controller method return type to stream the response body directly without buffering it into memory:

```java
public interface DownloadApi {

    @Get(path = "/files/${name}")
    InputStream download(@PathVariable(name = "name") String name);

    @Get(path = "/files/${name}")
    CompletableFuture<InputStream> downloadAsync(@PathVariable(name = "name") String name);
}
```

- **JSON by return type:** A POJO return type binds the response via the configured JSON codec; `String` returns the raw body text; `InputStream` declares byte binding and bypasses the codec entirely on the success path.
- **Error paths:** If the server returns an `@OnError` status or an unexpected status, the stream is consumed automatically to materialize the error payload (`HandledException` with the decoded error object or `UnexpectedStatusException` with raw text).
- **Resource lifecycle:** The caller is responsible for closing the returned `InputStream` once reading completes. Network timeouts apply until response headers arrive; interruptions during stream consumption surface as an `IOException` at the read site.

## Server-Sent Events & line streaming

Declare `Stream<ServerSentEvent>` or `Stream<String>` (and their `CompletableFuture<Stream<...>>` twins) to consume real-time event streams (such as LLM token completions) or newline-delimited payloads (NDJSON / JSON lines) lazily as chunks arrive over the wire:

```java
public interface LlmApi {

    @Post(path = "/v1/chat/completions")
    Stream<ServerSentEvent> streamChat(@RequestBody ChatRequest request);

    @Post(path = "/v1/chat/completions")
    CompletableFuture<Stream<ServerSentEvent>> streamChatAsync(@RequestBody ChatRequest request);

    @Get(path = "/events/ndjson")
    Stream<String> streamLines();
}
```

Consume in a `try-with-resources` block to ensure the underlying network connection and response stream close when finished:

```java
try (Stream<ServerSentEvent> events = api.streamChat(request)) {
    events.takeWhile(e -> !e.isDone()).forEach(event -> {
        System.out.print(event.data());
    });
}
```

- **WHATWG SSE conformance:** `ServerSentEvent` exposes `id()`, `event()`, `data()` (multi-line data concatenated with `\n`), `retry()`, and `isDone()` (detects the `[DONE]` marker common in LLM APIs). SSE comments (`: ping`) are ignored.
- **Encoding:** Charset is parsed from the `Content-Type` header (e.g. `text/event-stream; charset=utf-8`), defaulting to UTF-8.
- **Unified error contract:** On `@OnError` or unexpected HTTP statuses, the stream is consumed eagerly and produces `HandledException` or `UnexpectedStatusException`, preserving the uniform error contract across all return types.

## Request parameter & header flexibility

jRestly handles real-world HTTP parameter patterns declaratively across interface hierarchies:

```java
@RequestHeader(name = "User-Agent", value = "my-service/1.0")
@RequestDefaultParam(name = "api_version", value = "2026-10-14")
public interface BaseApi {
}

@RequestHeader(name = "Accept", value = "application/json")
public interface SearchApi extends BaseApi {

    @Get(path = "/api/items")
    List<Item> search(
            @RequestParam(name = "filter") Map<String, Object> filters,
            @RequestParam(name = "sort") List<String> sortFields,
            @RequestParam(name = "offset") Integer offset,
            @RequestHeader(name = "Accept") String acceptOverride,
            @RequestHeader Map<String, String> dynamicHeaders
    );
}
```

- **Nullable parameters & headers:** Passing `null` as a `@RequestHeader` or `@RequestParam` argument omits it gracefully from the wire request. Collections skip `null` elements without throwing exceptions. Passing `null` to a `@PathVariable` throws an actionable `IllegalArgumentException` indicating that the path placeholder cannot be left blank.
- **Dynamic `Map<?, ?>` parameters:** `@RequestParam Map<?, ?>` expands map entries into query parameters (or form fields). If `@RequestParam.name()` is specified, parameter names are prefixed as `name[key]` (e.g. `filter[status]=active`). Nested collections expand into repeated keys.
- **Dynamic `Map<?, ?>` headers:** `@RequestHeader Map<?, ?>` binds arbitrary dynamic headers. Entries with `null` keys or values are omitted.
- **Class-level & method-level static declarations:** `@RequestHeader` and `@RequestDefaultParam` can be declared directly on controller interfaces or individual methods with `@Repeatable` support. Interface inheritance applies parent declarations first, allowing child interfaces to specialize headers and defaults.
- **Override precedence:** Runtime parameter arguments override default query parameters and static headers (compared case-insensitively per RFC 9110), eliminating key duplication when overriding a default.

## Retry with backoff

Retry is **off by default** — without configuration a call makes exactly one attempt, so an existing client sees no change. Configure it client-wide and/or per method (the annotation is a self-contained policy that replaces the client-level one, no merging; a bare `@Retry` is the explicit off-switch on a method of a client that has retry enabled):

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .retry(RetryPolicy.defaults()) // 3 attempts, 200 ms -> 5 s backoff
        .build();

public interface ItemApi {

    @Get(path = "/api/items/${id}")
    @Retry(times = 3, initialBackoffMs = 500)
    Item getItem(@PathVariable(name = "id") long id);
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

    @Post(path = "/api/upload", requestType = RequestType.MULTIPART_FORM_DATA)
    String upload(@MultipartFormFile(partName = "file") FileContent file);

    @Post(path = "/api/attachments", requestType = RequestType.MULTIPART_FORM_DATA)
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

## License

Apache License 2.0. See [LICENSE](LICENSE).

Copyright 2026 Anton Sinyagovsky.
