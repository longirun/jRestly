# ADR-0073: YAML Payload Codec Submodule (`codec-yaml`)

* **Status:** Accepted
* **Date:** 2026-10-19
* **Author:** jRestly Autonomous Core Architect
* **Context:** Cycle 0.12.0 Candidate №12 — Codec Ecosystem: Proto/YAML Submodule Research

---

## 1. Context & Motivation

**ADR-0060** established the media-type-aware `PayloadCodec` SPI, the ordered `CodecRegistry` with fail-fast selection, and the `PayloadCodecProvider` SPI for auto-discovery. The **`codec-jackson-xml`** submodule (ADR-0060, phases 1–3) proved the complete pattern: a zero-dependency core that stays JSON-only by default, and an opt-in format adapter that binds request/response DTOs by `Content-Type` / `Accept`.

Candidate №12 extends the codec ecosystem to the two remaining industry formats: **Protocol Buffers** and **YAML**. They differ structurally:

1. **YAML** is a generic, schema-less text format with registered media types — a drop-in member of the media-type family model the SPI was built around.
2. **Protocol Buffers** is binary and *schema-bound*: every payload type is a generated `Message` with its own `Parser`, so the codec's contract is "resolve the parser for this Java class" rather than "claim a media type family". That is a different (second) design axis.

**Decision: Phase 1 = YAML.** It completes the human-readable triangle (JSON / XML / YAML) with the proven submodule pattern and unblocks users immediately. The Protocol Buffers adapter is deferred to **Phase 2 (a separate ADR)** where the `Message`/`Parser` resolution and the `application/x-protobuf` vs `*+protobuf` media-type question get their own design pass.

---

## 2. Key Decisions & Architecture

### Decision 1: Jackson `YAMLMapper` as the binding engine (not raw SnakeYAML)
The module depends on `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2` — the same engine generation as the JSON and XML adapters:
- one binding engine across JSON / XML / YAML: a single exception family (parser `IOException` wrapped in `UncheckedIOException` on the SPI boundary, per the `PayloadCodec` contract);
- records and POJOs bind out of the box through `TypeFactory.constructType(Type)` — identical to the XML adapter's path;
- `java.time` support is available through the same guarded `jsr310` registration (nested holder, `Class.forName` probe — the mapper class never references jsr310 types, so the module works without `jackson-datatype-jsr310` on the classpath);
- raw SnakeYAML would introduce a second configuration surface and Map-first binding semantics; SnakeYAML remains inside the Jackson YAML module as its parsing engine, so nothing is lost.

### Decision 2: Media-type family claim
`YamlCodec.supports(...)` claims exactly:
- `application/yaml` and `text/yaml` — the registered YAML media types (RFC 9512);
- every structured suffix ending in `+yaml` (RFC 6839 convention, mirroring the `*+xml` claim of the XML adapter, e.g. `application/vnd.company.v1+yaml`).

Unclaimed YAML variants (e.g. `application/x-yaml`) fail fast through the registry, consistent with ADR-0060's strict selection semantics.

### Decision 3: SPI auto-discovery, priority 50
`YamlCodecProvider` (registered in `META-INF/services/ru.jrestly.codec.PayloadCodecProvider`) probes `com.fasterxml.jackson.dataformat.yaml.YAMLMapper` availability and contributes `YamlCodec.defaults()` with **priority 50** — parity with the official XML adapter (ADR-0060). The two families do not overlap, so no tie can change resolution; a client with the submodule on the classpath binds YAML without any `builder().codec(...)` call, and can still opt out via an explicit JSON-only `CodecRegistry`.

### Decision 4: Default mapper configuration
`YamlCodec.defaults()` mirrors the XML adapter's defaults: `FAIL_ON_UNKNOWN_PROPERTIES` disabled (forward compatibility with evolving schemas — the dominant YAML-in-config use case) and `WRITE_DATES_AS_TIMESTAMPS` disabled (ISO-8601 dates on the wire). An explicit `YAMLMapper` constructor uses the mapper as is, never reconfigured. Instances are thread-safe.

### Decision 5: Core constants (pure addition)
`ru.jrestly.http.MediaType` gains `APPLICATION_YAML = "application/yaml"` and `TEXT_YAML = "text/yaml"` so declarative `@ContentType`/`@Accept` declarations reference named constants, as with `APPLICATION_XML`. No existing member changes — the public API is not broken.

---

## 3. Compatibility & Verification

- **Zero-Dep core preserved:** the only new dependency (`jackson-dataformat-yaml` 2.18.2, transitively SnakeYAML) lives in the submodule's `api` configuration; the core POM keeps exactly 0 runtime dependencies.
- **Gate:** 1,762 / 1,762 tests green across all 7 modules (+15 new: 10 unit in `YamlCodecTest`, 5 end-to-end in `YamlWireMockTest`). Javadoc clean under `-Werror` in the new module.
- **End-to-end proof (ADR-0060 pattern, phase 2 parity):** a DTO with `@ContentType(MediaType.APPLICATION_YAML)` goes on the wire byte-for-byte as the codec emits it (verified against the codec's own `serialize` output); a `@Accept`-declared `application/yaml` response binds into the declared record; the JSON default path stays byte-for-byte intact; SPI auto-discovery lights YAML up without registration; a JSON-only registry restores fail-fast for YAML.
- **Non-goals:** the Protocol Buffers adapter (Phase 2, separate ADR); YAML awareness in the core `JsonCodec` (the core remains format-agnostic beyond its JSON default); `text/x-yaml`-style unregistered variants.
