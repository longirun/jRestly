# ADR-0077: DTO Decode — Single Codec Resolution & the Wire-Byte Size Line

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 — Hot-Path Audit №3 (the response-decode zone: `processResponse` / `ResponseBody` buffered legs, `RetryAfter`, `MultipartBodyPublisher`)

---

## 1. Context & Motivation

The ADR-0055 → ADR-0072 → ADR-0075 → ADR-0076 line established the discipline: *a disabled log level must not pay for message construction; a per-call lookup must not run twice.* The response-decode zone — the tail of `processResponse` where a buffered body becomes the caller's return value — was the next free audit slice (the #335 handoff). The DTO leg of that tail is the hottest decode branch of the library: **every** non-stream, non-raw response flows through it, and its INFO log line is on by default (the JDK default `System.Logger` configuration reports `isLoggable(INFO) = true`).

The audit found **two** allocation/lookup zones, both on the DTO legs of `ResponseBody.bufferedOptional` / `bufferedRaw`, both enabled by default:

1. **The codec was resolved twice per call.** The leg resolved the lazily supplied codec once inside the INFO block (for the `prettyPrint` preview) and a second time for the `deserialize` call. The comment even documented the doubling ("resolved once per use (log preview, then deserialize)"). In the real wiring the supplier is `() -> responseCodec(responseHeaders)`, so each `get()` is a full `Content-Type` header scan (`contentTypeOf`) plus a `CodecRegistry.resolveOrDefault` (media-type normalization — trim / substring / `toLowerCase` — plus a linear scan of every registered codec). Per DTO response: two header scans, two normalizations, two registry scans.
2. **The whole payload was materialized for a log number.** The INFO line reported `"Response size: " + payload.length() + " bytes"`, where `payload = new String(bytes, UTF_8)` — the entire response body decoded into a temporary `String` (a 1 MB JSON body → a 1 MB transient String) whose only consumer was `.length()`. Worse, that number was a UTF-8 **character** count labeled "bytes", while the sibling `byte[]` leg reported the true `bytes.length` for the same body — the "response size" of one payload depended on the declared return type.

The rest of the audited zone was found **clean** and is recorded here so later sessions do not re-audit it:

- `isStreamType` — `instanceof` pattern match, allocation-free; `isMissingResource` — three integer comparisons.
- `resolveCharset` / `parseCharsetFromContentType` — one plain header scan; the split/parse work happens only when a `Content-Type` is present and is required for every text materialization anyway.
- `lineStream` / `eventStream` — lazy `BufferedReader.lines()` artifacts, allocation deferred to first pull; `consumePayload` — `readAllBytes` + try-with-resources, no extras.
- `wrapForTrailers` — pass-through (the original stream) unless a trailer holder actually exists; `closeQuietly` — tolerant close.
- `streamingPayloadLine` — the stream-branch log lines of `processResponse` all sit inside `isLoggable(INFO)` gates (the ADR-0076 pattern), so the string is built only for a reader.
- The `byte[]` / `String` / `Void` legs never resolve the codec (bare-template survival, pinned by `rawBindingsNeverResolveCodec`); the String leg's `payload` string **is** the return value, so its `payload.length()` adds no allocation (its character-count-in-"bytes" label is a known, accepted quirk — see Decision 2).
- `RetryAfter` — static formatters, per-call work confined to the parse itself; the `replaceAll("\\s{2,}", " ")` runs only on the last-resort asctime fallback for unparseable headers.
- The buffered error paths (`onErrorParser` / unexpected-status payload materialization) run only on the exceptional contour — acceptable.
- **Recorded for the next session (request side, not fixed here — session volume budget):** `MultipartBodyPublisher.of(boundary, parts)` is built up to **three** times per multipart call — the engine (necessary), `HttpTemplate.multipartSendSize` for the INFO log line, and `MetricsNotifier.requestBytes` for metrics — the latter two building the whole publisher graph (per-part header byte arrays, `BodyPublisher` objects, the `concat` composition) only to read `.contentLength()`. A size-only seam on `MultipartBodyPublisher` is the natural fix.

---

## 2. Key Decisions & Architecture

### Decision 1: One Codec Resolution per DTO Leg

Each DTO leg resolves the supplier exactly once, before the gate, and shares the result:

```java
PayloadCodec resolvedCodec = codec.get();
if (logger.isLoggable(System.Logger.Level.INFO)) {
    logger.log(System.Logger.Level.INFO, "Response size: " + bytes.length + " bytes\n"
            + resolvedCodec.prettyPrint(bytes, contentType, payloadCharset));
}
return Optional.ofNullable(resolvedCodec.deserialize(bytes, returnType, contentType));
```

The lazy-supplier contract is preserved exactly: the `byte[]` / `String` / `Void` legs still never call the supplier (a bare template with no registry codec survives those legs). The contract becomes *exactly one resolution per DTO leg, independent of log level* — pinned by a counting-supplier test in both the verbose and the quiet configuration, on both legs. The exception-visibility order is unchanged: a throwing supplier aborts the leg before the log line in either the old or the new shape.

### Decision 2: The Size Line Reports the Wire Byte Size

The DTO size line uses `bytes.length` — the actual number of bytes on the wire — instead of the length of a materialized UTF-8 preview string. Consequences:

- **No second decode of the body for logging.** The preview string existed only to be measured; the `prettyPrint` preview (which *is* the readable part of the line) still receives the payload charset, untouched.
- **Size is invariant to the return-type binding.** The same body now reports the same size whether it is bound to `byte[]`, to a DTO, or (in the byte[] leg's case) to the raw contour. For ASCII payloads the line is byte-for-byte identical to before; for multi-byte payloads the old line was a character count mislabeled "bytes" — the new one is what it says.
- **Known gap (deliberate, out of scope):** the `String` leg keeps reporting the decoded character count (`payload.length()`), because that string is the return value itself — no allocation is saved by changing it, and its pinning predates this audit. The byte[] leg and the DTO legs are now consistent with each other; the String leg is the documented exception.

### Known Gaps & Non-Goals

- With INFO enabled, the `prettyPrint` string is still built — correct, the reader exists (ADR-0076 stance: the gate removes the *no-reader* cost only).
- The gate stays a per-call `isLoggable` query, not a cached boolean (ADR-0076).
- The multipart triple-build (Context, clean-zone note) is recorded, not fixed — it is the request-side seam of the next audit slice.

---

## 3. Compatibility & Verification

- **Behavioral parity:** DTO decode results are identical on both legs; the supplier is now called exactly once per leg instead of twice (observable only for non-idempotent suppliers — the real wiring's `responseCodec` lookup is idempotent). The only user-visible difference is the DTO size line: wire byte size instead of the materialized preview's character count (identical for ASCII bodies).
- **Four new tests, TDD** (the red-first pair went red before the fix; all four are behavior pins after):
  - `ResponseBodyTest` — "bufferedOptional: the DTO leg resolves the codec supplier exactly once (ADR-0077)" (counting supplier, verbose + quiet)
  - `ResponseBodyTest` — "bufferedRaw: the DTO leg resolves the codec supplier exactly once (ADR-0077)"
  - `ResponseBodyTest` — "bufferedOptional: the DTO size line reports the wire byte size, not a materialized preview (ADR-0077)" (a 1-character / 3-UTF-8-byte body: the old code logged "1 bytes", the new one "3 bytes" — red before the fix)
  - `ResponseBodyTest` — "bufferedRaw: the DTO size line reports the wire byte size, not a materialized preview (ADR-0077)"
  - Two existing pin tests were re-named and re-expected from the UTF-8-preview framing to the wire-byte-size framing (`"Response size: 1 bytes"` → `"Response size: 2 bytes"` for the 2-byte body; the `prettyPrint` payload-charset pinning is retained).
- **Verification:** target suite `ResponseBodyTest` 38/38; full gate **1,822 / 1,822** green across all eight modules (root **1,660** = 1,656 + 4, + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + benchmarks 37).
- **Javadoc gate untouched:** `ResponseBody` is package-private; all main-source changes are method bodies and `//` comments — no public signature moved.
- **Coverage (re-measured from fresh reports):** `ResponseBody` stands at 100% on every counter (BRANCH 118/118 included). Core totals: BRANCH **4,058/4,469 = 90.80% — the #335 record is held** (three fresh re-measures at #336 ranged 4,056–4,058; the ±1–2 run-to-run variance is a timing-dependent branch in `ServerSentEventReconnector`, the on-disk report stands at 4,058), INSTRUCTION 98.13%, LINE 97.77%, COMPLEXITY 89.06%, CLASS/METHOD 100%.
