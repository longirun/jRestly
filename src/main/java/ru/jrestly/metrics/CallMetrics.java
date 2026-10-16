package ru.jrestly.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;

/**
 * Snapshot of metrics and telemetry for an executed logical HTTP call.
 * <p>
 * Passed to {@link ClientMetricsListener#onCallComplete(CallMetrics)} upon call completion
 * (successful or exceptional).
 */
public interface CallMetrics {

    /**
     * The invocation context carrying method, arguments, module name, and custom attributes.
     *
     * @return the request context, never {@code null}
     */
    RequestContext context();

    /**
     * The initial wire request sent across the wire.
     *
     * @return the wire request, never {@code null}
     */
    WireRequest request();

    /**
     * The final wire response received, if any.
     *
     * @return an {@link Optional} containing the wire response, or empty on transport failure
     */
    Optional<WireResponse> response();

    /**
     * The final failure exception encountered during call execution, if any.
     *
     * @return an {@link Optional} containing the failure exception, or empty on success
     */
    Optional<Throwable> failure();

    /**
     * Total elapsed duration of the logical call from invocation start to finish.
     *
     * @return the call duration, never {@code null}
     */
    Duration duration();

    /**
     * Total number of transmission attempts executed for this call (at least 1).
     *
     * @return the attempt count
     */
    int attempts();

    /**
     * Size in bytes of the request body sent across the wire, or {@code -1} if unknown or empty.
     * <p>
     * Per-send rendered bodies (streaming {@code Supplier} payloads, multipart form data)
     * are reported as unknown ({@code -1}); only buffered {@code byte[]} bodies are measured.
     *
     * @return the request body size in bytes, or {@code -1}
     */
    long requestBytes();

    /**
     * Size in bytes of the response body received across the wire, or {@code -1} if unknown or empty.
     *
     * @return the response body size in bytes, or {@code -1}
     */
    long responseBytes();

    /**
     * The effective HTTP status code (from response, HandledException, or -1 if unknown).
     *
     * @return the HTTP status code, or -1
     */
    default int statusCode() {
        Optional<WireResponse> resp = response();
        if (resp.isPresent()) {
            return resp.get().statusCode();
        }
        Optional<Throwable> err = failure();
        if (err.isPresent()) {
            Throwable t = err.get();
            if (t instanceof HandledException handled) {
                return handled.getStatusCode();
            }
            if (t.getCause() instanceof HandledException handledCause) {
                return handledCause.getStatusCode();
            }
        }
        return -1;
    }

    /**
     * Indicates whether the call succeeded (2xx HTTP status and no failure).
     *
     * @return {@code true} if successful
     */
    default boolean isSuccess() {
        if (failure().isPresent()) {
            return false;
        }
        int code = statusCode();
        return code >= 200 && code < 300;
    }

    /**
     * Convenient shortcut for {@code request().method()}.
     *
     * @return the HTTP method
     */
    default HttpMethod method() {
        return request().method();
    }

    /**
     * Convenient shortcut for {@code request().uri()}.
     *
     * @return the request URI
     */
    default URI uri() {
        return request().uri();
    }

    /**
     * Indicates whether the call was retried (attempts &gt; 1).
     *
     * @return {@code true} if retry attempts occurred
     */
    default boolean isRetried() {
        return attempts() > 1;
    }

    /**
     * The negotiated HTTP protocol version of the call.
     *
     * @return the HTTP version, defaulting to {@link HttpClient.Version#HTTP_1_1} if unknown or failed
     */
    default HttpClient.Version version() {
        return response().map(WireResponse::version).orElse(HttpClient.Version.HTTP_1_1);
    }
}
