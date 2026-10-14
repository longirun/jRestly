package ru.jrestly.interceptor;

/**
 * Post-execution interceptor hook for observability, logging, metrics, and auditing.
 * <p>
 * Invoked once the HTTP call concludes — either successfully with a response or exceptionally
 * with an error. Designed for non-intrusive observation.
 */
@FunctionalInterface
public interface ResponseInterceptor {

    /**
     * Invoked after the HTTP request execution completes.
     *
     * @param context the response context containing request, response, failure, and timing details
     */
    void onResponse(ResponseContext context);
}
