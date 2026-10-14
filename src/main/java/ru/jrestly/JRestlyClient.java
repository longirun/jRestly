package ru.jrestly;

import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;
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
         * jackson, then gson, then moshi, the first found wins
         * (ADR-0007).
         *
         * @param jsonCodec the codec to use
         * @return this builder, for chaining
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            moduleInfoBuilder.jsonCodec(jsonCodec);
            return this;
        }

        /**
         * The connect timeout in milliseconds (default 5 000).
         *
         * @param ms the connect timeout in milliseconds
         * @return this builder, for chaining
         */
        public Builder connectTimeout(int ms) {
            moduleInfoBuilder.connectTimeout(ms);
            return this;
        }

        /**
         * The socket (request) timeout in milliseconds (default 30 000).
         *
         * @param ms the socket timeout in milliseconds
         * @return this builder, for chaining
         */
        public Builder socketTimeout(int ms) {
            moduleInfoBuilder.socketTimeout(ms);
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
