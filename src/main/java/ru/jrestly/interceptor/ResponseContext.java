package ru.jrestly.interceptor;

import java.time.Duration;
import java.util.Optional;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

/**
 * Context passed to {@link ResponseInterceptor}, extending {@link RequestContext} with
 * wire request, response, failure, and execution duration details.
 */
public interface ResponseContext extends RequestContext {

    /**
     * The final wire request sent across the wire.
     *
     * @return the wire request, never {@code null}
     */
    WireRequest request();

    /**
     * The wire response received from the server, if any.
     *
     * @return an {@link Optional} containing the response, or empty on transport failure
     */
    Optional<WireResponse> response();

    /**
     * The failure encountered during execution, if any.
     *
     * @return an {@link Optional} containing the failure exception, or empty on success
     */
    Optional<Throwable> failure();

    /**
     * The total elapsed duration of the logical call.
     *
     * @return the duration, never {@code null}
     */
    Duration duration();

    /**
     * Returns the HTTP status code from the response, or from {@link HandledException} if available,
     * or {@code -1} if no status code is known.
     *
     * @return the HTTP status code, or {@code -1}
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
     * Indicates whether the call was successful (no failure and 2xx HTTP status).
     *
     * @return {@code true} if failure is absent and status code is between 200 and 299 inclusive
     */
    default boolean isSuccess() {
        if (failure().isPresent()) {
            return false;
        }
        int code = statusCode();
        return code >= 200 && code < 300;
    }
}
