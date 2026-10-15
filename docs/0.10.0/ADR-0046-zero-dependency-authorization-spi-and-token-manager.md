# ADR-0046: Zero-Dependency Authorization SPI, TokenManager and Transparent 401 Retry

**Status:** Accepted  
**Date:** 2026-10-15  
**Session:** #126

---

## Context

Production microservices and third-party API integrations heavily rely on authentication:
- **Bearer Tokens / OAuth2 / JWT:** 90%+ of cloud and enterprise APIs.
- **HTTP Basic Authentication:** RFC 7617 credentials encoded in Base64.
- **API Keys:** Header-based (`X-API-Key`) or query-based (`?api_key=...`).

Previously, jRestly provided only:
1. Low-level static headers via `@RequestHeader("Authorization: ...")` or `SimpleModuleInfo.authHeader(name, value)`.
2. Legacy `AuthProvider` / `AuthHeaderProvider` (ADR-0001) capturing credentials via `@SetAuthDetails` on response headers.

This approach was insufficient for modern cloud environments:
- **Cache Stampede / Thundering Herd:** When 100+ concurrent threads (e.g. Java 21 virtual threads) make calls after token expiry, they would concurrently invoke the auth endpoint, hammering the authorization server.
- **No Token Lifecycle & Refresh Buffer:** No built-in awareness of token expiration timestamps (`expiresAt`), time-to-live (`ttl`), or proactive refresh before deadline.
- **Lack of Transparent 401 Retry:** When an authorization server revokes or expires a token earlier than expected, production clients should invalidate the cached token, acquire a fresh one, and transparently retry the request once before propagating failure.
- **Boilerplate:** Constructing RFC 7617 Basic Auth or query-based API keys required repetitive manual code.

## Decision

1. **Token & Expiration Model (`ru.jrestly.auth.Token`):**
   - Immutable record `Token(String value, Instant expiresAt)`.
   - Factory methods: `Token.of(value)`, `Token.of(value, Instant expiresAt)`, `Token.of(value, Duration ttl)`.
   - Expiration checks: `isExpired()` and `isExpired(Duration refreshBuffer)` to proactively refresh tokens before clock skew or network transit causes rejection.

2. **Single-Flight Token Manager (`ru.jrestly.auth.TokenManager`):**
   - Core interface providing `String getToken()` and `void invalidate()`.
   - Built-in `SingleFlightTokenManager` utilizing `AtomicReference<CompletableFuture<Token>>`:
     - If a valid token is cached and not within the `refreshBuffer`, it is returned immediately without locking or contention.
     - When a token is absent or expired, exactly **one** concurrent thread initiates acquisition via the configured `Supplier<Token>`. All other concurrent callers await the same in-flight `CompletableFuture.join()`.
     - In case of failure, in-flight reference is cleared and the exception is propagated, enabling subsequent callers to retry.
   - Convenience factories: `TokenManager.of(Supplier<Token>)`, `TokenManager.of(Supplier<String>, Duration ttl)`, `TokenManager.ofPerpetual(Supplier<String>)`.

3. **Standard Zero-Dependency Auth Interceptors (`ru.jrestly.auth`):**
   - **`BearerAuth`:** Attaches `Authorization: Bearer <token>`. Backed by `TokenManager` (supporting static strings, dynamic suppliers, or full lifecycle managers). Annotated with `@Order(-1000)` so credentials are stamped before general interceptors run. Bypasses requests originating from methods annotated with `@Authorization`.
   - **`BasicAuth`:** RFC 7617 implementation using standard `java.util.Base64` from `java.base`. Supports static username/password or dynamic suppliers. Annotated with `@Order(-1000)`.
   - **`ApiKeyAuth`:** Supports `Location.HEADER` (e.g. `X-API-Key`) and `Location.QUERY` (RFC 3986 percent-encoded URI query parameters). Annotated with `@Order(-1000)`.

4. **Transparent 401 Unauthorized Retry:**
   - Client and ModuleInfo support `tokenManager(TokenManager)` and `retryOnUnauthorized(boolean)`.
   - When configured, upon receiving an HTTP 401 response:
     - If `retryOnUnauthorized == true` and `tokenManager != null`, the client invalidates the cached token (`tokenManager.invalidate()`), reapplies request interceptors with a freshly acquired token, and repeats the request once.
     - Both synchronous (`exchange()`) and asynchronous (`exchangeAsync()` via non-blocking `thenCompose`) execution flows are fully supported.
     - If the second attempt also returns 401, the response is delivered normally to prevent infinite retry loops.
     - Requests to endpoints annotated with `@Authorization` (login endpoints) bypass 401 retry to avoid false retry loops on invalid credentials.

5. **Fluent Client Ergonomics:**
   - `JRestlyClient.Builder` and `SimpleModuleInfo.Builder` provide:
     - `bearerAuth(String token)`
     - `bearerAuth(Supplier<String> tokenSupplier)`
     - `bearerAuth(TokenManager tokenManager)` (automatically enables `retryOnUnauthorized(true)`)
     - `basicAuth(String username, String password)`
     - `basicAuth(Supplier<String> usernameSupplier, Supplier<String> passwordSupplier)`
     - `apiKeyHeader(String headerName, String apiKey)`
     - `apiKeyHeader(String headerName, Supplier<String> apiKeySupplier)`
     - `apiKeyQuery(String paramName, String apiKey)`
     - `apiKeyQuery(String paramName, Supplier<String> apiKeySupplier)`
     - `tokenManager(TokenManager tokenManager)`
     - `retryOnUnauthorized(boolean retryOnUnauthorized)`

## Consequences

- **Zero-Dependency Guarantee:** All token management, RFC 7617 base64 encoding, and single-flight concurrency rely strictly on `java.base` (`CompletableFuture`, `AtomicReference`, `Base64`).
- **Production-Ready Resilience:** Eliminates cache stampede under high concurrent load (such as Java 21 Virtual Threads) and provides automatic self-healing on token invalidation.
- **Full Backward Compatibility:** Existing `AuthProvider` and `@RequestHeader` mechanisms remain untouched.
