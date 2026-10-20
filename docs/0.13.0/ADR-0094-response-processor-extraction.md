# ADR-0094: ResponseProcessor Extraction — Encapsulating Response Post-Processing, Error Handling, and Streaming Lifecycle

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the ongoing god-class decomposition of `ru.jrestly.http.HttpTemplate` (1,653 lines, WMC 269, 40 missed branches), we extract the shared response tail (`HttpTemplate.processResponse()`: ~126 lines) into the package-private collaborator `ru.jrestly.http.ResponseProcessor`.

---

## 1. Problem & Boundaries

`HttpTemplate` contained the entire response decoding and dispatch lifecycle inside `processResponse()`:
1. **Metadata & Captors:** timing duration calculation, diagnostic wire logging (`HttpDiagnosticLogger`), populating `ResponseHeaders` holder, and invoking ThreadLocal captors (`ResponseHeaders.CURRENT_CAPTOR`).
2. **Auth Details:** invoking `authDetailsConsumer` on received response headers.
3. **Streaming Responses:** terminal trailer wrapping (`ResponseBody.wrapForTrailers`), stream consumption for `@OnError` and unexpected status errors, and dispatching to `Stream<String>` (line stream), `Stream<ServerSentEvent>` (SSE stream), or raw `InputStream`, honoring `Optional` missing-resource empty semantics.
4. **Buffered Responses:** byte extraction, `@OnError` matching via `HttpErrorHandler`, unexpected status validation, and lazy codec decoding (`ResponseBody.bufferedOptional` / `ResponseBody.bufferedRaw`).

This logic represented 126 lines, ~25 WMC complexity points, and multiple boolean branches that contributed to `HttpTemplate`'s god-class profile.

---

## 2. Architecture & Design Decisions

### (D1) Package-Private `ResponseProcessor` Collaborator
`ResponseProcessor` is a package-private collaborator in `ru.jrestly.http`:
```java
final class ResponseProcessor {
    record ResponseConfig(
            Type returnType,
            boolean optional,
            ResponseHeaders responseHeadersHolder,
            Consumer<List<Header>> authDetailsConsumer,
            OnErrorParser onErrorParser,
            List<Integer> expectStatuses,
            boolean conditional,
            Function<List<Header>, PayloadCodec> codecResolver,
            System.Logger logger
    ) { ... }

    ResponseProcessor(ResponseConfig config) { ... }

    <T> T process(WireResponse response, long startNanos) throws IOException { ... }
}
```
All method parameters adhere strictly to rule C8 (≤ 7 parameters) via `ResponseConfig`.

### (D2) Clean Method Decomposition (Rule C5 ≤ 10)
`ResponseProcessor` is decomposed into small, cohesive methods with cyclomatic complexity ≤ 10:
- `process(response, startNanos)`: orchestrator (complexity 4)
- `captureHeadersAndNotify(...)`: metadata, captors, duration, and auth consumer (complexity 6)
- `processStream(...)`: streaming response types, trailer wrapping, SSE, lines, raw streams, optional empty (complexity 10)
- `processBuffered(...)`: buffered byte decode and fallback (complexity 6)
- small helpers for error handling and codec resolution.

### (D3) Lazy Seam in `HttpTemplate`
In `HttpTemplate`, `responseProcessor` is initialized lazily:
```java
    private ResponseProcessor responseProcessor() {
        if (responseProcessor == null) {
            responseProcessor = new ResponseProcessor(new ResponseProcessor.ResponseConfig(
                    returnType, optional, responseHeadersHolder, authDetailsConsumer,
                    onErrorParser, expectStatuses, conditional, this::responseCodec, logger
            ));
        }
        return responseProcessor;
    }
```
`processResponse(...)` delegates directly to `responseProcessor().process(response, startNanos)`.

---

## 3. Verification & Metrics

- **Red-First TDD:** 21 dedicated tests in `ResponseProcessorTest` covering all execution paths: buffered raw String, byte[], Optional present and missing (404/204), captor and auth details notification, line streams, SSE streams, raw InputStreams, `@OnError` matching, unexpected status handling, codec exception handling, and null logger fallback.
- **Collaborator Metrics:**
  - `ResponseProcessor`: 245 lines, WMC 44, Branch coverage: **60/62 (96.77%)**, 0 missed methods.
- **`HttpTemplate` Metrics:**
  - Lines: **1,653 → 1,544** (−109 lines)
  - WMC Complexity: **269 → 239** (−30 complexity, **first time in library history WMC < 250!**)
  - Missed branches: **40 → 35** (−5 missed branches)
- **Library-Wide Metrics:**
  - Full Gate: **2,076 / 2,076** tests passing across all 8 modules (root 1,912 + submodules 164), 0 failures/errors
  - Branch coverage: **93.57%** (4,103 / 4,385) — **NEW ABSOLUTE HISTORICAL RECORD**
  - Complexity coverage: **92.34%** (3,459 / 3,746)
  - Missed branches: **282** — **NEW ABSOLUTE MINIMUM IN LIBRARY HISTORY**
  - Instructions: **98.55%** (31,295 / 31,754)
  - Classes: **100%** (165 / 165)
  - Javadoc clean under `-Werror` across all 8 modules.
