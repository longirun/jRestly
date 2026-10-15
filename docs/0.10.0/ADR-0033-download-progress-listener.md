# ADR-0033: Response Download Progress Listener & Direct Byte Array Returns

> 2026-10-15 · **Accepted** · Session #111 (agent-loop). Builds on ADR-0004 (Transparent Return Types), ADR-0015 (Response Streaming), ADR-0022 (Response Metadata Access), and ADR-0030 (Upload Progress Listener).
> Source of truth for response body download progress tracking, reactive BodySubscriber decoration, and direct byte[] return type mapping.

## Context

Downloading response payloads — including large files, media assets, binary artifacts, raw documents, and streaming exports — is a core requirement of modern REST clients.

In symmetrical correspondence to upload progress tracking (ADR-0030), developers and integrated systems require observability into response receipt:
1. **User Experience:** Rendering real-time download progress bars in CLI, desktop, and UI applications.
2. **Metrics & Observability:** Calculating download throughput, bandwidth consumption, and transfer latency.
3. **Resilience:** Detecting network stalls and timeouts during prolonged downloads.

Prior to ADR-0033, jRestly supported both full response materialization (`byte[]` on the wire) and live streaming (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`), but provided no listener hook to track bytes arriving across the network socket. Furthermore, methods declaring `byte[]` as their return contract were mistakenly routed through `JsonCodec` instead of directly returning the payload bytes.

### Invariants & Non-Goals

1. **Zero-Dep Core:** The implementation relies exclusively on Java 21 `java.net.http.HttpResponse.BodyHandler`, `java.net.http.HttpResponse.BodySubscriber`, and `java.util.concurrent.Flow` reactive streams. Zero external dependencies.
2. **Transparent Return Types (ADR-0004):** No wrapping return types in `ProgressResponse<T>` or `Observable<T>`. Method signatures remain clean and idiomatic.
3. **Intrinsic Hook Pattern (ADR-0022, ADR-0030):** Following the proven pattern of `ResponseHeaders` and `UploadProgressListener`, `DownloadProgressListener` is declared as an unannotated intrinsic method parameter that is not serialized into query parameters or headers.
4. **Wire-Accurate Progress:** Progress measures bytes received from the network transport. When transparent decompression (ADR-0021) is active, progress tracks compressed wire bytes matching the HTTP `Content-Length` header.
5. **Direct `byte[]` Returns:** Methods declaring `byte[]` or `Optional<byte[]>` directly receive raw response bytes without redundant UTF-8 string conversions or JSON deserialization attempts.

---

## Decisions

### 1. The `DownloadProgressListener` Functional Interface (`ru.jrestly.http.DownloadProgressListener`)

We introduce a clean `@FunctionalInterface`:

```java
package ru.jrestly.http;

/**
 * Functional listener for tracking response body download progress as bytes are received
 * from the network transport.
 */
@FunctionalInterface
public interface DownloadProgressListener {

    /**
     * Invoked periodically as bytes are received from the network transport.
     *
     * @param bytesRead the total number of bytes received so far for the current attempt
     * @param totalBytes the total length in bytes of the response body as reported by
     *                   the {@code Content-Length} header, or {@code -1} if unknown (e.g. chunked transfer)
     */
    void onProgress(long bytesRead, long totalBytes);
}
```

### 2. Declarative Usage in Controller Methods

Developers declare `DownloadProgressListener` as an unannotated parameter in any HTTP method:

```java
public interface AssetClient {

    @Get("/assets/{id}")
    byte[] downloadAsset(@PathVariable(name = "id") String id, DownloadProgressListener progress);

    @Get("/assets/{id}/stream")
    InputStream downloadStream(@PathVariable(name = "id") String id, DownloadProgressListener progress);

    @Get("/assets/{id}/async")
    CompletableFuture<byte[]> downloadAsync(@PathVariable(name = "id") String id, DownloadProgressListener progress);

    @Post(value = "/assets/transform", requestType = RequestType.MULTIPART_FORM_DATA)
    byte[] transform(@MultipartFormFile(partName = "file") FileContent file,
                     UploadProgressListener uploadProgress,
                     DownloadProgressListener downloadProgress);
}
```

Calling code can pass a lambda, method reference, or `null`:
```java
client.downloadAsset("report-2026.pdf", (read, total) -> {
    if (total > 0) {
        System.out.printf("Downloaded %d / %d bytes (%.1f%%)%n", read, total, (read * 100.0) / total);
    } else {
        System.out.printf("Downloaded %d bytes (chunked)%n", read);
    }
});
```

Passing `null` (or omitting the parameter on simpler methods) introduces zero wrapping overhead and incurs zero allocations.

### 3. Reactive Streams Interception: `ProgressBodyHandler` & `ProgressBodySubscriber`

Under the hood, `HttpTemplate.send(WireRequest)` and `sendAsync(WireRequest)` conditionally wrap the delegate `HttpResponse.BodyHandler<T>` in `ProgressBodyHandler<T>`:

- **Total Length Extraction:** `ProgressBodyHandler.apply(ResponseInfo responseInfo)` inspects the HTTP headers for `Content-Length`, yielding the exact expected wire length or `-1` if chunked.
- **Reactive Buffers Interception:** `ProgressBodySubscriber.onNext(List<ByteBuffer> item)` reads buffer remaining counts without altering buffer positions (`item.remaining()`), updates atomic `bytesRead`, and invokes `listener.onProgress(current, totalBytes)`.
- **Empty Body Handling:** If `totalBytes == 0` and no chunks were delivered, `onComplete()` emits `onProgress(0, 0)`.
- **Retry Independence:** Each execution attempt in `HttpTemplate.send()` or `@Retry` loops constructs a fresh `ProgressBodyHandler` and `ProgressBodySubscriber`, naturally resetting `bytesRead` to 0 for each independent attempt.

### 4. Direct `byte[]` Return Type Handling

In `HttpTemplate.processResponse()`:
- If `byte[].class.equals(returnType)`, `response.body()` is returned directly as `byte[]` (or wrapped in `Optional.of(bytes)` when `optional == true`).
- This completely bypasses `JsonCodec.deserialize`, providing first-class zero-overhead support for raw binary downloads.

### 5. Controller Validation (`ControllerValidator`)

`ControllerValidator` recognizes `DownloadProgressListener` as an intrinsic library hook:
- It is excluded from the count of payload parameters (e.g. methods requiring at least one parameter for multipart or form-urlencoded still require actual data parameters).
- If a `DownloadProgressListener` parameter is mistakenly annotated with `@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, or `@MultipartFormFile`, `ControllerValidator` rejects it fail-fast with a `ControllerValidationException`.

---

## Verification & Gates

1. **Unit Tests:**
   - `ProgressBodySubscriberTest` (7 tests): construction fail-fast, sequential buffer interception, chunked unknown length, empty body handling, buffer position preservation, error delegation, and `ResponseInfo` length extraction.
   - `DownloadProgressValidationTest` (9 tests): rejection of invalid HTTP parameter annotations on listener, exclusion from payload count, proxy creation fail-fast, and `HttpTemplateBuilder` listener resolution.
2. **Integration Tests:**
   - `DownloadProgressWireMockTest` (12 tests): `byte[]` download, `String` download, JSON DTO download, streaming `InputStream`, chunked transfer encoding, async `CompletableFuture`, retry loop reset, simultaneous upload & download progress, `null` safety, regression freedom, large payload (> 128 KiB), and transparent decompression wire tracking.
3. **Gates:** 703/703 tests passing (83 test classes), 0 compiler warnings under `-Werror`, 0 Javadoc warnings under `-Xdoclint:all -Werror`, zero-dep core strictly preserved.
