# ADR-0045: Zero-Dependency Standalone Mode and Fallback NoOpJsonCodec

**Status:** Accepted  
**Date:** 2026-10-15  
**Session:** #125

---

## Context

jRestly is founded on the **Zero-Dependency Core** principle: the runtime core depends strictly on Java 21 (`java.base`, `java.net.http.HttpClient`) without external dependencies in `implementation`. External JSON integrations (Jackson, Gson, Moshi) are strictly `compileOnly` and resolved at runtime via the `JsonCodecResolver` probe chain (ADR-0001, ADR-0007).

However, previously `JsonCodecResolver.resolve()` performed an **eager fail-fast** check during `JRestlyClient.builder().build()` / `SimpleModuleInfo.Builder.build()`: if none of Jackson, Gson, or Moshi was present on the classpath, it immediately threw an `IllegalStateException`.

As a consequence, it was impossible to build or use a `JRestlyClient` in a pure Java zero-dependency application, even if the declarative interfaces only required standard Java types:
- `String getUser(@PathVariable("id") UUID id)`
- `byte[] downloadFile(...)`
- `InputStream getStream(...)`
- `Stream<String> readLines(...)`
- `Stream<ServerSentEvent> events(...)`
- `void deleteItem(...)`
- Form-urlencoded or multipart requests without JSON parts.

This created an artificial, hidden dependency on external JSON libraries for non-JSON use cases.

## Decision

1. **Fallback `NoOpJsonCodec` (`ru.jrestly.json.NoOpJsonCodec`):**
   - Provide a safe, immutable, thread-safe singleton fallback codec.
   - `prettyPrint(payload)`: returns `payload` unchanged (conforming to `JsonCodec` best-effort contract).
   - `serialize(value)`: returns `"null"` for `null`; throws `IllegalStateException` on attempts to serialize non-null objects, naming all supported libraries (`jackson-databind`, `gson`, `moshi`) and pointing to `JRestlyClient.builder().jsonCodec(...)`.
   - `deserialize(payload, type)`: returns `null` for `null`, blank, or `"null"` payloads; throws `IllegalStateException` on attempts to deserialize payloads into complex types.

2. **Convenience factory `JsonCodec.noop()`:**
   - Static method on `JsonCodec` returning `NoOpJsonCodec.instance()`.
   - Allows users to explicitly opt into zero-dependency standalone mode even when a JSON library happens to be present transitively on the classpath.

3. **Lazy failure in `JsonCodecResolver`:**
   - When the probe chain (`jackson` → `gson` → `moshi`) finds no matching JSON library, instead of throwing an `IllegalStateException` eagerly at client build time, it returns `NoOpJsonCodec.instance()`.
   - The `IllegalStateException` is deferred until a controller method actually requires serialization or deserialization of complex DTO objects without a configured JSON codec.

4. **Retry probe robustness in `HttpTemplate`:**
   - Align `probePayloadDecode` in `HttpTemplate` to treat `byte[].class` as a raw type (like `String.class` and `Void.class`), avoiding unnecessary codec invocations during retry evaluation.

## Consequences

- **100% Zero-Dependency Standalone Client:** A host application with zero external dependencies can use `JRestlyClient` for `String`, `byte[]`, `InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`, `Void`, query parameters, headers, URL-encoded forms, and multipart binary uploads.
- **Strict Backward Compatibility:** If Jackson, Gson, or Moshi is on the classpath, it is detected and used exactly as before. Explicit `jsonCodec(...)` configuration always takes precedence.
- **Actionable Error Messages:** If a developer defines a DTO method without adding a JSON library, the deferred failure clearly explains the missing dependency and how to resolve it.
