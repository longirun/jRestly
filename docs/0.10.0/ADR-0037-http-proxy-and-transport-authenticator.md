# ADR-0037: HTTP Proxy & Transport Authenticator Configuration

> 2026-10-15 · Accepted · Status: Complete · Next free ADR: 0038

## Context

In production enterprise deployments and real-world microservice environments, outgoing HTTP requests often cannot be dispatched directly to target servers. Instead, network traffic must traverse corporate forward proxies (HTTP, HTTPS, or SOCKS5 proxies) for security inspection, egress filtering, and compliance auditing.

Furthermore, automated test suites (functional, performance, security, and contract testing) frequently rely on local HTTP proxy listeners (such as Charles Proxy, OWASP ZAP, Fiddler, mitmproxy, or custom mock proxies) to record, verify, and mock external API traffic. Conversely, test runners interacting with local mock servers (like WireMock) often require an explicit bypass of any system-wide operating system proxies (`NO_PROXY`) to prevent unwanted proxy interference.

Prior to ADR-0037, `ru.jrestly` lacked programmatic or declarative configuration for proxying and HTTP transport-level authentication:
1. `HttpClient.Builder.proxy(...)` was never invoked; the underlying JDK client defaulted implicitly to `ProxySelector.getDefault()`, with no API to route through a dedicated proxy host/port or to force direct connections via `HttpClient.Builder.NO_PROXY`.
2. `HttpClient.Builder.authenticator(...)` was never configured; proxy challenges (`407 Proxy Authentication Required`) and server challenges (`401 Unauthorized` with Basic/Digest/Negotiate) requiring transport-level `java.net.Authenticator` could not be resolved automatically.

## Decision

Introduce zero-dependency HTTP proxy routing and transport authentication configuration into `ru.jrestly`:

### 1. Zero-Dep Core Standard Types
Use only standard Java 21 platform types from `java.base`:
- `java.net.ProxySelector`
- `java.net.Proxy`
- `java.net.InetSocketAddress`
- `java.net.Authenticator`
- `java.net.PasswordAuthentication`

Zero external libraries in implementation or compile scope.

### 2. Configuration SPI (`ModuleInfo`)
Extend `ru.jrestly.ModuleInfo` with non-breaking default methods returning `null`:
```java
default ProxySelector getProxySelector() {
    return null;
}

default Authenticator getAuthenticator() {
    return null;
}
```
When `getProxySelector()` returns `null`, the underlying JDK `HttpClient` preserves its default behavior (`ProxySelector.getDefault()`).
When `getAuthenticator()` returns `null`, no authenticator is attached.

### 3. Builder Ergonomics (`SimpleModuleInfo.Builder` & `JRestlyClient.Builder`)
Provide fluent, developer-friendly methods:
- `proxy(ProxySelector proxySelector)`: registers an arbitrary `ProxySelector` (e.g. for dynamic routing, PAC scripts, or complex rules). Rejects `null` fail-fast.
- `proxy(String host, int port)`: convenience method constructing `ProxySelector.of(new InetSocketAddress(host, port))`. Validates non-blank host and valid TCP port (1..65535).
- `noProxy()`: explicitly forces direct connection (`HttpClient.Builder.NO_PROXY`), bypassing any ambient system proxy.
- `authenticator(Authenticator authenticator)`: registers a custom `Authenticator`. Rejects `null` fail-fast.
- `proxyCredentials(String username, String password)`: convenience builder method registering an authenticator responding to `RequestorType.PROXY`.
- `credentials(String username, String password)`: convenience builder method registering a universal authenticator responding to both `RequestorType.SERVER` and `RequestorType.PROXY`.

### 4. Transport Wiring (`AbstractHttpClient`)
In `AbstractHttpClient.createConnection()`:
- If `moduleInfo.getProxySelector() != null`, pass to `builder.proxy(...)`.
- If `moduleInfo.getAuthenticator() != null`, pass to `builder.authenticator(...)`.
- Expose inspection getters `getProxySelector()` and `getAuthenticator()` in `AbstractHttpClient`.

### 5. Validation and Safety
- Fail-fast parameter checks: `Objects.requireNonNull` on objects, host non-blank validation, and port range check `port >= 1 && port <= 65535`.
- Defensive copying and thread-safety.

## Verification & Test Strategy

Comprehensive test coverage across 22 tests (+22 tests, suite 789/789 across 91 classes):
1. **Unit & Configuration Tests (`ru.jrestly.ProxyConfigTest`, 16 tests):**
   - Default null values in `ModuleInfo` and `SimpleModuleInfo`.
   - Explicit `ProxySelector` storage.
   - `proxy(host, port)` address parsing and validation.
   - `noProxy()` direct bypass mapping to `HttpClient.Builder.NO_PROXY`.
   - Custom `Authenticator` storage.
   - `proxyCredentials` and `credentials` helper authentication.
   - Upfront fail-fast validation: `null` selector, `null`/blank host, invalid port (<1 or >65535), `null` authenticator, `null` username/password.
   - Client forwarding and getters on `JRestlyClient` and `AbstractHttpClient`.
2. **Integration Tests (`ru.jrestly.ProxyIntegrationWireMockTest`, 6 tests):**
   - Synchronous GET routed through live local HTTP forward proxy server.
   - `407 Proxy Authentication Required` challenge handling and automatic retry with `Proxy-Authorization: Basic ...` header.
   - Negative test: invalid credentials fail fast with exception.
   - Target server `401 Unauthorized` challenge handling using `credentials(...)`.
   - Direct connection via `noProxy()` confirming 0 proxy requests received.
   - Asynchronous twin `CompletableFuture<TestDto>` executed through HTTP proxy.

## Consequences

- Applications and automated tests can direct jRestly traffic through corporate or local proxies with a single line of configuration.
- Automated tests can reliably use `noProxy()` to isolate local WireMock instances from corporate system proxies.
- Zero external dependencies introduced; 100% backward compatible with existing clients.
- Clean separation between application-level authentication headers (like Bearer tokens in `@RequestHeader`) and transport-level authentication (like Basic/NTLM proxy challenges in `Authenticator`).
