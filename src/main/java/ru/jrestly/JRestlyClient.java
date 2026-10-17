package ru.jrestly;

import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.tracing.TracePropagator;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * Factory for declarative HTTP API proxies.
 */
public class JRestlyClient extends AbstractHttpClient {

    /**
     * Creates a client for the given module configuration: the
     * configuration is stored on the client, and the underlying
     * connection is created right away (see
     * {@link AbstractHttpClient#createConnection()}).
     *
     * @param moduleInfo the module configuration for the client
     */
    public JRestlyClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    /**
     * A {@link Builder} pre-set to the {@link SimpleModuleInfo}
     * defaults; {@code baseUrl} must still be supplied before
     * {@link Builder#build()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    protected void login() {
    }

    /**
     * Returns a cached proxy implementation for the given interface.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> controllerClass) {
        return (T) controllers.computeIfAbsent(controllerClass, this::createController);
    }

    /**
     * A convenience builder for {@link JRestlyClient}: it forwards
     * every setting to {@link SimpleModuleInfo.Builder} and creates
     * the client in one step, so a client can be configured without
     * naming the module explicitly.
     *
     * <p>Every setting keeps the {@link SimpleModuleInfo} semantics;
     * {@code baseUrl} is the only required one (see
     * {@link SimpleModuleInfo.Builder#build()}).
     */
    public static class Builder {
        private final SimpleModuleInfo.Builder moduleInfoBuilder = SimpleModuleInfo.builder();

        /**
         * A builder pre-set to the defaults; {@code baseUrl} must still
         * be supplied before {@link #build()}.
         */
        public Builder() {
        }

        /**
         * The module name (default {@code "default"}).
         *
         * @param name the module name
         * @return this builder, for chaining
         */
        public Builder name(String name) {
            moduleInfoBuilder.name(name);
            return this;
        }

        /**
         * The base URL for relative request URLs. Required:
         * {@link #build()} fails fast without it.
         *
         * @param baseUrl the base URL
         * @return this builder, for chaining
         */
        public Builder baseUrl(String baseUrl) {
            moduleInfoBuilder.baseUrl(baseUrl);
            return this;
        }

        /**
         * The initial auth header pair (default: none). The header is
         * sent only when both name and value are set.
         *
         * @param name the auth header name, or {@code null} for no
         *             initial header
         * @param value the auth header value
         * @return this builder, for chaining
         */
        public Builder authHeader(String name, String value) {
            moduleInfoBuilder.authHeader(name, value);
            return this;
        }

        /**
         * The JSON codec for request and response bodies. When left
         * unset, {@link #build()} resolves one from the classpath —
         * jackson, then gson, then moshi, or falls back to
         * {@link ru.jrestly.json.NoOpJsonCodec} for pure zero-dependency standalone execution (ADR-0045).
         *
         * @param jsonCodec the codec to use
         * @return this builder, for chaining
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            moduleInfoBuilder.jsonCodec(jsonCodec);
            return this;
        }

        /**
         * Registers a media-type-aware {@link PayloadCodec} (ADR-0060) so
         * requests and responses whose {@code Content-Type} matches it are
         * bound with it instead of the JSON codec. Registration order is
         * resolution priority; the JSON codec remains the default fallback.
         *
         * @param codec the codec to register; {@code null} is ignored
         * @return this builder, for chaining
         */
        public Builder codec(PayloadCodec codec) {
            moduleInfoBuilder.codec(codec);
            return this;
        }

        /**
         * Replaces the whole media-type codec registry (ADR-0060). When unset,
         * a registry is derived from {@link #jsonCodec(JsonCodec)} plus any
         * {@link #codec(PayloadCodec)} registrations.
         *
         * @param codecRegistry the registry to use
         * @return this builder, for chaining
         */
        public Builder codecs(CodecRegistry codecRegistry) {
            moduleInfoBuilder.codecRegistry(codecRegistry);
            return this;
        }

        /**
         * The connect timeout in milliseconds (default 5 000). Must not be negative.
         * A value of {@code 0} disables the timeout.
         *
         * @param ms the connect timeout in milliseconds, not negative
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code ms} is negative
         */
        public Builder connectTimeout(int ms) {
            moduleInfoBuilder.connectTimeout(ms);
            return this;
        }

        /**
         * The connect timeout as a {@link Duration} (default 5 000 ms).
         * A zero duration disables the timeout.
         *
         * @param duration the connect timeout, not {@code null} and not negative
         * @return this builder, for chaining
         */
        public Builder connectTimeout(Duration duration) {
            moduleInfoBuilder.connectTimeout(duration);
            return this;
        }

        /**
         * The socket (request) timeout in milliseconds (default 30 000). Must not be negative.
         * A value of {@code 0} disables the timeout.
         *
         * @param ms the socket timeout in milliseconds, not negative
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code ms} is negative
         */
        public Builder socketTimeout(int ms) {
            moduleInfoBuilder.socketTimeout(ms);
            return this;
        }

        /**
         * The socket (request) timeout as a {@link Duration} (default 30 000 ms).
         * A zero duration disables the timeout.
         *
         * @param duration the socket timeout, not {@code null} and not negative
         * @return this builder, for chaining
         */
        public Builder socketTimeout(Duration duration) {
            moduleInfoBuilder.socketTimeout(duration);
            return this;
        }

        /**
         * The HTTP protocol version (default {@code HTTP_2}).
         *
         * @param httpVersion the protocol version, not {@code null}
         * @return this builder, for chaining
         */
        public Builder httpVersion(HttpClient.Version httpVersion) {
            moduleInfoBuilder.httpVersion(httpVersion);
            return this;
        }

        /**
         * Whether controller interfaces are validated up front (default
         * {@code true}: an invalid controller fails when its proxy is
         * created with a {@link ControllerValidationException}; when
         * off, the problems are only logged and the failing methods
         * throw on call).
         *
         * @param validate whether to validate controllers up front
         * @return this builder, for chaining
         */
        public Builder validateControllers(boolean validate) {
            moduleInfoBuilder.validateControllers(validate);
            return this;
        }

        /**
         * The client-level retry policy (default {@code off()}).
         *
         * @param retryPolicy the retry policy, not {@code null}
         * @return this builder, for chaining
         */
        public Builder retry(RetryPolicy retryPolicy) {
            moduleInfoBuilder.retry(retryPolicy);
            return this;
        }

        /**
         * Appends a request interceptor to the execution pipeline (ADR-0018).
         *
         * @param interceptor the request interceptor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addRequestInterceptor(RequestInterceptor interceptor) {
            moduleInfoBuilder.addRequestInterceptor(interceptor);
            return this;
        }

        /**
         * Appends a stateless request transformer to the pipeline (ADR-0018).
         *
         * @param operator the request transformer function, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addRequestInterceptor(UnaryOperator<WireRequest> operator) {
            moduleInfoBuilder.addRequestInterceptor(operator);
            return this;
        }

        /**
         * Replaces the request interceptors with the given list (ADR-0018).
         *
         * @param interceptors the request interceptors, not {@code null} and containing no {@code null} elements
         * @return this builder, for chaining
         */
        public Builder requestInterceptors(List<RequestInterceptor> interceptors) {
            moduleInfoBuilder.requestInterceptors(interceptors);
            return this;
        }

        /**
         * Appends a response interceptor to the execution pipeline (ADR-0018).
         *
         * @param interceptor the response interceptor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addResponseInterceptor(ResponseInterceptor interceptor) {
            moduleInfoBuilder.addResponseInterceptor(interceptor);
            return this;
        }

        /**
         * Replaces the response interceptors with the given list (ADR-0018).
         *
         * @param interceptors the response interceptors, not {@code null} and containing no {@code null} elements
         * @return this builder, for chaining
         */
        public Builder responseInterceptors(List<ResponseInterceptor> interceptors) {
            moduleInfoBuilder.responseInterceptors(interceptors);
            return this;
        }

        /**
         * Sets the custom SSL context for secure HTTPS connections (ADR-0020).
         *
         * @param sslContext the SSL context, not {@code null}
         * @return this builder, for chaining
         */
        public Builder sslContext(SSLContext sslContext) {
            moduleInfoBuilder.sslContext(sslContext);
            return this;
        }

        /**
         * Sets the custom SSL parameters for secure HTTPS connections (ADR-0020).
         *
         * @param sslParameters the SSL parameters, not {@code null}
         * @return this builder, for chaining
         */
        public Builder sslParameters(SSLParameters sslParameters) {
            moduleInfoBuilder.sslParameters(sslParameters);
            return this;
        }

        /**
         * Sets whether transparent response decompression is enabled (ADR-0021).
         * Enabled by default ({@code true}).
         *
         * @param enabled {@code true} to enable decompression, {@code false} to disable
         * @return this builder, for chaining
         */
        public Builder decompression(boolean enabled) {
            moduleInfoBuilder.decompression(enabled);
            return this;
        }

        /**
         * Sets the custom executor for asynchronous tasks and completions (ADR-0026).
         *
         * @param executor the custom executor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder executor(Executor executor) {
            moduleInfoBuilder.executor(executor);
            return this;
        }

        /**
         * Sets whether to use virtual threads for asynchronous tasks (ADR-0026).
         *
         * @param enabled {@code true} to enable virtual threads, {@code false} to disable
         * @return this builder, for chaining
         */
        public Builder virtualThreads(boolean enabled) {
            moduleInfoBuilder.virtualThreads(enabled);
            return this;
        }

        /**
         * Enables virtual threads for asynchronous tasks (ADR-0026).
         * Shortcut for {@code virtualThreads(true)}.
         *
         * @return this builder, for chaining
         */
        public Builder useVirtualThreads() {
            moduleInfoBuilder.useVirtualThreads();
            return this;
        }

        /**
         * Sets the custom cookie handler for automatic cookie and session management (ADR-0032).
         *
         * @param cookieHandler the cookie handler, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookieHandler(CookieHandler cookieHandler) {
            moduleInfoBuilder.cookieHandler(cookieHandler);
            return this;
        }

        /**
         * Enables automatic cookie and session management using a default {@link CookieManager} (ADR-0032).
         *
         * @return this builder, for chaining
         */
        public Builder cookieManager() {
            moduleInfoBuilder.cookieManager();
            return this;
        }

        /**
         * Enables automatic cookie and session management using a {@link CookieManager} with the given policy (ADR-0032).
         *
         * @param cookiePolicy the cookie policy, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookiePolicy(CookiePolicy cookiePolicy) {
            moduleInfoBuilder.cookiePolicy(cookiePolicy);
            return this;
        }

        /**
         * Enables automatic cookie and session management using a {@link CookieManager} with the given store (ADR-0039).
         * Defaults to {@link CookiePolicy#ACCEPT_ORIGINAL_SERVER}.
         *
         * @param cookieStore the cookie store, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookieStore(CookieStore cookieStore) {
            moduleInfoBuilder.cookieStore(cookieStore);
            return this;
        }

        /**
         * Enables automatic cookie and session management using a {@link CookieManager} with the given store and policy (ADR-0039).
         *
         * @param cookieStore the cookie store, not {@code null}
         * @param cookiePolicy the cookie policy, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookieStore(CookieStore cookieStore, CookiePolicy cookiePolicy) {
            moduleInfoBuilder.cookieStore(cookieStore, cookiePolicy);
            return this;
        }

        /**
         * Enables automatic cookie and session persistence to a local file using {@link ru.jrestly.cookie.PersistentCookieStore} (ADR-0039).
         *
         * @param path target cookie file path, not {@code null}
         * @return this builder, for chaining
         */
        public Builder persistentCookieStore(Path path) {
            moduleInfoBuilder.persistentCookieStore(path);
            return this;
        }

        /**
         * Sets the metrics and telemetry listener (ADR-0036).
         * If a metrics listener was already configured on this builder, the listeners are
         * combined using {@link ru.jrestly.metrics.ClientMetricsListener#composite(ru.jrestly.metrics.ClientMetricsListener...)}.
         *
         * @param metricsListener the metrics listener, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code metricsListener} is {@code null}
         */
        public Builder metricsListener(ru.jrestly.metrics.ClientMetricsListener metricsListener) {
            moduleInfoBuilder.metricsListener(metricsListener);
            return this;
        }

        /**
         * Sets the proxy selector used for routing HTTP requests (ADR-0037).
         *
         * @param proxySelector the proxy selector, not {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code proxySelector} is {@code null}
         */
        public Builder proxy(ProxySelector proxySelector) {
            moduleInfoBuilder.proxy(proxySelector);
            return this;
        }

        /**
         * Configures an HTTP proxy address for routing requests (ADR-0037).
         *
         * @param host the proxy host, not {@code null} and not blank
         * @param port the proxy port (1..65535)
         * @return this builder, for chaining
         * @throws NullPointerException if {@code host} is {@code null}
         * @throws IllegalArgumentException if {@code host} is blank or {@code port} is out of bounds (1..65535)
         */
        public Builder proxy(String host, int port) {
            moduleInfoBuilder.proxy(host, port);
            return this;
        }

        /**
         * Configures direct connections bypassing any system or ambient proxy (ADR-0037).
         * Equivalent to setting {@link HttpClient.Builder#NO_PROXY}.
         *
         * @return this builder, for chaining
         */
        public Builder noProxy() {
            moduleInfoBuilder.noProxy();
            return this;
        }

        /**
         * Sets the transport-level authenticator for proxy and server challenges (ADR-0037).
         *
         * @param authenticator the authenticator, not {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code authenticator} is {@code null}
         */
        public Builder authenticator(Authenticator authenticator) {
            moduleInfoBuilder.authenticator(authenticator);
            return this;
        }

        /**
         * Configures proxy authentication credentials for HTTP proxy challenges (ADR-0037).
         *
         * @param username the proxy username, not {@code null}
         * @param password the proxy password, not {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code username} or {@code password} is {@code null}
         */
        public Builder proxyCredentials(String username, String password) {
            moduleInfoBuilder.proxyCredentials(username, password);
            return this;
        }

        /**
         * Configures universal authentication credentials for both server and proxy challenges (ADR-0037).
         *
         * @param username the username, not {@code null}
         * @param password the password, not {@code null}
         * @return this builder, for chaining
         */
        public Builder credentials(String username, String password) {
            moduleInfoBuilder.credentials(username, password);
            return this;
        }

        /**
         * Sets the default request body compression algorithm (ADR-0041).
         *
         * @param algorithm the compression algorithm, or {@code null} to disable
         * @return this builder, for chaining
         */
        public Builder requestCompression(CompressionAlgorithm algorithm) {
            moduleInfoBuilder.requestCompression(algorithm);
            return this;
        }

        /**
         * Sets the default request body compression algorithm and minimum payload size threshold in bytes (ADR-0041).
         *
         * @param algorithm the compression algorithm, or {@code null} to disable
         * @param thresholdBytes minimum payload size in bytes to trigger compression (must be non-negative)
         * @return this builder, for chaining
         * @throws IllegalArgumentException if thresholdBytes is negative
         */
        public Builder requestCompression(CompressionAlgorithm algorithm, int thresholdBytes) {
            moduleInfoBuilder.requestCompression(algorithm, thresholdBytes);
            return this;
        }

        /**
         * Registers a custom {@link ContentCompressor} for content encoding and decoding (ADR-0041).
         *
         * @param compressor the compressor instance, not {@code null}
         * @return this builder, for chaining
         */
        public Builder contentCompressor(ContentCompressor compressor) {
            moduleInfoBuilder.contentCompressor(compressor);
            return this;
        }

        /**
         * Sets the trace context propagator used for distributed tracing and correlation ID propagation (ADR-0042).
         *
         * @param propagator the propagator, not {@code null}
         * @return this builder, for chaining
         */
        public Builder tracePropagator(TracePropagator propagator) {
            moduleInfoBuilder.tracePropagator(propagator);
            return this;
        }

        /**
         * Configures automatic correlation ID injection using UUID v4 into the specified header name (ADR-0042).
         *
         * @param headerName the correlation header name (e.g. {@code "X-Request-ID"}), not blank
         * @return this builder, for chaining
         */
        public Builder correlationId(String headerName) {
            moduleInfoBuilder.correlationId(headerName);
            return this;
        }

        /**
         * Configures automatic correlation ID injection into the specified header name using a custom generator (ADR-0042).
         *
         * @param headerName the correlation header name, not blank
         * @param generator the ID generator supplier, not null
         * @return this builder, for chaining
         */
        public Builder correlationId(String headerName, java.util.function.Supplier<String> generator) {
            moduleInfoBuilder.correlationId(headerName, generator);
            return this;
        }

        /**
         * Configures automatic correlation ID propagation from an active {@link ThreadLocal} into {@code "X-Request-ID"} (ADR-0042).
         *
         * @param threadLocal the thread local holding the correlation ID, not null
         * @return this builder, for chaining
         */
        public Builder correlationId(ThreadLocal<String> threadLocal) {
            moduleInfoBuilder.correlationId(threadLocal);
            return this;
        }

        /**
         * Configures automatic correlation ID propagation from an active {@link ThreadLocal} into the given header name (ADR-0042).
         *
         * @param headerName the header name, not blank
         * @param threadLocal the thread local holding the correlation ID, not null
         * @param generateIfMissing whether to generate a UUID v4 if thread local is null or blank
         * @return this builder, for chaining
         */
        public Builder correlationId(String headerName, ThreadLocal<String> threadLocal, boolean generateIfMissing) {
            moduleInfoBuilder.correlationId(headerName, threadLocal, generateIfMissing);
            return this;
        }

        /**
         * Enables standard W3C {@code traceparent} propagation (RFC 8414 / W3C TraceContext Level 1) (ADR-0042).
         *
         * @return this builder, for chaining
         */
        public Builder w3cTracing() {
            moduleInfoBuilder.w3cTracing();
            return this;
        }

        /**
         * Enables or disables standard W3C {@code traceparent} propagation (ADR-0042).
         *
         * @param enabled {@code true} to enable, {@code false} to disable
         * @return this builder, for chaining
         */
        public Builder w3cTracing(boolean enabled) {
            moduleInfoBuilder.w3cTracing(enabled);
            return this;
        }

        /**
         * Configures the client-level circuit breaker (ADR-0043).
         *
         * @param circuitBreaker the circuit breaker, or {@code null}
         * @return this builder, for chaining
         */
        public Builder circuitBreaker(ru.jrestly.resilience.CircuitBreaker circuitBreaker) {
            moduleInfoBuilder.circuitBreaker(circuitBreaker);
            return this;
        }

        /**
         * Configures the circuit breaker registry (ADR-0043).
         *
         * @param registry the registry, or {@code null}
         * @return this builder, for chaining
         */
        public Builder circuitBreakerRegistry(ru.jrestly.resilience.CircuitBreakerRegistry registry) {
            moduleInfoBuilder.circuitBreakerRegistry(registry);
            return this;
        }

        /**
         * Configures the client-level rate limiter (ADR-0043).
         *
         * @param rateLimiter the rate limiter, or {@code null}
         * @return this builder, for chaining
         */
        public Builder rateLimiter(ru.jrestly.resilience.RateLimiter rateLimiter) {
            moduleInfoBuilder.rateLimiter(rateLimiter);
            return this;
        }

        /**
         * Configures the rate limiter registry (ADR-0043).
         *
         * @param registry the registry, or {@code null}
         * @return this builder, for chaining
         */
        public Builder rateLimiterRegistry(ru.jrestly.resilience.RateLimiterRegistry registry) {
            moduleInfoBuilder.rateLimiterRegistry(registry);
            return this;
        }

        /**
         * Sets the token manager for token caching and transparent refresh (ADR-0046).
         *
         * @param tokenManager the token manager, or {@code null}
         * @return this builder, for chaining
         */
        public Builder tokenManager(ru.jrestly.auth.TokenManager tokenManager) {
            moduleInfoBuilder.tokenManager(tokenManager);
            return this;
        }

        /**
         * Enables or disables transparent 401 Unauthorized retry with token cache invalidation (ADR-0046).
         *
         * @param retryOnUnauthorized {@code true} to enable 401 retry
         * @return this builder, for chaining
         */
        public Builder retryOnUnauthorized(boolean retryOnUnauthorized) {
            moduleInfoBuilder.retryOnUnauthorized(retryOnUnauthorized);
            return this;
        }

        /**
         * Configures Bearer authentication using a fixed static token (ADR-0046).
         *
         * @param token the static token string, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code token} is {@code null}
         */
        public Builder bearerAuth(String token) {
            moduleInfoBuilder.bearerAuth(token);
            return this;
        }

        /**
         * Configures Bearer authentication using a dynamic token supplier (ADR-0046).
         *
         * @param tokenSupplier the token supplier, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code tokenSupplier} is {@code null}
         */
        public Builder bearerAuth(java.util.function.Supplier<String> tokenSupplier) {
            moduleInfoBuilder.bearerAuth(tokenSupplier);
            return this;
        }

        /**
         * Configures Bearer authentication using a {@link ru.jrestly.auth.TokenManager} (ADR-0046).
         * Also automatically sets the token manager and enables transparent 401 retry.
         *
         * @param tokenManager the token manager, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code tokenManager} is {@code null}
         */
        public Builder bearerAuth(ru.jrestly.auth.TokenManager tokenManager) {
            moduleInfoBuilder.bearerAuth(tokenManager);
            return this;
        }

        /**
         * Configures RFC 7617 Basic authentication with fixed credentials (ADR-0046).
         *
         * @param username the username, must not be {@code null}
         * @param password the password, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code username} or {@code password} is {@code null}
         */
        public Builder basicAuth(String username, String password) {
            moduleInfoBuilder.basicAuth(username, password);
            return this;
        }

        /**
         * Configures RFC 7617 Basic authentication with dynamic credential suppliers (ADR-0046).
         *
         * @param usernameSupplier the username supplier, must not be {@code null}
         * @param passwordSupplier the password supplier, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code usernameSupplier} or {@code passwordSupplier} is {@code null}
         */
        public Builder basicAuth(java.util.function.Supplier<String> usernameSupplier, java.util.function.Supplier<String> passwordSupplier) {
            moduleInfoBuilder.basicAuth(usernameSupplier, passwordSupplier);
            return this;
        }

        /**
         * Configures API Key authentication passed via an HTTP header (ADR-0046).
         *
         * @param headerName the HTTP header name, must not be {@code null}
         * @param apiKey the API key value, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code headerName} or {@code apiKey} is {@code null}
         */
        public Builder apiKeyHeader(String headerName, String apiKey) {
            moduleInfoBuilder.apiKeyHeader(headerName, apiKey);
            return this;
        }

        /**
         * Configures API Key authentication passed via an HTTP header using a dynamic supplier (ADR-0046).
         *
         * @param headerName the HTTP header name, must not be {@code null}
         * @param apiKeySupplier the API key supplier, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code headerName} or {@code apiKeySupplier} is {@code null}
         */
        public Builder apiKeyHeader(String headerName, java.util.function.Supplier<String> apiKeySupplier) {
            moduleInfoBuilder.apiKeyHeader(headerName, apiKeySupplier);
            return this;
        }

        /**
         * Configures API Key authentication passed via a URL query parameter (ADR-0046).
         *
         * @param paramName the query parameter name, must not be {@code null}
         * @param apiKey the API key value, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code paramName} or {@code apiKey} is {@code null}
         */
        public Builder apiKeyQuery(String paramName, String apiKey) {
            moduleInfoBuilder.apiKeyQuery(paramName, apiKey);
            return this;
        }

        /**
         * Configures API Key authentication passed via a URL query parameter using a dynamic supplier (ADR-0046).
         *
         * @param paramName the query parameter name, must not be {@code null}
         * @param apiKeySupplier the API key supplier, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code paramName} or {@code apiKeySupplier} is {@code null}
         */
        public Builder apiKeyQuery(String paramName, java.util.function.Supplier<String> apiKeySupplier) {
            moduleInfoBuilder.apiKeyQuery(paramName, apiKeySupplier);
            return this;
        }

        /**
         * Appends a short-circuit interceptor capable of returning synthetic responses (ADR-0047).
         *
         * @param interceptor the short-circuit interceptor, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code interceptor} is {@code null}
         */
        public Builder shortCircuit(ru.jrestly.interceptor.ShortCircuitInterceptor interceptor) {
            moduleInfoBuilder.shortCircuit(interceptor);
            return this;
        }

        /**
         * Configures an unconditional synthetic mock response for all requests (ADR-0047).
         *
         * @param response the synthetic wire response to return, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code response} is {@code null}
         */
        public Builder mock(ru.jrestly.http.WireResponse response) {
            moduleInfoBuilder.mock(response);
            return this;
        }

        /**
         * Configures a conditional synthetic mock response matching the specified predicate (ADR-0047).
         *
         * @param predicate the predicate matching requests to mock, must not be {@code null}
         * @param response the synthetic response to return, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code predicate} or {@code response} is {@code null}
         */
        public Builder mock(java.util.function.Predicate<ru.jrestly.http.WireRequest> predicate, ru.jrestly.http.WireResponse response) {
            java.util.Objects.requireNonNull(response, "response");
            moduleInfoBuilder.mock(predicate, response);
            return this;
        }

        /**
         * Configures a conditional synthetic mock response produced dynamically by a factory function (ADR-0047).
         *
         * @param predicate the predicate matching requests to mock, must not be {@code null}
         * @param responseSupplier the factory producing synthetic responses for matching requests, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code predicate} or {@code responseSupplier} is {@code null}
         */
        public Builder mock(
                java.util.function.Predicate<ru.jrestly.http.WireRequest> predicate,
                java.util.function.Function<ru.jrestly.http.WireRequest, ru.jrestly.http.WireResponse> responseSupplier
        ) {
            moduleInfoBuilder.mock(predicate, responseSupplier);
            return this;
        }

        /**
         * Sets the custom logger for request and response logging (ADR-0049).
         *
         * @param logger the custom logger, or {@code null}
         * @return this builder, for chaining
         */
        public Builder logger(System.Logger logger) {
            moduleInfoBuilder.logger(logger);
            return this;
        }

        /**
         * Sets the custom pluggable {@link ru.jrestly.engine.HttpEngine} (ADR-0052).
         *
         * @param httpEngine the HTTP engine, or {@code null} to use default engine resolution
         * @return this builder, for chaining
         */
        public Builder httpEngine(ru.jrestly.engine.HttpEngine httpEngine) {
            moduleInfoBuilder.httpEngine(httpEngine);
            return this;
        }

        /**
         * Validates the settings (baseUrl must be set) and creates the
         * client for the resulting {@link SimpleModuleInfo}; an unset
         * {@code jsonCodec} is resolved from the classpath.
         *
         * @return the client
         * @throws IllegalStateException when {@code baseUrl} is not
         *                               set or no JSON codec is found
         *                               on the classpath
         */
        public JRestlyClient build() {
            return new JRestlyClient(moduleInfoBuilder.build());
        }
    }
}
