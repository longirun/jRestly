# ADR-0020: SSL/TLS configuration, custom certificates & mTLS

> 2026-10-15 · **Proposed** · Concept and design for the SSL/TLS configuration epic.
> Addresses candidate #1 from the active zone of DRAFT.md and the maintainer's 2026-10-14
> mailbox guidance on library maturity vectors ("донастройка ssl (например, кастомные серты)").

## Context

In real-world enterprise environments, microservice integrations, and test automation
frameworks, declarative HTTP clients frequently need to communicate over secure HTTPS
channels that deviate from the public Web PKI:

1. **Private corporate PKI / Internal CA:** Enterprise services often issue certificates
   signed by an internal corporate Certificate Authority (CA) not present in the standard
   JDK `cacerts` truststore.
2. **Self-signed certificates:** In local integration tests, staging environments, and mock
   servers (e.g. WireMock HTTPS), self-signed certificates are prevalent.
3. **Mutual TLS (mTLS / Two-Way SSL):** Zero-trust architectures and sensitive B2B APIs
   require the client to authenticate itself with an X.509 client certificate and private key.
4. **TLS protocol & cipher suite tuning:** High-compliance systems often require pinning TLS
   protocols (e.g. enforcing `TLSv1.3` only) or fine-tuning hostname verification via
   endpoint identification algorithms.

Currently, jRestly initializes the underlying `java.net.http.HttpClient` in
`AbstractHttpClient.createConnection()` using `HttpClient.newBuilder()`, which implicitly falls
back to the JVM-wide `SSLContext.getDefault()`. There is no declarative or builder-level hook
to configure a custom `SSLContext` or `SSLParameters` without subclassing `AbstractHttpClient`
and overriding `createConnection()`.

### Constraints & Invariants

1. **Zero-dep core:** Rely strictly on Java 21 standard library classes from `javax.net.ssl`
   (`SSLContext`, `SSLParameters`) present in `java.base`. No BouncyCastle or external TLS wrappers.
2. **Strict backward compatibility:** Existing `ModuleInfo` implementations must not break;
   default methods must preserve the platform default behavior (`SSLContext.getDefault()`).
3. **Transparent & declarative:** Retain all architectural invariants (ADR-0003 anti-combination,
   ADR-0004 transparent methods without `Response<T>`).
4. **Fail-fast builder semantics:** Passing `null` to builder methods fails fast with
   `NullPointerException`, while leaving the builder methods uncalled preserves the platform default.

---

## Decisions

### 1. SPI / Configuration surface: `ModuleInfo`

We add two default methods to `ru.jrestly.ModuleInfo`:

```java
/**
 * The SSL context used for secure HTTPS connections. When {@code null}
 * (the default), the underlying HTTP client uses the platform default
 * SSL context.
 *
 * @return the SSL context, or {@code null} for platform default
 */
default SSLContext getSslContext() {
    return null;
}

/**
 * The SSL parameters used for secure HTTPS connections. When {@code null}
 * (the default), the underlying HTTP client uses the default SSL parameters.
 *
 * @return the SSL parameters, or {@code null} for defaults
 */
default SSLParameters getSslParameters() {
    return null;
}
```

Since both return `null` by default, all existing third-party or internal `ModuleInfo`
implementations continue to compile and function without modification.

### 2. Builder ergonomics: `SimpleModuleInfo.Builder` & `JRestlyClient.Builder`

Both builders receive fluent configuration methods:

- `sslContext(SSLContext sslContext)`: validates `Objects.requireNonNull(sslContext, "sslContext")`.
- `sslParameters(SSLParameters sslParameters)`: validates `Objects.requireNonNull(sslParameters, "sslParameters")`.

When not set, the fields remain `null`.

### 3. Transport instantiation: `AbstractHttpClient.createConnection()`

During HTTP client initialization, if `sslContext` or `sslParameters` are non-null on the
module configuration, they are passed to the JDK `HttpClient.Builder`:

```java
protected void createConnection() {
    HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(moduleInfo.getConnectTimeout()))
            .version(moduleInfo.getHttpVersion())
            .followRedirects(HttpClient.Redirect.NEVER);

    SSLContext sslContext = moduleInfo.getSslContext();
    if (sslContext != null) {
        builder.sslContext(sslContext);
    }

    SSLParameters sslParameters = moduleInfo.getSslParameters();
    if (sslParameters != null) {
        builder.sslParameters(sslParameters);
    }

    this.httpClient = builder.build();
}
```

### 4. Testing strategy (TDD)

1. **Configuration & fail-fast tests (`SslConfigTest`):**
   - Verify defaults return `null` across `ModuleInfo`, `SimpleModuleInfo`, and `JRestlyClient`.
   - Verify explicit custom instances are passed through to `ModuleInfo`.
   - Verify `null` arguments fail fast with `NullPointerException`.
2. **Integration wire tests (`HttpsSslTest`):**
   - Spin up WireMock with HTTPS enabled on a dynamic port (`dynamicHttpsPort()`).
   - Negative test: client without custom SSL context fails against WireMock self-signed cert
     with an SSL handshake/verification exception.
   - Positive test: client with configured `SSLContext` (trusting WireMock's cert) connects
     and executes declarative requests cleanly.
   - SSLParameters test: client configured with specific `SSLParameters` (e.g. TLSv1.3)
     successfully performs HTTPS exchanges.
