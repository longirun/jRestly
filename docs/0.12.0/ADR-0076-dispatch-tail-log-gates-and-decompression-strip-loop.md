# ADR-0076: Dispatch-Tail Log Gates & the Decompression Strip Loop

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 — Hot-Path Audit №2 (the `HttpTemplate` dispatch tail: per-hop logging, retry scheduling, decompression)

---

## 1. Context & Motivation

**ADR-0055** (Zero-Garbage Dispatch Pipeline) pruned the dispatch pipeline, and ADR-0072/0075 established the discipline: *a disabled log level must not pay for message construction; a shape-driven allocation must not pay for intermediate collections.* A focused audit of the remaining dispatch tail — the send path after the engine returns, the retry scheduler, and the decompression seam — found three zones that still violated it:

1. **`logPerHop`:** the per-hop DEBUG line (`GET http://… -> 200 in 3 ms`) is produced on **every send, every hop** of a call. It built its message string unconditionally — no `isLoggable` pre-check — while its siblings `logRequest`/`logResponse` already honor the level. With DEBUG disabled (the default for every production deployment running at INFO), one `String` concatenation per hop per call, garbage for no reader.
2. **`scheduleRetry` / `scheduleAsyncRetry`:** the retry-schedule DEBUG line (`Attempt 1/3 failed: HTTP 503 — retrying in 412 ms`) is built unconditionally on every failed retry attempt, in both the sync and the async twin.
3. **`handleDecompression`:** the stripped-header list was built with `.stream().filter(…).toList()` — Stream machinery (pipeline, stage, sink) plus a fresh list — and the result was handed to the `WireResponse` compact constructor, which applies the single defensive `List.copyOf` the response contract requires. Two list copies where one was needed, for the most common decompressed shape.

The rest of the audited tail was found **clean** and is recorded here so later sessions do not re-audit it: the lazy `engineOptions` fallback in `send`/`sendAsync` is a defensive path the builder always pre-fills (`HttpTemplateBuilder.build` sets the options unconditionally); `MethodMetadata` caches are precomputed (`CACHE` `ConcurrentHashMap`, `placeholderToken`, static cookie header); the cookie-merge hot path was pruned in session #334; the header-merge / trace-view / response-map zones are ADR-0075.

---

## 2. Key Decisions & Architecture

### Decision 1: Level Gate Before Construction — All Three Log Sites

The exact pattern `logRequest`/`logResponse` already use is applied to the three ungated sites:

```java
if (!logger.isLoggable(System.Logger.Level.DEBUG)) {
    return; // or: skip the log call
}
```

`logPerHop` early-returns (the gate is the whole method's reason to exist); `scheduleRetry` and `scheduleAsyncRetry` wrap the single `log` call. A **disabled DEBUG must not build the message string** — the gate is on the construction, not on the transport, which is what makes it an allocation gate rather than a formatting nicety. The gate checks the `DEBUG` level specifically, matching the level of the guarded line; it does not introduce a new level vocabulary.

### Decision 2: Plain Loop for the Decompression Strip

The `strippedHeaders` assembly in `handleDecompression` is a plain forward loop:

- **Same strip semantics, byte-for-byte:** `Content-Encoding` and `Content-Length` are dropped case-insensitively; **nameless headers are kept** (a `null` name is not a strip target — the same null-tolerance invariant as ADR-0075's name operations); order is preserved.
- **One copy, not two:** the loop fills an `ArrayList` pre-sized to the input, and the `WireResponse` compact constructor's `List.copyOf` is the *only* defensive copy the response contract requires. The stream pipeline's intermediate list and its machinery are gone.
- The `Content-Encoding` *lookup* loop above the strip (first-match scan with `break`) was already a plain loop and is untouched.

### Known Gaps & Non-Goals

- With DEBUG **enabled**, the three lines still concatenate their strings — correct, since the reader exists; the gate only removes the *no-reader* cost.
- The gate is a per-call `isLoggable` query (one virtual call), not a cached boolean: `System.Logger` implementations may change level at runtime, and the call is far cheaper than the concatenation it guards.
- The nameless-header `null`-name tolerance is a data-quality downgrade (non-match / keep), not a validation — same stance as ADR-0075 Decision 2.

---

## 3. Compatibility & Verification

- **Behavioral parity:** with DEBUG enabled the three lines are byte-for-byte identical (same format, same content); with DEBUG disabled the observable difference is *only* the absence of the record and of the string allocation. The strip loop's output list equals the stream pipeline's output list element-for-element, in order.
- **Seven new tests, TDD** (the two gate-skip tests were red before the gates landed; the rest are behavior pins green on first run):
  - `HttpTemplateLoggingTest` — "logPerHop builds no message when DEBUG is disabled (ADR-0076)"
  - `HttpTemplateLoggingTest` — "logPerHop emits the per-hop line when DEBUG is enabled"
  - `HttpTemplateLoggingTest` — "the retry-schedule DEBUG line is skipped when DEBUG is disabled (ADR-0076)" (full `exchange()`, 503→200, zero backoff)
  - `HttpTemplateLoggingTest` — "the retry-schedule DEBUG line is built when DEBUG is enabled (ADR-0076)"
  - `HttpTemplateLoggingTest` — "the async retry-schedule DEBUG line is built when DEBUG is enabled (ADR-0076)"
  - `DecompressionConfigTest` — nameless headers survive the strip, header order preserved
  - `DecompressionConfigTest` — the stripped header list is an immutable copy
- **Verification:** target suites 31/31; full gate **1,818 / 1,818** green across all eight modules (root 1,656 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + benchmarks 37).
- **Javadoc gate untouched:** all main-source changes are `//` comments; no public signature moved.
- **Coverage (re-measured from the fresh report):** BRANCH **4,058/4,469 = 90.80%** — a new record (was 90.72%); all three new gate branches are covered on both sides. INSTRUCTION 98.13%, LINE 97.77%, COMPLEXITY 89.06%, CLASS/METHOD 100%.
