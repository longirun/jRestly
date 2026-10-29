# jRestly

[![](https://jitpack.io/v/longirun/jRestly.svg)](https://jitpack.io/#longirun/jRestly)

A declarative HTTP client for Java. Define an interface, get a working client — no code generation, just a dynamic proxy over `java.net.http.HttpClient` with a zero-dependency core.

> **Status:** early development, current release v0.8.0 — declarative media types & form-data flexibility (@Accept, @ContentType, MediaType constants, client default headers, multipart JSON DTO / raw byte parts and custom charsets). See `ROADMAP.md`.

## Features

- Declarative interface-based API via `java.lang.reflect.Proxy`
- HTTP methods: `@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`
- Path variables (`@PathVariable` + `${var}`), query params (`@RequestParam`, `@RequestDefaultParam`), headers (`@RequestHeader`), dynamic `Map<?, ?>` parameters and headers, static repeatable `@RequestHeader` / `@RequestDefaultParam` on interfaces and methods, request body (`@RequestBody`)
- Content types: `application/json` (default), `application/x-www-form-urlencoded`, `multipart/form-data`
- Multipart uploads via `@MultipartFormFile(partName = …)`: any number of files from disk, memory, or arbitrary streams (`FileContent`), streamed with bounded buffering; per-part `Content-Type` derived from the file extension, overridable per parameter
- Per-method typed error mapping: `@OnError(statuses = {…}, errorObject = X.class)` throws `HandledException` with the deserialized body
- Strict status validation: any status not in `@OnError` and not in `@ExpectStatus` (or not 2xx when `@ExpectStatus` is absent) throws `UnexpectedStatusException` with the raw body. Use `@ExpectStatus(statuses = {202})` for strict 2xx contracts
- Per-method redirect chasing: `@FollowRedirects(count = N)`
- HTTP version pinning: client-wide `httpVersion(HttpClient.Version.HTTP_1_1)` on the builder, per-method override `@HttpVersion(HttpClient.Version.HTTP_1_1)` (default is `HTTP_2`)
- Auth state machine: `@Anonymous` (skip auth refresh), `@Authorization` (this is the login call), `@SetAuthDetails(headerName = …)` (capture token from response, reuse on subsequent calls)
- Zero-dependency core on `java.net.http.HttpClient` (`System.Logger`, no runtime dependencies); bring your own JSON codec — Jackson 2 is autodetected from the classpath

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.longirun:jRestly:v0.8.0")
    // JSON codec is not bundled — bring your own; Jackson 2 is autodetected
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
    implementation 'com.github.longirun:jRestly:v0.8.0'
    // JSON codec is not bundled — bring your own; Jackson 2 is autodetected
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
    <version>v0.8.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.2</version>
</dependency>
```

> Requires Java 21+ (compiled with `-parameters` flag).
>
> The core jar has no runtime dependencies: JSON serialization goes through the `ru.jrestly.json.JsonCodec` SPI. Jackson 2 on the classpath is autodetected; a custom codec can be wired explicitly via `JRestlyClient.builder().jsonCodec(...)`. Without a codec the client fails fast with an actionable message.

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

## Request parameter & header flexibility

jRestly handles real-world parameter patterns declaratively, across interface hierarchies:

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
            @RequestHeader(name = "Accept") String acceptOverride,
            @RequestHeader Map<String, String> dynamicHeaders
    );
}
```

- **Nullable parameters & headers:** passing `null` as a `@RequestHeader` or `@RequestParam` argument omits it from the wire request; `null` collection elements are skipped; `null` in a `@PathVariable` fails fast with an actionable `IllegalArgumentException`.
- **Dynamic `Map<?, ?>` parameters:** `@RequestParam Map` expands entries into query parameters (or urlencoded form fields / multipart text parts) with the map keys as parameter names (`status=active`). With an explicit `name()`, keys are namespaced as `name[key]` (`filter[status]=active`). Unlike scalar parameters, a blank `name()` on a `Map` does not fall back to the Java parameter name — it means "no prefix". A collection value expands into repeated parameters.
- **Dynamic `Map<?, ?>` headers:** `@RequestHeader Map` binds arbitrary headers from the entries; entries with `null` keys or values are omitted.
- **Static declarations on interfaces and methods:** repeatable `@RequestHeader` / `@RequestDefaultParam` directly on a controller interface or method apply to every call; inheritance applies parent declarations first, letting a sub-interface specialize headers and defaults.
- **Override precedence:** a non-null runtime argument replaces a same-named default parameter (query keys match exactly) or static header (case-insensitive per RFC 9110) — no duplicate keys on the wire; a `null` argument falls back to the static/default declaration.

## Declarative media types & client defaults

jRestly supports declarative media-type negotiation and universal client-level defaults:

```java
@Accept(MediaType.APPLICATION_JSON)
@ContentType(MediaType.APPLICATION_JSON)
public interface InventoryApi {

    @Get(path = "/api/items/${id}")
    Item getItem(@PathVariable(name = "id") long id);

    @Post(path = "/api/items/raw")
    @ContentType(MediaType.APPLICATION_OCTET_STREAM)
    void uploadRawPayload(@RequestBody byte[] data);

    @Get(path = "/api/items/export")
    @Accept({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    String exportItems();
}
```

- **`@Accept` & `@ContentType`:** declared on interface or method level with hierarchy inheritance (method wins over interface, child interface wins over parent). Multiple `@Accept` values join into a comma-separated `Accept` header per RFC 9110 §12.5.1.
- **`MediaType` constants:** standard MIME type constants in `ru.jrestly.http.MediaType` (`APPLICATION_JSON`, `APPLICATION_OCTET_STREAM`, `APPLICATION_FORM_URLENCODED`, `MULTIPART_FORM_DATA`, etc.).
- **Client default headers:** configure universal ambient headers via `JRestlyClient.builder().defaultHeader(name, value)` or `.defaultHeaders(map)`. Default `Accept` serves as the fallback baseline; default `Content-Type` acts as a client fallback without overriding method declarations or breaking form/multipart encodings.
- **Payload handling:** `@RequestBody byte[]` passes through verbatim without JSON conversion. `@RequestBody String` encodes using the charset declared in the effective Content-Type (falling back to UTF-8).

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

### Mixed multipart payloads & JSON DTO parts

Multipart requests can combine binary files, text fields, and serialized domain DTOs:

```java
public interface DocumentApi {

    @Post(path = "/api/documents", requestType = RequestType.MULTIPART_FORM_DATA)
    void uploadDocument(
            @RequestParam(name = "metadata", contentType = MediaType.APPLICATION_JSON) MetadataDto metadata,
            @RequestParam(name = "note", contentType = "text/plain; charset=windows-1251") String localizedNote,
            @RequestParam(name = "signature", contentType = MediaType.APPLICATION_OCTET_STREAM) byte[] rawBytes,
            @MultipartFormFile(partName = "file") FileContent file
    );
}
```

- **`@RequestParam(contentType = …)`:** when specified on a multipart parameter:
  - Complex DTO objects are serialized to JSON via the client's `JsonCodec`.
  - Strings are encoded using the declared `charset` (e.g. `charset=windows-1251` or `charset="ISO-8859-1"`), falling back to UTF-8.
  - Raw `byte[]` arrays are passed directly with the declared `Content-Type`. (Without `contentType`, `byte[]` fails fast to prevent accidental class-string leakage).
  - Collections and arrays serialize each element with the specified content type.
- **Eager validation:** `@MultipartFormFile` validates part names eagerly, forbids combination with `@RequestParam` on the same parameter, and requires `RequestType.MULTIPART_FORM_DATA`. Form and multipart methods require at least one body parameter.

### Migrating from `MultipartWriter`

`MultipartWriter.write(boundary, parts)` (whole-body `byte[]` assembly) was removed in 0.6.0. If you bypassed the annotations and assembled parts by hand, switch to the annotation flow with `FileContent` values. Annotation-only consumers get 0.6.0 with zero breaking changes — except one deliberate wire change: file parts now carry a derived `Content-Type` instead of a blanket `application/octet-stream`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

Copyright 2026 Anton Sinyagovsky.
