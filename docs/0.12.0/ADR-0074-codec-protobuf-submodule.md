# ADR-0074: Protocol Buffers Payload Codec Submodule (`codec-protobuf`)

* **Status:** Accepted
* **Date:** 2026-10-20
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 Candidate №12 — Codec Ecosystem: Proto/YAML Submodule Research (Phase 2 of ADR-0073)

---

## 1. Context & Motivation

**ADR-0073** delivered the `codec-yaml` submodule and deliberately deferred Protocol Buffers to a second design pass, because protobuf is structurally different from JSON/XML/YAML:

1. **Schema-bound:** every wire type is a protoc-generated `Message` class with its own `Parser`; the codec's contract is "resolve the parser for this Java class", not "claim a media type family" — the media type family is constant and the *type* is the variable.
2. **Binary:** `prettyPrint` has no textual re-format; the payload is bytes.
3. **One-way asymmetry:** deserialization needs the target class (the generated `Parser`); serialization needs only the value (`toByteArray()`).

This ADR closes Candidate №12 Phase 2 with the `codec-protobuf` submodule, keeping the zero-dependency core JSON-only by default.

---

## 2. Key Decisions & Architecture

### Decision 1: `com.google.protobuf:protobuf-java` (full runtime) as the binding engine
The module depends on `com.google.protobuf:protobuf-java:3.25.5` — the standard full runtime (not `protobuf-javalite`):
- the codec's contract is expressed against the `com.google.protobuf.Message` interface, the dominant case in the Java ecosystem;
- `Parser.parseFrom(byte[])` / `Message.toByteArray()` are the canonical entry points — no hand-rolled varint handling, no reflection into wire internals;
- `InvalidProtocolBufferException` is an `IOException` subclass, so the SPI contract ("wrap the underlying failure in the adapter's own exception family") maps directly onto `UncheckedIOException`.

The `protobuf-javalite`-only classpath is a documented Phase-1 non-goal (the codec references the full `Message` interface).

### Decision 2: Media-type family claim
`ProtobufCodec.supports(...)` claims exactly:
- `application/x-protobuf` — the de-facto legacy type used across HTTP tooling;
- `application/protobuf` — the IANA-registered type;
- every structured suffix ending in `+protobuf` (RFC 6839 convention, e.g. `application/vnd.company.v1+protobuf`).

Deliberately **not** claimed: `application/grpc+proto` — gRPC payloads carry a 5-byte framing prefix, so a gRPC-aware codec is a different component (documented non-goal); unregistered variants (e.g. `application/proto`) fail fast through the registry, consistent with ADR-0060's strict selection semantics.

### Decision 3: Parser resolution for an arbitrary target `Type`
`deserialize(byte[], Type, ...)` receives the target type from the declarative layer. The Phase-1 contract is: the type must be a `Class` of a protobuf message. Resolution order:
1. **`PARSER` static field** — the protoc invariant: every generated message class exposes `public static final <Name>Parser PARSER`. Read via reflection, no instance needed;
2. **`getDefaultInstance()` + `getParserForType()`** — for hand-written `Message` implementations without a `PARSER` field;
3. **fail fast** with an `IllegalArgumentException` naming the offending class when neither is present.

Resolvers are cached in a `ConcurrentHashMap<Class<?>, Parser<?>>` — one lookup per message class for the lifetime of the codec. Non-message target types (`String`, records, …) fail fast the same way, so a `@Get` declaring `List<String>` under a protobuf `Content-Type` produces an actionable error instead of a silent mis-bind.

Implementation note: the protobuf-java *well-known types* (`Timestamp`, `Struct`, …) ship with a **private** `PARSER` field (they are hand-maintained, not protoc output), so they resolve through the static-factory branch; user-generated messages resolve through the `PARSER` branch. Both branches are covered by the test suite.

### Decision 4: Serialization — `toByteArray()` and nothing more
`serialize(Object, ...)`: a `Message` / `MessageLite` value serializes via `toByteArray()`; `null` yields an empty payload (SPI contract); any other value fails fast with an `IllegalArgumentException`. There is intentionally **no** property-reflection path: protobuf is schema-bound, so a value that is not a generated message is a programming error the user should see immediately.

### Decision 5: `prettyPrint` renders binary as hex
Best-effort log rendering for a non-text format: 16 bytes per line, space-separated two-digit lowercase hex; `null` → `null`, empty → `""`. No schema-less decoding is attempted (`DynamicMessage` over arbitrary bytes is misleading without the corresponding `.proto`).

### Decision 6: SPI auto-discovery, priority 50
`ProtobufCodecProvider` (registered in `META-INF/services/ru.jrestly.codec.PayloadCodecProvider`) probes `com.google.protobuf.Message` availability and contributes the default codec with **priority 50** — parity with the official JSON/XML/YAML adapters. The families are disjoint, so no tie can change resolution; a client with the submodule on the classpath binds protobuf without any `builder().codec(...)` call, and can still opt out via an explicit JSON-only `CodecRegistry`.

### Decision 7: Core constants (pure addition)
`ru.jrestly.http.MediaType` gains `APPLICATION_PROTOBUF = "application/protobuf"` and `APPLICATION_X_PROTOBUF = "application/x-protobuf"` so declarative `@ContentType`/`@Accept` declarations reference named constants, as with the JSON/XML/YAML members. No existing member changes — the public API is not broken.

---

## 3. Compatibility & Verification

- **Zero-Dep core preserved:** the only new dependency (`protobuf-java` 3.25.5) lives in the submodule's `api` configuration; the core POM keeps exactly 0 runtime dependencies.
- **Gate:** 1,804 / 1,804 tests green across all 8 modules (+19 new: 14 unit in `ProtobufCodecTest`, 5 end-to-end in `ProtobufWireMockTest`). Javadoc clean under `-Werror` in the new module.
- **End-to-end proof (ADR-0060/0073 pattern):** a generated `Message` DTO with `@ContentType(MediaType.APPLICATION_X_PROTOBUF)` goes on the wire byte-for-byte as the codec emits it (verified with WireMock `binaryEqualTo`); an `@Accept`-declared protobuf response binds into the generated message type; the JSON default path stays byte-for-byte intact; SPI auto-discovery lights protobuf up without registration; a JSON-only registry restores fail-fast for protobuf.
- **Non-goals:** gRPC framing (`application/grpc+proto`); `protobuf-javalite`-only classpaths; schema-less `DynamicMessage` binding; collection/generic targets (`List<Message>`) — Phase-1 deserialization is per-message, matching how generated services are actually declared.
