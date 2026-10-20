# ADR-0078: Multipart Size-Only Seam — Measuring Without Building the Publisher Graph

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 — Hot-Path Audit №4 (the request-side multipart build zone: `MultipartBodyPublisher`, `HttpTemplate.multipartSendSize`, `MetricsNotifier.requestBytes`)

---

## 1. Context & Motivation

ADR-0077 closed the response-decode slice of the hot-path audit line (ADR-0055 → ADR-0072 → ADR-0075 → ADR-0076 → ADR-0077) and recorded its request-side bookmark: `MultipartBodyPublisher.of(boundary, parts)` was built up to **three times per multipart call** —

1. the engine (`JdkHttpEngine` / `OkHttpEngine` / `Apache5HttpEngine` / `NettyHttpEngine` — necessary, the publisher is the body),
2. `HttpTemplate.multipartSendSize` — for the `"Sending size: N"` line of the INFO log,
3. `MetricsNotifier.requestBytes` — for the request-bytes metric —

and the latter two built the **whole publisher graph** only to read `.contentLength()`: per-part header `byte[]`s, per-part `BodyPublisher` wrappers, the `ofFile` / `ofInputStream` / `ofStream` content publishers, the closing-delimiter array and its wrapper, and the `concat` composition — on every multipart call, in every engine (all four engines render multipart through the shared JDK wire model). The INFO leg is enabled by default (the JDK default `System.Logger` configuration reports `isLoggable(INFO) = true`), so in a default deployment the second build is per-call; the metrics leg runs on every metric notification for a multipart request.

The audit of the zone found the rest **clean** and records it so later sessions do not re-audit it:

- `of` — one `crlf` array shared across all parts, one sequence `ArrayList`, the `concat` composition (per-call by nature);
- `partHeaders` / `quoted` — plain `StringBuilder`, one `byte[]` per part (the only per-part allocation a size probe can avoid);
- `ofStream` — the subscriber factory and its `AtomicBoolean` are per-subscription, not per-construction (per-call by nature);
- the engine rendering itself (ADR-0070 lineage) — the publisher is the body, construction is required.

---

## 2. Key Decisions & Architecture

### Decision 1: A Size-Only Seam on `MultipartBodyPublisher`

A new public seam — `MultipartBodyPublisher.contentLength(boundary, parts)` — computes the exact rendered size without building the publisher graph:

```java
public static long contentLength(String boundary, List<MultipartPart> parts) {
    long total = closingDelimiter(boundary).length;
    for (MultipartPart part : parts) {
        total += partHeaders(boundary, part).length;
        long content = partContentLength(part);
        if (content < 0) {
            return -1;
        }
        total += content + 2;
    }
    return total;
}
```

- **Byte parity is structural, not accidental:** the per-part framing reuses the *same* `partHeaders(boundary, part)` byte builder that `of` uses, and the closing delimiter is extracted into a shared `closingDelimiter(boundary)` helper used by both paths — one source of truth for the framing bytes.
- **Per-kind content length mirrors `contentPublisher` exactly:** `byte[]` → `length`; `Path` → `Files.size` after the same existence probe and the same `RuntimeException` wrap that `ofFile` gets in `of` (JDK finding below); `Supplier` → the declared `part.contentLength()` (`null` = unbounded = `−1`, which `ofInputStream` reports as `−1`); anything else → the same `IllegalStateException`.
- **`−1` propagation mirrors `concat`:** the first part with an unknown content length short-circuits the total to `−1` — the same rule `BodyPublishers.concat` applies.

The allocation profile of the probe is now: the per-part header `byte[]`s (unavoidable for byte parity — the framing strings are UTF-8) plus the one closing-delimiter array. Gone: the per-part `BodyPublisher` wrappers, the content publishers (`ofFile` / `ofInputStream` / `ofStream`), and the `concat` composite — for a call with `N` parts, that is `3N + 2` objects and the JDK file/stream probes per redundant build.

### Decision 2: Probe, Not Build — Two Pinned Contracts

The seam is a *measurement*, not a rendering:

- **It never opens the stream source.** The probe reads the declared length and does not call the user's `Supplier` — pinned by a counting-supplier test. (The rendered publisher also defers the open, but the probe must not regress into a "cheat" that opens and drains the stream to measure it.)
- **It stops at the first unbounded part.** Parts after the first `−1` are not probed, so a part that would fail rendering (e.g. a missing file) does not fail the *measurement* — pinned by an unknown-part-then-missing-file test. The engine's `of(...)` call remains the full validator: if a later part is unrenderable, the request still fails there, exactly as before. The metrics probe already documented this stance ("a part that cannot be rendered does not fail the metrics probe"); the INFO log line inherits it with identical behavior to before for every input the engine would accept.

### Decision 3: Rewire the Two Secondary Builds; the Engine Keeps Building

`HttpTemplate.multipartSendSize` (the INFO log line) and `MetricsNotifier.requestBytes` (the request-bytes metric) now call the seam. The engines keep calling `of(...)` — there the publisher *is* the body. No public API moved; the seam is a pure addition to the public `MultipartBodyPublisher` surface.

### JDK 21 Finding: `ofFile` Rejects Only a Missing Path at Build Time

The `Path` parity work surfaced a JDK subtlety (verified by probe on the project's JDK 21.0.12): `BodyPublishers.ofFile(path)` (delegating to `RequestPublishers.FilePublisher.create`) checks only `Files.exists(path)` at construction — a non-regular existing path (a **directory**) is accepted at build time, its `contentLength()` is the directory-entry size (e.g. 4096), and the failure surfaces lazily at subscribe time. The seam mirrors exactly that (exists probe + `Files.size`, same wrap), and the behavior is pinned by a directory test on both `of` and the seam, so a future JDK tightening the probe cannot silently desynchronize the two.

### Known Gaps & Non-Goals

- The probe still materializes the per-part header `byte[]`s. A zero-allocation probe would need a UTF-8-aware byte count of the framing strings (the boundary / names / filenames may be non-ASCII) — deferred: the header arrays are small and the saved objects are the point.
- The directory quirk is mirrored, not fixed — parity with the rendered publisher is the contract.
- The probe is request-side only; the response-side zone was closed by ADR-0077.

---

## 3. Compatibility & Verification

- **Behavioral parity:** for every part kind the seam returns exactly `of(boundary, parts).contentLength()` (byte[] parts, filename parts, `Path` parts, declared-length stream parts, zero-length stream parts, mixed sets); `−1` propagates exactly as `concat` does; the `Path` / unsupported-content exceptions match the rendered publisher in type, cause type and message. The only observable differences: (a) the INFO log line and the request-bytes metric no longer allocate the publisher graph (values unchanged); (b) the probe stops at the first unbounded part, so a *later* unrenderable part does not fail the measurement (the engine still fails the request there).
- **Eleven new tests, TDD** (`MultipartBodyPublisherContentLengthTest`, all red before the seam — the class did not compile — and green after):
  - byte-parity matrix: `of(...).contentLength()` agreement for a mixed part set (text, bytes, filename, `Path`, declared-length stream, zero-length stream);
  - the hand-computed RFC 7578 body size (the literal wire bytes of a one-part body) and the empty-parts-list closing-delimiter case;
  - `−1` propagation (known part + unbounded stream part), zero-length stream part counting as 0 (the `ofStream` → `noBody` short-circuit), `Path` size accounting;
  - exception parity: missing `Path` (wrapped `FileNotFoundException`) and unsupported content (`IllegalStateException`) on both `of` and the seam;
  - the probe-not-build pins: a counting `Supplier` is never opened by the probe, and an unknown part before a missing file yields `−1` without the later part failing the probe;
  - the JDK-21 directory finding pinned on both sides.
- **Existing call-site pins unchanged and green:** `MetricsNotifierBytesTest` (`multipartSizeIsMeasured`, `multipartWithUnknownPartSizeIsUnknown`, `multipartRenderingFailureIsUnknown`) and `HttpTemplateLoggingTest` (the `"Sending size:"` line) — the rewired legs report the same values as before.
- **Verification:** full gate **1,833 / 1,833** green across all eight modules (root **1,671** = 1,660 + 11, + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + benchmarks 37).
- **Javadoc gate untouched:** one new public static method with full Javadoc; all other main-source changes are method bodies, one extracted helper, and `//` comments — no existing signature moved.
- **Coverage (re-measured, two fresh reports):** `MultipartBodyPublisher` stands at **100% on every counter** (BRANCH 36/36 included) on both runs. Core totals: BRANCH **4,071–4,073 / 4,483 = 90.81–90.85% — a new record** (superseding the 4,058/4,469 = 90.80% held at #336; the on-disk report stands at 4,071, the ±1–2 run-to-run variance from the timing-dependent `ServerSentEventReconnector` branch is unchanged), INSTRUCTION 98.13%, LINE 97.78%, COMPLEXITY 89.06–89.11%, CLASS/METHOD 100%. All 14 branches of the seam are covered on both sides by the new suite.
