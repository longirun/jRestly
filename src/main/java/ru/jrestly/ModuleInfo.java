package ru.jrestly;

import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;
import ru.jrestly.http.RetryPolicy;

import java.net.http.HttpClient;

public interface ModuleInfo {

    String getName();

    <T> Environment<T> getEnvironment();

    AuthProvider getAuthProvider();

    default JsonCodec getJsonCodec() {
        return JsonCodecResolver.resolve();
    }

    default int getConnectTimeout() {
        return 5_000;
    }

    default int getSocketTimeout() {
        return 30_000;
    }

    default HttpClient.Version getHttpVersion() {
        return HttpClient.Version.HTTP_2;
    }

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
     */
    default RetryPolicy getRetryPolicy() {
        return RetryPolicy.off();
    }
}
