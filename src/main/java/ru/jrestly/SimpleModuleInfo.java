package ru.jrestly;

import ru.jrestly.http.AuthHeaderProvider;
import ru.jrestly.http.Header;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;

import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.tracing.TraceContext;
import ru.jrestly.tracing.TraceIdGenerator;
import ru.jrestly.tracing.TracePropagator;
import ru.jrestly.tracing.TracePropagators;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * The {@link ModuleInfo} created by {@link #builder()}: every setting is
 * fixed at {@code build()} time, so the result is immutable and
 * thread-safe.
 *
 * <p>Every field has a default — module name {@code "default"}, 5 s
 * connect timeout, 30 s socket timeout, {@code HTTP_2}, controller
 * validation on, retry {@code off()} — and wherever the
 * {@link ModuleInfo} interface provides a default method, the builder's
 * default matches it, so an unset field keeps the interface behavior.
 *
 * <p>{@link #getEnvironment()} returns a fresh execution environment
 * rooted at the {@code baseUrl} on every call: relative request URLs
 * resolve against {@link Environment#getUrl()}, and the environment holds
 * the client's typed auth details (see {@link Environment}).
 */
public class SimpleModuleInfo implements ModuleInfo {

    private final String name;
    private final String baseUrl;
    private final AuthProvider authProvider;
    private final JsonCodec jsonCodec;
    private final int connectTimeout;
    private final int socketTimeout;
    private final HttpClient.Version httpVersion;
    private final boolean validateControllers;
    private final RetryPolicy retryPolicy;
    private final List<RequestInterceptor> requestInterceptors;
    private final List<ResponseInterceptor> responseInterceptors;
    private final SSLContext sslContext;
    private final SSLParameters sslParameters;
    private final boolean decompressionEnabled;
    private final Executor executor;
    private final boolean virtualThreads;
    private final CookieHandler cookieHandler;
    private final ru.jrestly.metrics.ClientMetricsListener metricsListener;
    private final ProxySelector proxySelector;
    private final Authenticator authenticator;
    private final CompressionAlgorithm requestCompressionAlgorithm;
    private final int requestCompressionThreshold;
    private final Map<String, ContentCompressor> contentCompressors;
    private final TracePropagator tracePropagator;
    private final ru.jrestly.resilience.CircuitBreaker circuitBreaker;
    private final ru.jrestly.resilience.CircuitBreakerRegistry circuitBreakerRegistry;
    private final ru.jrestly.resilience.RateLimiter rateLimiter;
    private final ru.jrestly.resilience.RateLimiterRegistry rateLimiterRegistry;
    private final ru.jrestly.auth.TokenManager tokenManager;
    private final boolean retryOnUnauthorized;
    private final System.Logger logger;

    private SimpleModuleInfo(Builder builder) {
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.authProvider = builder.authHeaderName != null
                ? new AuthHeaderProvider(builder.authHeaderName, builder.authHeaderValue)
                : new AuthHeaderProvider();
        this.logger = builder.logger;
        this.jsonCodec = builder.jsonCodec;
        this.connectTimeout = builder.connectTimeout;
        this.socketTimeout = builder.socketTimeout;
        this.httpVersion = builder.httpVersion;
        this.validateControllers = builder.validateControllers;
        this.retryPolicy = builder.retryPolicy;
        this.requestInterceptors = List.copyOf(builder.requestInterceptors);
        this.responseInterceptors = List.copyOf(builder.responseInterceptors);
        this.sslContext = builder.sslContext;
        this.sslParameters = builder.sslParameters;
        this.decompressionEnabled = builder.decompressionEnabled;
        this.executor = builder.executor;
        this.virtualThreads = builder.virtualThreads;
        this.cookieHandler = builder.cookieHandler;
        this.metricsListener = builder.metricsListener;
        this.proxySelector = builder.proxySelector;
        this.authenticator = builder.authenticator;
        this.requestCompressionAlgorithm = builder.requestCompressionAlgorithm;
        this.requestCompressionThreshold = builder.requestCompressionThreshold;
        this.contentCompressors = Map.copyOf(builder.contentCompressors);
        this.tracePropagator = builder.tracePropagator;
        this.circuitBreaker = builder.circuitBreaker;
        this.circuitBreakerRegistry = builder.circuitBreakerRegistry != null
                ? builder.circuitBreakerRegistry
                : ru.jrestly.resilience.CircuitBreakerRegistry.ofDefaults();
        this.rateLimiter = builder.rateLimiter;
        this.rateLimiterRegistry = builder.rateLimiterRegistry != null
                ? builder.rateLimiterRegistry
                : ru.jrestly.resilience.RateLimiterRegistry.ofDefaults();
        this.tokenManager = builder.tokenManager;
        this.retryOnUnauthorized = builder.retryOnUnauthorized;
    }

    /**
     * A builder pre-set to the defaults (see the class documentation).
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getName() {
        return name;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Environment<T> getEnvironment() {
        return (Environment<T>) new SimpleEnvironment(baseUrl);
    }

    @Override
    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    @Override
    public JsonCodec getJsonCodec() {
        return jsonCodec;
    }

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public int getSocketTimeout() {
        return socketTimeout;
    }

    @Override
    public HttpClient.Version getHttpVersion() {
        return httpVersion;
    }

    @Override
    public boolean isValidateControllers() {
        return validateControllers;
    }

    @Override
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    @Override
    public List<RequestInterceptor> getRequestInterceptors() {
        return requestInterceptors;
    }

    @Override
    public List<ResponseInterceptor> getResponseInterceptors() {
        return responseInterceptors;
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public SSLParameters getSslParameters() {
        return sslParameters;
    }

    @Override
    public boolean isDecompressionEnabled() {
        return decompressionEnabled;
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public boolean isVirtualThreads() {
        return virtualThreads;
    }

    @Override
    public CookieHandler getCookieHandler() {
        return cookieHandler;
    }

    @Override
    public ru.jrestly.metrics.ClientMetricsListener getMetricsListener() {
        return metricsListener;
    }

    @Override
    public ProxySelector getProxySelector() {
        return proxySelector;
    }

    @Override
    public Authenticator getAuthenticator() {
        return authenticator;
    }

    @Override
    public CompressionAlgorithm getRequestCompressionAlgorithm() {
        return requestCompressionAlgorithm;
    }

    @Override
    public int getRequestCompressionThreshold() {
        return requestCompressionThreshold;
    }

    @Override
    public Map<String, ContentCompressor> getContentCompressors() {
        return contentCompressors;
    }

    @Override
    public ContentCompressor getContentCompressor(String encoding) {
        if (encoding == null) {
            return null;
        }
        return contentCompressors.get(encoding.trim().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public TracePropagator getTracePropagator() {
        return tracePropagator;
    }

    @Override
    public ru.jrestly.resilience.CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public ru.jrestly.resilience.CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    @Override
    public ru.jrestly.resilience.RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    @Override
    public ru.jrestly.resilience.RateLimiterRegistry getRateLimiterRegistry() {
        return rateLimiterRegistry;
    }

    @Override
    public ru.jrestly.auth.TokenManager getTokenManager() {
        return tokenManager;
    }

    @Override
    public boolean isRetryOnUnauthorized() {
        return retryOnUnauthorized;
    }

    @Override
    public System.Logger getLogger() {
        return logger;
    }

    /**
     * A builder for {@link SimpleModuleInfo}, pre-set to the defaults:
     * name {@code "default"}, 5 s connect timeout, 30 s socket timeout,
     * {@code HTTP_2}, controller validation on, retry {@code off()}.
     *
     * <p>{@code baseUrl} is the only required setting; {@link #build()}
     * validates the combination and returns the immutable module.
     */
    public static class Builder {
        private String name = "default";
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private JsonCodec jsonCodec;
        private int connectTimeout = 5_000;
        private int socketTimeout = 30_000;
        private HttpClient.Version httpVersion = HttpClient.Version.HTTP_2;
        private boolean validateControllers = true;
        private RetryPolicy retryPolicy = RetryPolicy.off();
        private final List<RequestInterceptor> requestInterceptors = new ArrayList<>();
        private final List<ResponseInterceptor> responseInterceptors = new ArrayList<>();
        private SSLContext sslContext;
        private SSLParameters sslParameters;
        private boolean decompressionEnabled = true;
        private Executor executor;
        private boolean virtualThreads = false;
        private CookieHandler cookieHandler;
        private ru.jrestly.metrics.ClientMetricsListener metricsListener;
        private ProxySelector proxySelector;
        private Authenticator authenticator;
        private CompressionAlgorithm requestCompressionAlgorithm;
        private int requestCompressionThreshold = 0;
        private final Map<String, ContentCompressor> contentCompressors = new java.util.LinkedHashMap<>();
        private TracePropagator tracePropagator;
        private ru.jrestly.resilience.CircuitBreaker circuitBreaker;
        private ru.jrestly.resilience.CircuitBreakerRegistry circuitBreakerRegistry;
        private ru.jrestly.resilience.RateLimiter rateLimiter;
        private ru.jrestly.resilience.RateLimiterRegistry rateLimiterRegistry;
        private ru.jrestly.auth.TokenManager tokenManager;
        private boolean retryOnUnauthorized = false;
        private System.Logger logger;

        /**
         * Sets the custom logger for request and response logging (ADR-0049).
         *
         * @param logger the custom logger, or {@code null}
         * @return this builder, for chaining
         */
        public Builder logger(System.Logger logger) {
            this.logger = logger;
            return this;
        }

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
            this.name = name;
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
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * The initial auth header pair (default: none). The header is
         * sent only when both name and value are set; a client started
         * without it is not authorized until login captures a credential.
         *
         * @param name the auth header name, or {@code null} for no
         *             initial header
         * @param value the auth header value
         * @return this builder, for chaining
         */
        public Builder authHeader(String name, String value) {
            this.authHeaderName = name;
            this.authHeaderValue = value;
            return this;
        }

        /**
         * The JSON codec for request and response bodies. When left
         * unset, {@link #build()} resolves one from the classpath —
         * jackson, then gson, then moshi, or falls back to
         * {@link ru.jrestly.json.NoOpJsonCodec} for zero-dependency standalone execution (ADR-0045).
         *
         * @param jsonCodec the codec to use
         * @return this builder, for chaining
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        /**
         * The connect timeout in milliseconds (default 5 000).
         *
         * @param ms the connect timeout in milliseconds
         * @return this builder, for chaining
         */
        public Builder connectTimeout(int ms) {
            this.connectTimeout = ms;
            return this;
        }

        /**
         * The connect timeout as a {@link Duration} (default 5 000 ms).
         *
         * @param duration the connect timeout, not {@code null} and not negative
         * @return this builder, for chaining
         */
        public Builder connectTimeout(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("connectTimeout duration must not be negative");
            }
            this.connectTimeout = Math.toIntExact(duration.toMillis());
            return this;
        }

        /**
         * The socket (request) timeout in milliseconds (default 30 000).
         *
         * @param ms the socket timeout in milliseconds
         * @return this builder, for chaining
         */
        public Builder socketTimeout(int ms) {
            this.socketTimeout = ms;
            return this;
        }

        /**
         * The socket (request) timeout as a {@link Duration} (default 30 000 ms).
         *
         * @param duration the socket timeout, not {@code null} and not negative
         * @return this builder, for chaining
         */
        public Builder socketTimeout(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("socketTimeout duration must not be negative");
            }
            this.socketTimeout = Math.toIntExact(duration.toMillis());
            return this;
        }

        /**
         * The HTTP protocol version (default {@code HTTP_2}).
         *
         * @param httpVersion the protocol version, not {@code null}
         * @return this builder, for chaining
         */
        public Builder httpVersion(HttpClient.Version httpVersion) {
            this.httpVersion = java.util.Objects.requireNonNull(httpVersion, "httpVersion");
            return this;
        }

        /**
         * Whether controller interfaces are validated up front (default
         * {@code true}: an invalid controller fails when its proxy is
         * created with a {@link ControllerValidationException}; when off,
         * the problems are only logged and the failing methods throw on
         * call).
         *
         * @param validate whether to validate controllers up front
         * @return this builder, for chaining
         */
        public Builder validateControllers(boolean validate) {
            this.validateControllers = validate;
            return this;
        }

        /**
         * The client-level retry policy (default {@code off()}).
         *
         * @param retryPolicy the retry policy, not {@code null}
         * @return this builder, for chaining
         */
        public Builder retry(RetryPolicy retryPolicy) {
            this.retryPolicy = java.util.Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /**
         * Appends a request interceptor to the execution pipeline (ADR-0018).
         *
         * @param interceptor the request interceptor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addRequestInterceptor(RequestInterceptor interceptor) {
            this.requestInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Appends a stateless request transformer to the pipeline (ADR-0018).
         *
         * @param operator the request transformer function, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addRequestInterceptor(UnaryOperator<WireRequest> operator) {
            Objects.requireNonNull(operator, "operator");
            this.requestInterceptors.add(RequestInterceptor.of(operator));
            return this;
        }

        /**
         * Replaces the request interceptors with the given list (ADR-0018).
         *
         * @param interceptors the request interceptors, not {@code null} and containing no {@code null} elements
         * @return this builder, for chaining
         */
        public Builder requestInterceptors(List<RequestInterceptor> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            this.requestInterceptors.clear();
            for (RequestInterceptor interceptor : interceptors) {
                this.requestInterceptors.add(Objects.requireNonNull(interceptor, "interceptor in list cannot be null"));
            }
            return this;
        }

        /**
         * Appends a response interceptor to the execution pipeline (ADR-0018).
         *
         * @param interceptor the response interceptor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder addResponseInterceptor(ResponseInterceptor interceptor) {
            this.responseInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Replaces the response interceptors with the given list (ADR-0018).
         *
         * @param interceptors the response interceptors, not {@code null} and containing no {@code null} elements
         * @return this builder, for chaining
         */
        public Builder responseInterceptors(List<ResponseInterceptor> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            this.responseInterceptors.clear();
            for (ResponseInterceptor interceptor : interceptors) {
                this.responseInterceptors.add(Objects.requireNonNull(interceptor, "interceptor in list cannot be null"));
            }
            return this;
        }

        /**
         * Sets the custom SSL context for secure HTTPS connections (ADR-0020).
         *
         * @param sslContext the SSL context, not {@code null}
         * @return this builder, for chaining
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
            return this;
        }

        /**
         * Sets the custom SSL parameters for secure HTTPS connections (ADR-0020).
         *
         * @param sslParameters the SSL parameters, not {@code null}
         * @return this builder, for chaining
         */
        public Builder sslParameters(SSLParameters sslParameters) {
            this.sslParameters = Objects.requireNonNull(sslParameters, "sslParameters");
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
            this.decompressionEnabled = enabled;
            return this;
        }

        /**
         * Sets the custom executor for asynchronous tasks and completions (ADR-0026).
         *
         * @param executor the custom executor, not {@code null}
         * @return this builder, for chaining
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Sets whether to use virtual threads for asynchronous tasks (ADR-0026).
         *
         * @param enabled {@code true} to enable virtual threads, {@code false} to disable
         * @return this builder, for chaining
         */
        public Builder virtualThreads(boolean enabled) {
            this.virtualThreads = enabled;
            return this;
        }

        /**
         * Enables virtual threads for asynchronous tasks (ADR-0026).
         * Shortcut for {@code virtualThreads(true)}.
         *
         * @return this builder, for chaining
         */
        public Builder useVirtualThreads() {
            return virtualThreads(true);
        }

        /**
         * Sets the custom cookie handler for automatic cookie and session management (ADR-0032).
         *
         * @param cookieHandler the cookie handler, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookieHandler(CookieHandler cookieHandler) {
            this.cookieHandler = Objects.requireNonNull(cookieHandler, "cookieHandler");
            return this;
        }

        /**
         * Enables automatic cookie and session management using a default {@link CookieManager} (ADR-0032).
         *
         * @return this builder, for chaining
         */
        public Builder cookieManager() {
            this.cookieHandler = new CookieManager();
            return this;
        }

        /**
         * Enables automatic cookie and session management using a {@link CookieManager} with the given policy (ADR-0032).
         *
         * @param cookiePolicy the cookie policy, not {@code null}
         * @return this builder, for chaining
         */
        public Builder cookiePolicy(CookiePolicy cookiePolicy) {
            Objects.requireNonNull(cookiePolicy, "cookiePolicy");
            this.cookieHandler = new CookieManager(null, cookiePolicy);
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
            Objects.requireNonNull(cookieStore, "cookieStore");
            this.cookieHandler = new CookieManager(cookieStore, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
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
            Objects.requireNonNull(cookieStore, "cookieStore");
            Objects.requireNonNull(cookiePolicy, "cookiePolicy");
            this.cookieHandler = new CookieManager(cookieStore, cookiePolicy);
            return this;
        }

        /**
         * Enables automatic cookie and session persistence to a local file using {@link ru.jrestly.cookie.PersistentCookieStore} (ADR-0039).
         *
         * @param path target cookie file path, not {@code null}
         * @return this builder, for chaining
         */
        public Builder persistentCookieStore(Path path) {
            Objects.requireNonNull(path, "path");
            return cookieStore(ru.jrestly.cookie.PersistentCookieStore.ofFile(path));
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
            Objects.requireNonNull(metricsListener, "metricsListener");
            if (this.metricsListener == null) {
                this.metricsListener = metricsListener;
            } else {
                this.metricsListener = ru.jrestly.metrics.ClientMetricsListener.composite(this.metricsListener, metricsListener);
            }
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
            this.proxySelector = Objects.requireNonNull(proxySelector, "proxySelector");
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
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("proxy host must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("proxy port must be between 1 and 65535: " + port);
            }
            this.proxySelector = ProxySelector.of(new InetSocketAddress(host, port));
            return this;
        }

        /**
         * Configures direct connections bypassing any system or ambient proxy (ADR-0037).
         * Equivalent to setting {@link HttpClient.Builder#NO_PROXY}.
         *
         * @return this builder, for chaining
         */
        public Builder noProxy() {
            this.proxySelector = HttpClient.Builder.NO_PROXY;
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
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
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
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            char[] pass = password.toCharArray();
            this.authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(username, pass);
                    }
                    return null;
                }
            };
            return this;
        }

        /**
         * Configures universal authentication credentials for both server and proxy challenges (ADR-0037).
         *
         * @param username the username, not {@code null}
         * @param password the password, not {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code username} or {@code password} is {@code null}
         */
        public Builder credentials(String username, String password) {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            char[] pass = password.toCharArray();
            this.authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, pass);
                }
            };
            return this;
        }

        /**
         * Sets the default request body compression algorithm (ADR-0041).
         *
         * @param algorithm the compression algorithm, or {@code null} to disable
         * @return this builder, for chaining
         */
        public Builder requestCompression(CompressionAlgorithm algorithm) {
            this.requestCompressionAlgorithm = algorithm;
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
            if (thresholdBytes < 0) {
                throw new IllegalArgumentException("request compression threshold must not be negative: " + thresholdBytes);
            }
            this.requestCompressionAlgorithm = algorithm;
            this.requestCompressionThreshold = thresholdBytes;
            return this;
        }

        /**
         * Registers a custom {@link ContentCompressor} for content encoding and decoding (ADR-0041).
         *
         * @param compressor the compressor instance, not {@code null}
         * @return this builder, for chaining
         */
        public Builder contentCompressor(ContentCompressor compressor) {
            Objects.requireNonNull(compressor, "compressor must not be null");
            if (compressor.encoding() == null || compressor.encoding().isBlank()) {
                throw new IllegalArgumentException("compressor encoding must not be blank");
            }
            this.contentCompressors.put(compressor.encoding().trim().toLowerCase(java.util.Locale.ROOT), compressor);
            return this;
        }

        /**
         * Sets the trace context propagator used for distributed tracing and correlation ID propagation (ADR-0042).
         *
         * @param tracePropagator the propagator, not {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code tracePropagator} is null
         */
        public Builder tracePropagator(TracePropagator tracePropagator) {
            this.tracePropagator = Objects.requireNonNull(tracePropagator, "tracePropagator must not be null");
            return this;
        }

        /**
         * Configures automatic correlation ID injection using UUID v4 into the specified header name (ADR-0042).
         *
         * @param headerName the correlation header name (e.g. {@code "X-Request-ID"}), not blank
         * @return this builder, for chaining
         */
        public Builder correlationId(String headerName) {
            return tracePropagator(TracePropagators.correlationId(headerName));
        }

        /**
         * Configures automatic correlation ID injection into the specified header name using a custom generator (ADR-0042).
         *
         * @param headerName the correlation header name, not blank
         * @param generator the ID generator supplier, not null
         * @return this builder, for chaining
         */
        public Builder correlationId(String headerName, java.util.function.Supplier<String> generator) {
            Objects.requireNonNull(generator, "generator must not be null");
            return tracePropagator(TracePropagators.correlationId(headerName, generator::get));
        }

        /**
         * Configures automatic correlation ID propagation from an active {@link ThreadLocal} into {@code "X-Request-ID"} (ADR-0042).
         * Generates a new UUID v4 if the thread-local contains no value.
         *
         * @param threadLocal the thread local holding the correlation ID, not null
         * @return this builder, for chaining
         */
        public Builder correlationId(ThreadLocal<String> threadLocal) {
            return tracePropagator(TracePropagators.threadLocal("X-Request-ID", threadLocal, true));
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
            return tracePropagator(TracePropagators.threadLocal(headerName, threadLocal, generateIfMissing));
        }

        /**
         * Enables standard W3C {@code traceparent} propagation (RFC 8414 / W3C TraceContext Level 1) (ADR-0042).
         *
         * @return this builder, for chaining
         */
        public Builder w3cTracing() {
            return tracePropagator(TracePropagators.w3c());
        }

        /**
         * Enables or disables standard W3C {@code traceparent} propagation (ADR-0042).
         *
         * @param enabled {@code true} to enable, {@code false} to disable
         * @return this builder, for chaining
         */
        public Builder w3cTracing(boolean enabled) {
            this.tracePropagator = enabled ? TracePropagators.w3c() : null;
            return this;
        }

        /**
         * Configures the client-level circuit breaker (ADR-0043).
         *
         * @param circuitBreaker the circuit breaker, or {@code null}
         * @return this builder, for chaining
         */
        public Builder circuitBreaker(ru.jrestly.resilience.CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        /**
         * Configures the circuit breaker registry (ADR-0043).
         *
         * @param registry the registry, or {@code null}
         * @return this builder, for chaining
         */
        public Builder circuitBreakerRegistry(ru.jrestly.resilience.CircuitBreakerRegistry registry) {
            this.circuitBreakerRegistry = registry;
            return this;
        }

        /**
         * Configures the client-level rate limiter (ADR-0043).
         *
         * @param rateLimiter the rate limiter, or {@code null}
         * @return this builder, for chaining
         */
        public Builder rateLimiter(ru.jrestly.resilience.RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /**
         * Configures the rate limiter registry (ADR-0043).
         *
         * @param registry the registry, or {@code null}
         * @return this builder, for chaining
         */
        public Builder rateLimiterRegistry(ru.jrestly.resilience.RateLimiterRegistry registry) {
            this.rateLimiterRegistry = registry;
            return this;
        }

        /**
         * Sets the token manager for token caching and transparent refresh (ADR-0046).
         *
         * @param tokenManager the token manager, or {@code null}
         * @return this builder, for chaining
         */
        public Builder tokenManager(ru.jrestly.auth.TokenManager tokenManager) {
            this.tokenManager = tokenManager;
            return this;
        }

        /**
         * Enables or disables transparent 401 Unauthorized retry with token cache invalidation (ADR-0046).
         *
         * @param retryOnUnauthorized {@code true} to enable 401 retry
         * @return this builder, for chaining
         */
        public Builder retryOnUnauthorized(boolean retryOnUnauthorized) {
            this.retryOnUnauthorized = retryOnUnauthorized;
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
            this.requestInterceptors.add(ru.jrestly.auth.BearerAuth.of(token));
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
            this.requestInterceptors.add(ru.jrestly.auth.BearerAuth.of(tokenSupplier));
            return this;
        }

        /**
         * Configures Bearer authentication using a {@link ru.jrestly.auth.TokenManager} (ADR-0046).
         * Also automatically sets {@link #tokenManager(ru.jrestly.auth.TokenManager)} and enables
         * {@link #retryOnUnauthorized(boolean)}.
         *
         * @param tokenManager the token manager, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code tokenManager} is {@code null}
         */
        public Builder bearerAuth(ru.jrestly.auth.TokenManager tokenManager) {
            java.util.Objects.requireNonNull(tokenManager, "tokenManager");
            this.tokenManager = tokenManager;
            this.retryOnUnauthorized = true;
            this.requestInterceptors.add(ru.jrestly.auth.BearerAuth.of(tokenManager));
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
            this.requestInterceptors.add(ru.jrestly.auth.BasicAuth.of(username, password));
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
            this.requestInterceptors.add(ru.jrestly.auth.BasicAuth.of(usernameSupplier, passwordSupplier));
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
            this.requestInterceptors.add(ru.jrestly.auth.ApiKeyAuth.header(headerName, apiKey));
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
            this.requestInterceptors.add(ru.jrestly.auth.ApiKeyAuth.header(headerName, apiKeySupplier));
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
            this.requestInterceptors.add(ru.jrestly.auth.ApiKeyAuth.query(paramName, apiKey));
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
            this.requestInterceptors.add(ru.jrestly.auth.ApiKeyAuth.query(paramName, apiKeySupplier));
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
            java.util.Objects.requireNonNull(interceptor, "interceptor");
            this.requestInterceptors.add(interceptor);
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
            return shortCircuit(ru.jrestly.interceptor.ShortCircuitInterceptor.of(response));
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
            return shortCircuit(ru.jrestly.interceptor.ShortCircuitInterceptor.matching(predicate, req -> response));
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
            return shortCircuit(ru.jrestly.interceptor.ShortCircuitInterceptor.matching(predicate, responseSupplier));
        }

        /**
         * Validates the settings and creates the module: {@code baseUrl}
         * must be set (not blank) and an unset {@code jsonCodec} is
         * resolved from the classpath.
         *
         * @return the immutable module
         * @throws IllegalStateException when {@code baseUrl} is not set
         */
        public SimpleModuleInfo build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl is required");
            }
            if (jsonCodec == null) {
                jsonCodec = JsonCodecResolver.resolve();
            }
            return new SimpleModuleInfo(this);
        }
    }

    private static class SimpleEnvironment implements Environment<Header> {
        private final String url;
        private Header authDetails;

        SimpleEnvironment(String url) {
            this.url = url;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public Header getAuthDetails() {
            return authDetails;
        }

        @Override
        public void setAuthDetails(Header authDetails) {
            this.authDetails = authDetails;
        }
    }
}
