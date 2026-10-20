# ADR-0087: YAML Codec Allocation Audit & ResponseDecompressor Extraction

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Two structural investigations from the session #346 baton:
  (1) The audit of `YamlCodec.deserialize` (bookmark #339: 27–60 µs / 22.7 KB/op on a small DTO document);
  (2) The ongoing decomposition of the remaining #1 red god class `HttpTemplate` according to the ADR-0081 scorecard.

---

## 1. Audit: `YamlCodec.deserialize` Memory & CPU Profile

In session #339, `CodecBenchmark` revealed that YAML deserialization was an outlier compared to JSON (~1.2 µs / 1,256 B) and XML (~6.4 µs / 3,896 B), demanding 27–60 µs and ~22.7 KB of heap allocations per 129-byte `UserDto` payload.

We conducted a fine-grained allocation breakdown using `ThreadMXBean.getThreadAllocatedBytes()` on Temurin JDK 21.0.12.1:

| Operation | Allocations (B/op) | Notes |
|---|---:|---|
| `YamlCodec.deserialize` (full DTO) | 22,884 | End-to-end `UserDto` deserialization |
| `TypeFactory.constructType(Type)` | **0** | Jackson's `TypeFactory` already caches `JavaType` in its internal `_typeCache` (`LRUMap`) |
| `YAMLFactory.createParser(bytes)` + iterate tokens | 22,704 | Jackson databind adds only **180 B/op** over raw token iteration |
| `YAMLParser` instantiation + close | **9,552** | SnakeYAML `StreamReader`, `ParserImpl`, `ScannerImpl`, and `Resolver` construction |
| SnakeYAML `ParserImpl` event loop | 16,264 | Event streaming: `ScalarEvent`, `MappingStartEvent`, `Token`, `Mark`, regex matcher instances |
| `StreamReader` alone | 2,712 | Input character buffering and `dataWindow` allocation |
| `ObjectReader.readValue(bytes)` | 22,640 | Saves only ~244 B/op and ~360 ns/op (~1.5% CPU) |

### Key Findings
1. **jRestly overhead is exactly 0 B.** `YamlCodec` incurs zero extra allocations for `TypeFactory.constructType` due to Jackson's LRU type cache.
2. **SnakeYAML architecture is the sole contributor.** Nearly 10 KB is consumed merely initializing the SnakeYAML tokenizer (`Resolver` creates ~50 `ResolverTuple` instances and `HashMap` entries on every parser creation). Another ~13 KB is consumed by SnakeYAML's AST event streaming model.
3. **Conclusion & Guidance:** YAML parsing cost on the JVM is structural and cannot be reduced without rewriting or heavily patching SnakeYAML. YAML in jRestly should remain dedicated to configuration and human-readable documents (ADR-0073), not high-rate hot-path RPC. The jRestly codec integration is already maximally optimal.

---

## 2. Structural Decomposition: `ResponseDecompressor` Extraction

In accordance with ADR-0081 (shrinking red classes without adding debt), the response decompression logic embedded inside `HttpTemplate` was isolated into an independent collaborator.

### Architecture & Boundaries
1. **Package-private Collaborator:** `ru.jrestly.http.ResponseDecompressor` (stateless, final, private constructor).
2. **Responsibilities:**
   - Content encoding header inspection and `identity` pass-through.
   - Decompression dispatch for `gzip`, `x-gzip`, `deflate` (RFC 1950 zlib with fallback to RFC 1951 raw deflate stream upon `ZipException`).
   - Custom `ContentCompressor` SPI integration from `ModuleInfo`.
   - Streaming response wrapping (`GZIPInputStream`, `InflaterInputStream`, custom stream decompressors).
   - Case-insensitive stripping of `Content-Encoding` and `Content-Length` headers, preserving order and nameless headers (ADR-0076).
3. **Seam in `HttpTemplate`:**
   - `HttpTemplate.handleDecompression` delegates to `ResponseDecompressor.decompress(response, moduleInfo)`.
   - `HttpTemplate` drops all `java.util.zip.*` imports and `ByteArrayInputStream`.
   - `HttpTemplate` shrinks from **2,221 to 2,127 lines** (−94 lines).

---

## 3. Verification & Metrics

- **TDD Red-First:** `ResponseDecompressorTest` written prior to implementation (16 test methods covering all branches, fallbacks, immutability, stream wrappers, and edge cases).
- **Collaborator Quality (C11 Ratchet):**
  - **Branches:** 48 / 48 covered (**100%**)
  - **Complexity:** 27 / 27 covered (**100%**)
  - **Instructions:** 258 / 258 covered (**100%**)
  - **Missed Branches:** **0**
- **Existing Tests:** `DecompressionConfigTest` and all end-to-end compression wire tests pass without changes.
- **Navigation Map:** ADR-0081 and `ru.jrestly.http/package-info.java` updated to link `ResponseDecompressor`.
