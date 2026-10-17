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

## Amendment #235 (Session #235, 2026-10-17): Eager validation of @ExpectStatus and @FollowRedirects declarations

- **Problem:** All method-level and class-level declarative annotations with configurable constraints are validated fail-fast during proxy creation in `ControllerValidator`, with two historical exceptions: `@ExpectStatus` and `@FollowRedirects`.
  1. An empty `@ExpectStatus(statuses = {})` declaration silently turned every HTTP response (including standard 200 OK) into an `UnexpectedStatusException` at runtime because `isExpectedStatus` evaluated `expectStatuses.contains(code)` on an empty list. Furthermore, out-of-range status values (< 100 or > 599) escaped validation, unlike `@OnError` and `@Retry`.
  2. A negative hop count `@FollowRedirects(count < 0)` was accepted at proxy creation; at runtime, redirect loops did not execute (`hopsLeft > 0` was immediately false), while spurious `"Too many redirects, returning the last response"` messages were logged upon receiving 3xx responses.
- **Decision:** `ControllerValidator` now eagerly validates `@ExpectStatus` and `@FollowRedirects`:
  1. `@ExpectStatus` requires a non-empty `statuses` array (`@ExpectStatus statuses must not be empty`), and every status code must fall in the valid HTTP range `[100, 599]` (`@ExpectStatus status must be in [100, 599], was <status>`).
  2. `@FollowRedirects` requires a non-negative hop count `count >= 0` (`@FollowRedirects count must not be negative, was <count>`).
  Violations are aggregated into `ControllerValidationException` alongside existing declaration issues at proxy creation. Valid usages (`statuses = {200, 201}`, `count = 0`, `count = N`) remain fully backward-compatible with zero regressions.
- **Verification:** Unit tests `rejectsInvalidExpectStatusConfigurationEagerly` and `rejectsInvalidFollowRedirectsConfigurationEagerly` in `ControllerValidationTest` (red-first on invalid declarations, green on valid ones). Core suite at **1178**, total gate at **1258/1258** passing tests under `-Werror`.

