package ru.jrestly;

import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;
import ru.jrestly.http.RetryPolicy;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Module configuration: everything a client is built from — identity
 * ({@link #getName()}), the execution environment (base URL and typed
 * auth details via {@link #getEnvironment()}), auth headers
 * ({@link #getAuthProvider()}), the JSON codec, the timeouts, the HTTP
 * protocol version, up-front controller validation, the retry policy,
 * and request/response interceptors (ADR-0018).
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
     * moshi, the first found wins (ADR-0007) — and fails fast with an
     * {@link IllegalStateException} when none is present.
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
}
