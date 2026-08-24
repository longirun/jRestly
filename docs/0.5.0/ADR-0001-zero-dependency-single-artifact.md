# ADR-0001: Zero runtime dependencies, single artifact

> 2026-08-21 · Accepted

## Context

jRestly targets API test automation and embeddable SDK cores. As of 0.4.0 the POM drags HttpClient 4.5 (EOL branch), Jackson ×4, commons-io/lang3, log4j — an embedding-killer set.

## Decision

- Single artifact `ru.jrestly:jrestly`, forever. No `-jackson`/`-gson`/`-all` module matrix — it contradicts the "lightweight" message and adds JitPack friction.
- Codec adapters live in the same jar behind an internal SPI, compiled `compileOnly`: never in the POM, instantiated only when their library is on the classpath.
- Codec resolution at build time: explicit builder setting > classpath probe (`Class.forName`, jackson first, Gson/Moshi later) > fail fast with an actionable message.
- ServiceLoader rejected: `ServiceConfigurationError` on provider failure is messy; an explicit probe chain is predictable and ~20 lines.
- Default wire format becomes compact (pretty printing stays in response logs) — breaking, acceptable in 0.x.

## Consequences

- Host projects inherit zero transitive dependencies; SDK authors plug their own codec.
- Jackson adapter targets Jackson 2.x; a Jackson 3 needs a new adapter revision.
- An adapter with its own heavy dependency tree (e.g. resilience4j) may become a module point-wise, when it exists — never preemptively.
