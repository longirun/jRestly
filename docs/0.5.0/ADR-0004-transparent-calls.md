# ADR-0004: Transparent method calls as the API contract

> 2026-08-21 · Accepted (retroactive — the decision emerged during `@ExpectStatus` work in 0.4.0; recorded to preserve the rationale) · ✅ Preserved through the 0.5.0 rewrite (2026-08-24): transparent calls, per-method `@OnError`/`@ExpectStatus` contract intact on the new engine.

## Context

Wrappers like `Response<T>` / `Call<T>` force per-call unwrapping and make controller interfaces noisy. The wrapper question resurfaced during `@ExpectStatus` design and was rejected.

## Decision

- The method return type IS the contract: the deserialized body type, `String` for raw payloads, `void` for none. No `Response<T>` — ever.
- Errors travel as exceptions: `@OnError(statuses, errorObject)` → `HandledException` (details, status, headers); anything outside the contract → `UnexpectedStatusException` with the raw payload.
- Auth capture is declarative (`@SetAuthDetails` on the login method) — error and auth contracts stay per-method, unlike global decoders in Retrofit/Feign.

## Consequences

- No `Call.cancel()`-style lazy handles — acceptable for both niches.
- Exception-based error channel dictates `assertThrows`-style test usage — already proven in jRestly's own suite.
