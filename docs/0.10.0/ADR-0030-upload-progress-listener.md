# ADR-0030: Upload Progress Listener for Multipart & Request Bodies

> 2026-10-15 · **Accepted** · Session #108 (agent-loop). Builds on ADR-0005 (Multipart Authoring & Streaming) and ADR-0022 (Response Metadata Access).
> Source of truth for request body upload progress tracking, contract validation, and reactive streams integration.

## Context

Uploading large payloads — particularly in multipart file uploads, bulk data ingestion, and media transfer — is a primary scenario in modern REST APIs and microservice integrations.

Developers and client systems require visibility into the transmission lifecycle:
1. **User Experience:** Providing real-time upload progress bars in desktop, CLI, or web applications.
2. **Metrics & Observability:** Measuring bandwidth throughput, transfer latency, and upload completion rates.
3. **Resilience & Watchdogs:** Detecting stalled uploads or slow network conditions during prolonged transfers.

Prior to ADR-0030, jRestly provided fully streaming multipart transfers (ADR-0005) with bounded memory allocations, but lacked any callback mechanism for callers to monitor bytes transmitted to the network socket.

### Invariants & Non-Goals

1. **Zero-Dep Core:** The implementation relies exclusively on Java 21 `java.net.http.HttpRequest.BodyPublisher` and `java.util.concurrent.Flow` reactive streams. Zero external dependencies.
2. **Transparent Return Types (ADR-0004):** No wrapping methods in `Observable<T>` or `ProgressResponse<T>`. The interface signature remains clean and transparent.
3. **Intrinsic Hook Pattern (ADR-0022):** Following the proven pattern established by `ResponseHeaders`, `UploadProgressListener` is declared as an unannotated intrinsic method parameter that is not serialized into the request body or query string.
4. **Universal Applicability:** While primarily motivated by multipart file uploads, the mechanism applies uniformly to all request bodies (`byte[]`, `InputStream`, `FileContent`, `Path`, JSON DTO, form-urlencoded).

---

## Decisions

### 1. The `UploadProgressListener` Functional Interface (`ru.jrestly.http.UploadProgressListener`)

We introduce a clean `@FunctionalInterface`:

```java
package ru.jrestly.http;

/**
 * Functional listener for tracking request body upload progress as bytes are transmitted
 * over the network transport.
 */
@FunctionalInterface
public interface UploadProgressListener {

    /**
     * Invoked periodically as bytes are written to the network transport.
     *
     * @param bytesSent the total number of bytes sent so far for the current attempt
     * @param totalBytes the total length in bytes of the request body, or {@code -1} if unknown (e.g. chunked transfer)
     */
    void onProgress(long bytesSent, long totalBytes);
}
```

### 2. Declarative Usage in Controller Methods

Developers declare `UploadProgressListener` as a parameter in any HTTP method:

```java
public interface FileClient {

    @Post(value = "/files", requestType = RequestType.MULTIPART_FORM_DATA)
    UploadResult upload(@MultipartFormFile(partName = "file") FileContent file,
                        UploadProgressListener progress);

    @Post("/raw-upload")
    String uploadRaw(byte[] payload, UploadProgressListener progress);

    @Post(value = "/files/async", requestType = RequestType.MULTIPART_FORM_DATA)
    CompletableFuture<UploadResult> uploadAsync(@MultipartFormFile(partName = "file") FileContent file,
                                               UploadProgressListener progress);
}
```

Calling code can pass a lambda, method reference, or `null`:
```java
client.upload(file, (sent, total) -> {
    if (total > 0) {
        System.out.printf("Progress: %d / %d bytes (%.1f%%)%n", sent, total, (sent * 100.0) / total);
    } else {
        System.out.printf("Sent: %d bytes (chunked)%n", sent);
    }
});
```
Passing `null` (or omitting the parameter on simpler methods) is completely safe and introduces zero wrapping overhead.

### 3. Reactive Streams Interception: `ProgressBodyPublisher`

Under the hood, `HttpTemplate.bodyPublisher(WireRequest)` wraps the delegate `HttpRequest.BodyPublisher` in a package-private `ProgressBodyPublisher`:

- **Content Length Delegation:** `ProgressBodyPublisher.contentLength()` returns the exact length of the delegate publisher (e.g. total multipart size or byte array length), or `-1` if chunked.
- **Subscriber Wrapping:** When the JDK HTTP client subscribes to the publisher (`subscribe(Subscriber<? super ByteBuffer>)`), `ProgressBodyPublisher` wraps the subscriber in a `ProgressSubscriber`.
- **Chunk Tracking:** In `onNext(ByteBuffer item)`, `item.remaining()` is read before passing the buffer downstream to `delegate.onNext(item)`. The running total `bytesSent.addAndGet(len)` is updated, and `listener.onProgress(current, totalBytes)` is invoked.
- **Retry & Redirect Semantics:** Because a new `BodyPublisher` is obtained per attempt in `HttpTemplate.bodyPublisher(WireRequest)`, retried requests or redirect re-sends naturally reset `bytesSent` to 0 for each independent attempt.

### 4. Controller Validation (`ControllerValidator`)

`ControllerValidator` recognizes `UploadProgressListener` as an intrinsic library hook:
- It is excluded from the count of payload parameters (e.g. methods requiring at least one parameter for multipart or form-urlencoded still require actual data parameters).
- If an `UploadProgressListener` parameter is mistakenly annotated with `@RequestParam`, `@PathVariable`, `@RequestHeader`, or `@MultipartFormFile`, `ControllerValidator` rejects it fail-fast with a `ControllerValidationException`.

---

## Consequences

- **Ergonomics:** Simple lambda parameter enables clean upload progress tracking in both sync and async calls.
- **Zero-Dep & Lightweight:** 100% standard Java 21 `java.net.http` and `java.util.concurrent.Flow`. No external dependencies or reflection on the wire.
- **Resilience:** Integrates smoothly with `@Retry` and HTTP redirects.
