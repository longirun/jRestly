# ADR-0002: java.net.http — the only HTTP engine

> 2026-08-21 · Accepted · ✅ Implemented in 0.5.0 (2026-08-24): `java.net.http` sole engine via `WireRequest`/`WireResponse`; manual redirect loop (301/302/303→GET, 307/308→method+body, hop counter, final response returned); own RFC 7578 `MultipartWriter`; tester suite 61/61 incl. redirect semantics.

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

## Amendment #253

Session #253 (free-audit, 2026-10-17) — the 34th "silent" finding of the series:
the redirect decision above promised "explicit `@RequestHeader` values survive"
the cross-origin auth drop, but the drop was name-based only. `HttpTemplateBuilder`
collected `authHeaderNames` from the raw `AuthProvider.getAuthHeaders()` list, so an
explicit `@RequestHeader` (static on method/type, or a dynamic parameter) that
shadowed an ambient auth header **by name** (the realistic case: a client
`bearerAuth(...)` plus a per-call `@RequestHeader("Authorization")` override — the
merged request already carries the explicit value, per `createHeaders`) was
silently dropped on the cross-origin hop, and the redirected request went out with
no `Authorization` at all. Fix: the drop list is now computed in
`HttpTemplateBuilder.build()` as the ambient auth names **minus** the controller's
explicit `@RequestHeader` names (static + declared dynamic, compared
case-insensitively), so only genuinely ambient credentials are dropped. Residual
edge (documented, not fixed): a dynamic `Map`-typed `@RequestHeader` argument whose
runtime key collides with an ambient auth name is still dropped — the name is only
known at call time, and the name-based policy predates it. Verified red-first by
`CrossOriginRedirectTest.crossOriginRedirectPreservesExplicitAuthOverride` (hop 1
carries the explicit token; hop 2, cross-origin, now carries it too) plus the
existing guards (ambient drop, same-origin keep).
