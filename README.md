# jRestly

[![](https://jitpack.io/v/longirun/jRestly.svg)](https://jitpack.io/#longirun/jRestly)

A declarative HTTP client for Java. Define an interface, get a working client — no code generation, just a dynamic proxy over `java.net.http.HttpClient` with a zero-dependency core.

> **Status:** early development, current release v0.6.1 — `LocalDateTime` round-trips with sub-second precision and accepts ISO-8601 input; 0.6.0 brought streaming multipart uploads for any number of files from disk, memory, or streams, plus a pluggable JSON codec SPI. See `ROADMAP.md`.

## Features

- Declarative interface-based API via `java.lang.reflect.Proxy`
- HTTP methods: `@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`
- Path variables (`@PathVariable` + `${var}`), query params (`@RequestParam`, `@RequestDefaultParam`), headers (`@RequestHeader`), request body (`@RequestBody`)
- Content types: `application/json` (default), `application/x-www-form-urlencoded`, `multipart/form-data`
- Multipart uploads via `@MultipartFormFile(partName = …)`: any number of files from disk, memory, or arbitrary streams (`FileContent`), streamed with bounded buffering; per-part `Content-Type` derived from the file extension, overridable per parameter
- Per-method typed error mapping: `@OnError(statuses = {…}, errorObject = X.class)` throws `HandledException` with the deserialized body
- Strict status validation: any status not in `@OnError` and not in `@ExpectStatus` (or not 2xx when `@ExpectStatus` is absent) throws `UnexpectedStatusException` with the raw body. Use `@ExpectStatus(statuses = {202})` for strict 2xx contracts
- Per-method redirect chasing: `@FollowRedirects(count = N)`
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
