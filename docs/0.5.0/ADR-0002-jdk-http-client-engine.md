# ADR-0002: java.net.http — the only HTTP engine

> 2026-08-21 · Accepted

## Context

The engine is Apache HttpClient 4.5 (EOL maintenance branch) and the template vocabulary is hard-bound to it; the path resolver even borrows `StrSubstitutor` from log4j-core. Zero-dep goal (ADR-0001) + Java 21 baseline point to the JDK client.

## Decision

- `java.net.http.HttpClient` is the single engine. No transport SPI, no HC adapter — rejected as speculative generality; revisit via a new ADR on real demand.
- An internal wire model (method, URI, headers, byte body / multipart parts) decouples template logic from the engine.
- Redirects: manual loop with a hop counter; 301/302/303 → GET, 307/308 → method + body; the final response is returned to the caller — fixes the current bug (result discarded, caller got null; only 302 recognized). On a cross-origin hop (scheme/host/port change) ambient auth headers supplied by the AuthProvider are dropped — matching browser/curl behavior (curl CVE-2018-1000120); explicit `@RequestHeader` values survive.
- Multipart: own minimal RFC 7578 writer — file parts and text `@RequestParam` parts.
- Timeouts: connect → HttpClient, socket → per-request; connection-request dropped (no pool-queue semantics in the JDK client).

## Consequences

- HC4 vocabulary disappears; redirect/multipart tests updated to the new semantics.
- Engine quirks (malformed responses throwing instead of yielding a status) are ours to own — accepted cost of control.
- HTTP/2 comes free where servers support it.
