# ADR-0056: Response Charset Resolution for String Payloads

> 2026-10-15 · Accepted · Status: Complete · Next free ADR: 0057

## Context

Audit finding (maintainer's question: "what problems are unplanned and invisible?", 2026-10-15). Response decoding in `ru.jrestly` was split-brained across two contracts:

1. **Streaming path (ADR-0016) honored the declared charset.** `HttpTemplate.processResponse()` wrapped the raw `InputStream` in `new InputStreamReader(stream, resolveCharset(responseHeaders))` for `Stream<String>` and `Stream<ServerSentEvent>` returns, defaulting to UTF-8. `HttpTemplateLineAndEventStreamTest` and `ServerSentEventsAndLineStreamingTest` pin this with a `windows-1251` scenario.

2. **Buffered path hard-coded UTF-8.** Every buffered text materialization used `new String(bytes, StandardCharsets.UTF_8)`, ignoring `Content-Type: ...; charset=...`:
   - `String` return type and `Optional<String>` return type;
   - `@OnError` error payloads and `UnexpectedStatusException.getRawBody()`;
   - stream-consumption error payloads (`consumePayload`);
   - the retry loop's exception payload (`extractResponseBodyString`).

Consequence: the *same endpoint* returned correct text through `Stream<String>` and mojibake through `String` — a silent data corruption for legacy `text/*` APIs that declare `windows-1251`, `ISO-8859-1`, `koi8-r`, etc. (RFC 9110 §8.3.2 makes the declared charset authoritative for text media types; JSON stays UTF-8 by RFC 8259, and raw `byte[]`/DTO paths were already charset-neutral through ADR-0054's byte pipeline).

## Decision

Resolve the response charset **once per response** in `HttpTemplate.processResponse()` via the existing `resolveCharset(responseHeaders)` helper and reuse it for every text materialization:

- `String` and `Optional<String>` returns, `@OnError` payloads, unexpected-status raw bodies, stream-error consumption, and the retry loop's payload mirror the pure text contract of RFC 9110 §8.3.2.
- Fallback is unchanged: missing header, empty value, or unknown charset name resolve to UTF-8 (never fail a call over a bad server hint).
- Quoted (`charset="windows-1251"`) and parameterized media types (`application/x-ndjson; boundary=x; charset=us-ascii`) keep working — the parser rules of the streaming path are reused verbatim.
- JSON/DTO decoding keeps its zero-copy byte pipeline (`jsonCodec.deserializeBytes`); `byte[]` remains untouched. No new public API, no new dependency.

## Consequences & Verification

- **Consistency:** the buffered and streaming contracts now agree; text APIs with legacy charsets stop producing mojibake on `String` returns and in error bodies.
- **Strict Backward Compatibility:** UTF-8 remains the effective behavior whenever no (or an unusable) charset is declared — every previously green response decodes identically.
- **Automated Verification:** new `ResponseCharsetWireMockTest` (6 E2E tests): `windows-1251` `String` return, quoted charset, unknown-charset fallback, missing-charset fallback, `Optional<String>`, `UnexpectedStatusException.getRawBody()`.
- **Gates:** **1042/1042 tests** across 135 classes (0 failures, 0 errors); `compileJava`/`compileTestJava` 0 warnings under `-Xlint:all -Werror`; `javadoc` 0 warnings under `-Xdoclint:all -Werror`; 0 dependencies in the root Maven POM.
