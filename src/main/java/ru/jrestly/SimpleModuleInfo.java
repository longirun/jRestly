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

    private SimpleModuleInfo(Builder builder) {
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.authProvider = builder.authHeaderName != null
                ? new AuthHeaderProvider(builder.authHeaderName, builder.authHeaderValue)
                : new AuthHeaderProvider();
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
         * jackson, then gson, then moshi, the first found wins (ADR-0007).
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
