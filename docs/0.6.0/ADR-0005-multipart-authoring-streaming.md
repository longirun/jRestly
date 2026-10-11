# ADR-0005: Multipart authoring & streaming file parts

> 2026-10-08 · Done · ✅ Implemented in 0.6.0 (2026-10-11): streaming multipart via `BodyPublishers.concat` (exact Content-Length when every part length is known, chunked otherwise); `FileContent` open SPI (disk/memory/stream) accepted on collections/arrays/varargs; per-part Content-Type (mime map + `contentType()` override); `MultipartWriter` removed; part framing hardened (quoted-string escaping, CR/LF guard). Deviations: open SPI instead of sealed (pluggability); escaping — tester finding, pre-existing since 0.5.0. Tester independent pass + full suite green. Derived from field findings in a real consumer (2026-10), recorded in ROADMAP "Multipart gaps"; diagnosis of the Content-Type gap corrected during planning (see Context).

## Context

Three field-found gaps in the annotation-driven multipart flow: (1) `@MultipartFormFile` yields exactly one part per annotated parameter, so an arbitrary number of files is inexpressible; (2) synthetic/in-memory content must be written to a temp file first; (3) every file part carries a hardcoded `Content-Type: application/octet-stream` — an earlier diagnosis ("header never set") was wrong, the real gap is the hardcoded type. Deeper issue discovered during planning: the whole multipart path materializes the body in memory (`Files.readAllBytes` per part + `MultipartWriter.write` into one `byte[]`) — there is no streaming even for disk files.

**Goal:** annotation-driven multipart for an arbitrary number of files from any source (disk, memory, arbitrary stream) without unbounded memory use.

## Known Gaps

- `Collection<byte[]>` (unnamed in-memory elements) → not supported by design; `FileContent.of(name, bytes)` covers it. Index-based naming rejected permanently.
- Mime map stays small (~15-20 common extensions); exotic formats → `application/octet-stream` or explicit `contentType` override.
- Non-ASCII file names go on the wire as raw UTF-8 (de-facto browser behavior); no RFC 5987 `filename*` parameter.
- Unknown-length multipart bodies over h2c: some servers tear the stream down (`RST_STREAM`); documented in the README chunked caveat — root cause not investigated this cycle.

## Decisions

### Aspect 1: Parameter model — two buckets

| Option | + | − | Verdict |
|---|---|---|---|
| Three naming sources (`Path` / `byte[]` + annotation attribute / value type) | max flexibility | mixed vocabulary: file name comes from three different places; two ways to send one in-memory file | ✗ |
| **Two buckets: bare JDK types = disk sugar; `FileContent` = universal value** | one crisp rule; the annotation never names a file; simpler than the original plan (no `fileName` attribute at all) | a new public type in the vocabulary | ✅ |
| Unified `FilePart` type for everything | one rule | honest implementation needs a lazy-disk-or-bytes union → sealed hierarchy, mini-framework | ✗ |
| No memory support (annotations for disk only) | zero new API | consumers keep bypassing the library for a legit case | ✗ |

Invariant: **`@MultipartFormFile` names the part (`partName`), never the file.** A file's name comes from its value — disk path or `FileContent`. Bare `File`/`Path`/`String`-as-path parameters stay supported as disk sugar.

### Aspect 2: `FileContent` — interface with factories, not a record

| Option | + | − | Verdict |
|---|---|---|---|
| `record InMemoryFile(String, byte[])` | 1 line | public shape frozen; a future stream content = breaking change or a third bucket | ✗ |
| **`sealed interface FileContent { String fileName(); }` + static factories (permitted records package-private)** | representation hidden — stream/length slots added later without breaking; one family name | +~15 lines | ✅ |

Factories in 0.6.0:
- `of(Path)` (lazy disk read)
- `of(String fileName, byte[] bytes)`
- `of(String fileName, Supplier<InputStream> source)`
- `of(String fileName, Supplier<InputStream> source, long contentLength)` (explicit length to avoid chunked)

Sources mix freely in one collection — any combination of disk, memory and stream values.

### Aspect 3: Streaming engine — `BodyPublishers.concat` composition

- **Body composition**: `HttpRequest.BodyPublishers.concat(...)` of per-part sequences `[headers, content-publisher, CRLF]`: disk → `ofFile`, memory → `ofByteArray`, source → `ofInputStream`.
- **Length calculation**: exact `Content-Length` when every part has a known length (guaranteed by `concat`, `@since 16` — verified on JDK 21); otherwise fallback to **chunked** (server caveat documented in README).
- **Removals & breaking changes**: whole-body `byte[]` assembly and `Files.readAllBytes` removed; public `MultipartWriter.write(boundary, parts)` **removed** (breaking for bypass users; they migrate to `FileContent`). For annotation-only consumers: purely additive, zero breaking changes.

### Aspect 4: Collections on `@MultipartFormFile`

- **Supported types**: `Collection`, arrays, varargs of `File | Path | String | FileContent` (sources mixable).
- **Semantics**: one part per element; wire filename = element's own name; part order = parameter order, then element order.
- **Edge cases**: `null` argument is skipped (symmetric with `@RequestParam`), empty collection yields zero parts.

### Aspect 5: Part Content-Type

- **Derivation**: built-in extension→mime map (lowercase, case-insensitive match, ~15-20 common types, unknown → `application/octet-stream`).
- **Override**: explicit `contentType()` attribute on `@MultipartFormFile` (applies to every part of that parameter).
- **Wire change**: deliberate behavior change — file parts carry derived type instead of blanket `application/octet-stream`.

## Constraints

- Java 21+ baseline unchanged; zero new dependencies.
- The streaming path must not buffer the whole body — buffering is bounded to per-chunk.
- Regression gate: request body bytes for existing single-file (disk/memory) requests are identical through Step 3; Step 4 intentionally changes only part metadata (the added Content-Type header), not file content bytes.

## Stages

### 1. Content-source model + FileContent
- **Result:** `MultipartPart` content is a content source (`byte[]` for text parts; `Path | byte[] | Supplier<InputStream>` for file parts); public `FileContent` with all four factories; bare JDK types keep working.
- **Done when:** suite green; wire bytes for existing single-file requests unchanged.

### 2. Streaming engine
- **Result:** body via `BodyPublishers.concat`; exact Content-Length / chunked fallback; `MultipartWriter.write` and whole-body assembly removed.
- **Done when:** WireMock — known-length bodies byte-identical to Step-1 output; large-file streaming test passes; chunked case covered.

### 3. N files per request
- **Result:** `@MultipartFormFile` accepted on collections/arrays/varargs (disk + `FileContent`, mixable).
- **Done when:** WireMock — part count/order/names for single + collection + varargs + mixed-source requests.

### 4. Part Content-Type
- **Result:** mime map + `contentType()` override applied to all file parts (single and collection).
- **Done when:** WireMock — known extension / unknown extension / explicit override.

### 5. Release 0.6.0
- **Result:** README rewritten for the two-bucket concept (streaming notes, chunked caveat, `MultipartWriter` migration note); version 0.6.0; tag `v0.6.0`.
- **Done when:** tester full pass — independent test additions + full suite green.

## Deviations

| Stage | Expected | Actual | Cause |
|---|---|---|---|
| 1 | `FileContent` — hidden representation, factories as the only entry point (sealed hierarchy) | Open SPI interface: public `content()` and `default contentLength()`; built-in impls stay hidden, factories remain the blessed entry point, not the only one | Architect decision during review: an OSS library must let consumers plug in their own implementations; sealing blocks local substitutability |
| 2 | Part framing assumed identifier-safe names (raw append into the quoted-string) | `name`/`filename` escaped per RFC 2045 quoted-string (`\` → `\\`, `"` → `\"`); CR/LF in `name`/`filename`/`contentType` rejected with `IllegalArgumentException` (header-injection guard, fail-fast per library style) | Tester finding: `"` is legal in Linux/macOS names and arbitrary in `FileContent`, so it corrupted part framing; pre-existing since 0.5.0, fixed before the 0.6.0 tag (lands with Stage 5) |
| 5 | Negative `contentLength` from custom implementations unspecified | Rejected with `IllegalArgumentException` in the `MultipartPart` constructor, symmetric with the factory validation | Review decision: an explicitly declared length must fail fast — a silent chunked fallback would surface later as the documented server-side chunked caveat |
| 5 | `MultipartPart` — implementation detail | Public record whose invariants (CR/LF, non-negative length) are enforced by the validating compact constructor; `contentTypeFor` and the 5-arg `file(...)` are public helpers for custom implementations | Same open-SPI rationale as Stage 1: consumers may construct parts directly, so invariants must hold at the type boundary, not in factories |

## References

- ROADMAP "Multipart gaps — found in the field (2026-10)" (superseded by this cycle's plan; diagnosis correction recorded here)
- ADR-0002 (jdk http client engine), ADR-0003 (non-goals)
- `java.net.http.HttpRequest.BodyPublishers.concat` Javadoc (`@since 16`, content-length aggregation)
- RFC 7578 (multipart/form-data)

## Status

- [x] 1. Content-source model + FileContent
- [x] 2. Streaming engine
- [x] 3. N files per request
- [x] 4. Part Content-Type
- [x] 5. Release 0.6.0
