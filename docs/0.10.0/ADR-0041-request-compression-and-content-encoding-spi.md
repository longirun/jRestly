# ADR-0041: Request Body Compression & Content-Encoding SPI

> 2026-10-15 · **Accepted** · Concept, implementation, and comprehensive unit + WireMock tests verified in session #119.
> Addresses candidate #2 from the active zone of DRAFT.md and the maintainer's guidance on library maturity vectors.

## Context

In high-throughput HTTP integrations, microservices, log ingestion pipelines, analytics telemetry,
and bulk data updates (e.g., Elasticsearch, Kafka REST proxy, S3/blob uploads), client request
payloads (JSON DTOs, XML, strings, raw byte arrays) can be substantial in size.

The HTTP/1.1 and HTTP/2 specifications (RFC 9110 §8.4) define content codings applied to representation
payloads:
- Request header: `Content-Encoding: gzip` (or `deflate`)
- Request body: compressed octet stream

While ADR-0021 introduced transparent response decompression (`Accept-Encoding: gzip, deflate`),
JDK 21's `HttpClient` does not automatically compress outbound request bodies. Callers who need to
transmit compressed payloads had to manually compress bytes, manage streams, and set headers by hand,
losing the clean declarative ergonomics of jRestly interfaces.

### Constraints & Invariants

1. **Zero-dep core:** Rely strictly on Java 21 standard library classes from `java.util.zip`
   (`GZIPOutputStream`, `DeflaterOutputStream`, `Deflater`, `GZIPInputStream`, `InflaterInputStream`, `Inflater`).
   No external compression libraries in the core classpath.
2. **Declarative & programmatic ergonomics:** Support both method/interface-level annotations (`@Compress`)
   and client-wide builder settings (`requestCompression(CompressionAlgorithm, int threshold)`).
3. **Pluggable SPI:** Provide a lightweight, zero-dep `ContentCompressor` SPI allowing custom or external
   algorithms (e.g. Brotli, Zstandard) to be plugged in via builder or SPI.
4. **Header and double-compression safety:** If a request already specifies a manual `Content-Encoding`
   header, jRestly respects it and does not compress the payload again.
5. **Threshold controls:** Allow configuring a `minSizeBytes` threshold (e.g., 512 or 1024 bytes) so that
   tiny payloads that would expand under compression overhead are sent as plaintext.

---

## Decisions

### 1. SPI & Algorithms: `ru.jrestly.compression`

We introduce the package `ru.jrestly.compression`:

1. **`CompressionAlgorithm` enum:**
   - `GZIP` (`"gzip"`)
   - `DEFLATE` (`"deflate"`)
   - `NONE` (`"identity"`)
   Includes utility methods `encoding()` and `fromEncoding(String)`.

2. **`ContentCompressor` interface:**
   ```java
   public interface ContentCompressor {
       String encoding();
       byte[] compress(byte[] uncompressed) throws IOException;
       default byte[] decompress(byte[] compressed) throws IOException {
           throw new UnsupportedOperationException("Decompression not supported by " + getClass().getName());
       }
   }
   ```

3. **Standard Zero-Dep Implementations:**
   - `GzipContentCompressor`: uses `GZIPOutputStream` and `GZIPInputStream`.
   - `DeflateContentCompressor`: uses `DeflaterOutputStream` and `InflaterInputStream` with RFC 1950 zlib and RFC 1951 raw fallback.
   - `ContentCompressors`: built-in registry and factory providing lookup by `CompressionAlgorithm` or string encoding.

### 2. Declarative Ergonomics: `@Compress` annotation

We introduce `@Compress` in `ru.jrestly.annotation`:
```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Compress {
    CompressionAlgorithm algorithm() default CompressionAlgorithm.GZIP;
    int minSizeBytes() default 0;
    boolean enabled() default true;
}
```

- When declared on a controller interface, all mutating methods with request bodies inherit compression.
- When declared on a specific method, it overrides interface or client defaults.
- Setting `enabled = false` allows opting out for specific endpoints.

### 3. Client & Module Configuration

In `ModuleInfo`:
- `default CompressionAlgorithm getRequestCompressionAlgorithm() { return null; }`
- `default int getRequestCompressionThreshold() { return 0; }`
- `default ContentCompressor getContentCompressor(String encoding) { return null; }`

In `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`:
- `requestCompression(CompressionAlgorithm algorithm)`
- `requestCompression(CompressionAlgorithm algorithm, int thresholdBytes)`
- `contentCompressor(ContentCompressor compressor)`

### 4. Controller Validation

In `ControllerValidator`:
- Validates that `minSizeBytes >= 0`. Negative values throw `ControllerValidationException`.
- Validates that `algorithm != null`.

### 5. Transport Pipeline Integration

In `HttpTemplateBuilder`:
1. Resolves effective compression settings: Method `@Compress` > Interface `@Compress` > Client configuration.
2. If compression is active and body length `>= minSizeBytes`:
   - Checks if `Content-Encoding` header is already present. If present, leaves body untouched.
   - Otherwise, invokes `compressor.compress(body)`, replaces `body` with compressed bytes, and appends `Content-Encoding: <algorithm>`.
3. In `HttpTemplate.handleDecompression`: custom registered `ContentCompressor` instances can be leveraged for response decompression if present.

---

## Verification Plan

- **Unit tests:**
  - `CompressionAlgorithmTest`: encoding names, roundtrip lookup, null-safety.
  - `ContentCompressorTest`: gzip roundtrip, deflate roundtrip, custom compressor, error handling.
  - `CompressConfigTest`: ModuleInfo defaults, builder fluent setters, validation rules in `ControllerValidator`.
- **WireMock Integration tests:**
  - `RequestCompressionWireMockTest`:
    - Declarative `@Compress` on `@Post` endpoint (JSON DTO payload compressed with GZIP).
    - Declarative `@Compress(algorithm = DEFLATE)` on `@Put` endpoint.
    - Threshold filtering (`minSizeBytes = 500`): small payload sent plaintext, large payload compressed.
    - Interface-level `@Compress` with method-level opt-out (`@Compress(enabled = false)`).
    - Client-level builder configuration (`requestCompression(CompressionAlgorithm.GZIP)`).
    - Avoidance of double compression when caller supplies explicit `Content-Encoding`.
    - Async twin via `CompletableFuture<T>`.
    - Custom `ContentCompressor` registration.
