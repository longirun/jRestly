# ADR-0057: Streaming Decompression SPI and Zero-Length Upload Progress

> 2026-10-15 · Accepted · Status: Complete · Next free ADR: 0058

## Context

Continuing the codebase audit (maintainer's question: *"what problems are unplanned and invisible to you?"*, 2026-10-15). Two silent edge-case defects were uncovered and verified under TDD:

1. **Custom `ContentCompressor` broke streaming response types (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`) with `ZipException`:**
   In `HttpTemplate.handleDecompression()`, when `response.stream() != null`:
   ```java
   InputStream decompressedStream = isGzip
           ? new GZIPInputStream(response.stream())
           : new InflaterInputStream(response.stream());
   ```
   If a user registered a custom `ContentCompressor` (via `JRestlyClient.builder().contentCompressor(...)`, e.g., for Brotli, Zstandard, Snappy, or proprietary algorithms), `isGzip` evaluated to `false`. As a consequence, `HttpTemplate` unconditionally routed the compressed stream into `InflaterInputStream` (Deflate). The custom compressor was completely ignored, and reading the stream immediately failed with `ZipException: incorrect header check` or produced corrupted bytes.

2. **`ProgressBodyPublisher` omitted progress notification on zero-length uploads:**
   In `ProgressBodySubscriber` (download progress, ADR-0033), empty responses (`totalBytes == 0`) trigger a guaranteed `listener.onProgress(0, totalBytes)` upon `onComplete()`. However, `ProgressBodyPublisher` (upload progress, ADR-0030) did not mirror this:
   - Furthermore, the underlying JDK `HttpClient` (`jdk.internal.net.http.Http1Request`) optimizes zero-length requests (`HttpRequest.BodyPublishers.noBody()` or `ofByteArray(new byte[0])`) by omitting subscription to the request's `Flow.Publisher` altogether (only writing `Content-Length: 0`).
   - Consequently, for any zero-byte upload, `UploadProgressListener` was never notified, violating symmetric ergonomics with download progress.

## Decision

1. **Extend `ContentCompressor` with `decompress(InputStream)` in SPI:**
   - Add a default method `default InputStream decompress(InputStream stream) throws IOException` to `ContentCompressor`.
   - The default implementation reads all bytes and delegates to `decompress(byte[])`, so custom implementors do not need to provide a custom streaming decoder if byte-buffering is acceptable. Implementors with native streaming decoders (like Brotli or Zstd) can override it for true zero-buffering streaming decompression.
   - Built-in `GzipContentCompressor` and `DeflateContentCompressor` implement streaming decompression via `new GZIPInputStream(stream)` and `new InflaterInputStream(stream)`.
   - `HttpTemplate.handleDecompression()` delegates directly to `customCompressor.decompress(response.stream())` when present, followed by `isGzip` and `isDeflate`.

2. **Symmetric Zero-Length Upload Progress Notification:**
   - In `ProgressBodyPublisher`, add `notifyZeroLengthIfPending()` and thread-safe CAS guard (`AtomicBoolean notifiedZero`) to ensure `listener.onProgress(0, 0)` is delivered exactly once.
   - When subscribed via Flow API, `onComplete()` triggers `onProgress(0, 0)` if 0 bytes were sent.
   - In `JdkHttpEngine` (`send` and `sendAsync`), `notifyZeroUploadIfPending` invokes `notifyZeroLengthIfPending()` to handle transports that bypass subscription on zero-length bodies.

## Consequences & Verification

- **Streaming Decompression Consistency:** Custom content encoding SPI now transparently supports streaming returns (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`) without `ZipException`.
- **Upload Progress Symmetry:** Upload progress listeners reliably receive `(0, 0)` for zero-length uploads, matching download behavior.
- **Automated Verification (+5 tests):**
  - `RequestCompressionWireMockTest`: E2E custom SPI tests for `InputStream` and `Stream<String>` line streaming.
  - `ContentCompressorTest`: streaming decompression unit tests for GZIP, DEFLATE, and default SPI implementation.
  - `ProgressBodyPublisherTest`: unit test for empty body subscription.
  - `UploadProgressWireMockTest`: E2E WireMock integration test for zero-length upload payload.
- **Gates:**
  - **1047/1047 tests** across 135 classes (0 failures, 0 errors, +5 tests).
  - `compileJava` and `compileTestJava`: 0 warnings under `-Xlint:all -Werror`.
  - `javadoc`: 0 warnings under `-Xdoclint:all -Werror`.
  - Maven POM publication: strictly 0 dependencies (zero-dependency core preserved).
