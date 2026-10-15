package ru.jrestly;

import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;
import ru.jrestly.http.RetryPolicy;

import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.tracing.TracePropagator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Module configuration: everything a client is built from — identity
 * ({@link #getName()}), the execution environment (base URL and typed
 * auth details via {@link #getEnvironment()}), auth headers
 * ({@link #getAuthProvider()}), the JSON codec, the timeouts, the HTTP
 * protocol version, up-front controller validation, the retry policy,
 * request/response interceptors (ADR-0018), and SSL/TLS configuration
 * (ADR-0020).
 *
 * <p>{@link SimpleModuleInfo} is the canonical implementation: a builder
 * with a default for every setting ({@code baseUrl} is the only
 * required one), and {@link JRestlyClient.Builder} forwards to it so a
 * client can be created without naming the module.
 */
public interface ModuleInfo {

    /**
     * The module name.
     *
     * @return the module name
     */
    String getName();

    /**
     * The client's execution environment: the base URL that relative
     * request URLs are resolved against, and the client's typed auth
     * details (see {@link Environment}).
     *
     * @param <T> the auth-details type the environment works with
     * @return the execution environment
     */
    <T> Environment<T> getEnvironment();

    /**
     * The provider of the client's auth headers: the client asks it
     * whether the client is authorized, updates it when login captures
     * a credential, and reads the headers it attaches to every
     * request.
     *
     * @return the auth provider
     */
    AuthProvider getAuthProvider();

    /**
     * The JSON codec for request and response bodies. The default
     * resolves one from the classpath — jackson, then gson, then
     * moshi, the first found wins (ADR-0007). When none of the supported
     * libraries is present, falls back to {@link ru.jrestly.json.NoOpJsonCodec}
     * allowing pure zero-dependency standalone execution (ADR-0045).
     *
     * @return the JSON codec
     */
    default JsonCodec getJsonCodec() {
        return JsonCodecResolver.resolve();
    }

    /**
     * The connect timeout in milliseconds (default 5 000).
     *
     * @return the connect timeout in milliseconds
     */
    default int getConnectTimeout() {
        return 5_000;
    }

    /**
     * The socket (request) timeout in milliseconds (default 30 000).
     *
     * @return the socket timeout in milliseconds
     */
    default int getSocketTimeout() {
        return 30_000;
    }

    /**
     * The HTTP protocol version (default {@code HTTP_2}).
     *
     * @return the HTTP protocol version
     */
    default HttpClient.Version getHttpVersion() {
        return HttpClient.Version.HTTP_2;
    }

    /**
     * Whether controller interfaces are validated up front (default
     * {@code true}: an invalid controller fails when its proxy is
     * created with a {@link ControllerValidationException}; when off,
     * the problems are only logged and the failing methods throw on
     * call).
     *
     * @return whether controllers are validated up front
     */
    default boolean isValidateControllers() {
        return true;
    }

    /**
     * The client-level retry policy (ADR-0008). A default method so
     * existing implementors are not broken; {@code off()} by default, so
     * enabling retry is an opt-in and the failure timing of existing
     * modules does not change. The policy applies to both synchronous and
     * {@code CompletableFuture}-returning controller methods (uniform
     * across sync/async).
     *
     * @return the client-level retry policy
     */
    default RetryPolicy getRetryPolicy() {
        return RetryPolicy.off();
    }

    /**
     * The configured request interceptors (ADR-0018). Default method
     * returning an empty list so existing implementations do not break.
     * Interceptors run sequentially before each request is dispatched.
     *
     * @return the list of request interceptors, never {@code null}
     */
    default List<RequestInterceptor> getRequestInterceptors() {
        return List.of();
    }

    /**
     * The configured response interceptors (ADR-0018). Default method
     * returning an empty list so existing implementations do not break.
     * Interceptors run sequentially after each exchange completes
     * (either successfully or exceptionally).
     *
     * @return the list of response interceptors, never {@code null}
     */
    default List<ResponseInterceptor> getResponseInterceptors() {
        return List.of();
    }

    /**
     * The SSL context used for secure HTTPS connections (ADR-0020). When
     * {@code null} (the default), the underlying HTTP client uses the platform
     * default SSL context ({@link SSLContext#getDefault()}).
     *
     * @return the SSL context, or {@code null} for the platform default
     */
    default SSLContext getSslContext() {
        return null;
    }

    /**
     * The SSL parameters used for secure HTTPS connections (ADR-0020). When
     * {@code null} (the default), the underlying HTTP client uses the default
     * SSL parameters.
     *
     * @return the SSL parameters, or {@code null} for defaults
     */
    default SSLParameters getSslParameters() {
        return null;
    }

    /**
     * Whether transparent response decompression for {@code gzip} and
     * {@code deflate} content encodings is enabled (ADR-0021). Enabled by
     * default ({@code true}). When enabled, requests automatically include
     * {@code Accept-Encoding: gzip, deflate} unless an explicit
     * {@code Accept-Encoding} header is already declared, and responses with
     * matching {@code Content-Encoding} are transparently decompressed.
     *
     * @return {@code true} if decompression is enabled, {@code false} otherwise
     */
    default boolean isDecompressionEnabled() {
        return true;
    }

    /**
     * The executor used for asynchronous tasks in the underlying HTTP client
     * and asynchronous pipeline stages (ADR-0026). When {@code null} (the default),
     * the client uses the platform default executor, unless {@link #isVirtualThreads()}
     * is enabled.
     *
     * @return the custom executor, or {@code null} for defaults
     */
    default Executor getExecutor() {
        return null;
    }

    /**
     * Whether to use virtual threads (via {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()})
     * for asynchronous tasks when no custom {@link #getExecutor()} is provided (ADR-0026).
     * Defaults to {@code false}.
     *
     * @return {@code true} if virtual threads are enabled, {@code false} otherwise
     */
    default boolean isVirtualThreads() {
        return false;
    }

    /**
     * The cookie handler used for automatic cookie and session management (ADR-0032).
     * When {@code null} (the default), no automatic cookie handling is performed by
     * the underlying HTTP client.
     *
     * @return the cookie handler, or {@code null} for no automatic cookie handling
     */
    default CookieHandler getCookieHandler() {
        return null;
    }

    /**
     * The metrics and telemetry listener (ADR-0036).
     * When {@code null} (the default), no metrics listener is invoked.
     *
     * @return the metrics listener, or {@code null} if none configured
     */
    default ru.jrestly.metrics.ClientMetricsListener getMetricsListener() {
        return null;
    }

    /**
     * The proxy selector used for routing HTTP requests (ADR-0037).
     * When {@code null} (the default), the underlying HTTP client uses the platform default
     * proxy selector ({@link ProxySelector#getDefault()}).
     *
     * @return the proxy selector, or {@code null} for defaults
     */
    default ProxySelector getProxySelector() {
        return null;
    }

    /**
     * The transport-level authenticator used for HTTP proxy and server authentication challenges (ADR-0037).
     * When {@code null} (the default), no authenticator is attached.
     *
     * @return the authenticator, or {@code null} if none configured
     */
    default Authenticator getAuthenticator() {
        return null;
    }

    /**
     * The default request body compression algorithm (ADR-0041).
     * When {@code null} (the default), requests are sent uncompressed unless explicitly
     * annotated with {@link ru.jrestly.annotation.Compress}.
     *
     * @return the default compression algorithm, or {@code null} if disabled
     */
    default CompressionAlgorithm getRequestCompressionAlgorithm() {
        return null;
    }

    /**
     * The minimum request body size in bytes before automatic request compression is applied (ADR-0041).
     * Defaults to {@code 0} (compress all non-empty bodies when request compression is enabled).
     *
     * @return the threshold in bytes
     */
    default int getRequestCompressionThreshold() {
        return 0;
    }

    /**
     * Map of registered custom content compressors keyed by encoding token (ADR-0041).
     *
     * @return unmodifiable map of custom compressors
     */
    default Map<String, ContentCompressor> getContentCompressors() {
        return Map.of();
    }

    /**
     * Resolves a content compressor for the given encoding token (ADR-0041).
     *
     * @param encoding content encoding token (e.g. {@code "gzip"}, {@code "deflate"})
     * @return the compressor instance, or {@code null} if none registered
     */
    default ContentCompressor getContentCompressor(String encoding) {
        if (encoding == null) {
            return null;
        }
        return getContentCompressors().get(encoding.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The trace context propagator used for distributed tracing and correlation ID injection (ADR-0042).
     * When {@code null} (the default), requests do not inject tracing headers unless
     * explicitly configured via {@link ru.jrestly.annotation.Trace}.
     *
     * @return the trace context propagator, or {@code null} if not configured
     */
    default TracePropagator getTracePropagator() {
        return null;
    }

    /**
     * The client-level {@link ru.jrestly.resilience.CircuitBreaker} (ADR-0043).
     * When {@code null} (the default), requests do not use circuit breaker protection unless
     * configured via {@link ru.jrestly.annotation.CircuitBreaker}.
     *
     * @return the circuit breaker, or {@code null} if not configured
     */
    default ru.jrestly.resilience.CircuitBreaker getCircuitBreaker() {
        return null;
    }

    /**
     * The registry of {@link ru.jrestly.resilience.CircuitBreaker} instances (ADR-0043).
     *
     * @return the circuit breaker registry, or {@code null} if not configured
     */
    default ru.jrestly.resilience.CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return null;
    }

    /**
     * The client-level {@link ru.jrestly.resilience.RateLimiter} (ADR-0043).
     * When {@code null} (the default), requests do not use client-side rate limiting unless
     * configured via {@link ru.jrestly.annotation.RateLimiter}.
     *
     * @return the rate limiter, or {@code null} if not configured
     */
    default ru.jrestly.resilience.RateLimiter getRateLimiter() {
        return null;
    }

    /**
     * The registry of {@link ru.jrestly.resilience.RateLimiter} instances (ADR-0043).
     *
     * @return the rate limiter registry, or {@code null} if not configured
     */
    default ru.jrestly.resilience.RateLimiterRegistry getRateLimiterRegistry() {
        return null;
    }

    /**
     * The token manager associated with this client module, or {@code null} if none is configured (ADR-0046).
     *
     * @return the token manager, or {@code null}
     */
    default ru.jrestly.auth.TokenManager getTokenManager() {
        return null;
    }

    /**
     * Whether transparent 401 Unauthorized retry with token invalidation is enabled (ADR-0046).
     *
     * @return {@code true} if 401 retry is enabled
     */
    default boolean isRetryOnUnauthorized() {
        return false;
    }
}

