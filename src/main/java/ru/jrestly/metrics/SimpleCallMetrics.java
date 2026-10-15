package ru.jrestly.metrics;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;

/**
 * Standard immutable implementation of {@link CallMetrics}.
 *
 * @param context the request invocation context, must not be {@code null}
 * @param request the wire request, must not be {@code null}
 * @param response the wire response, or empty
 * @param failure the failure, or empty
 * @param duration the duration, must not be {@code null}
 * @param attempts the number of attempts executed
 * @param requestBytes the request body bytes, or -1
 * @param responseBytes the response body bytes, or -1
 */
public record SimpleCallMetrics(
        RequestContext context,
        WireRequest request,
        Optional<WireResponse> response,
        Optional<Throwable> failure,
        Duration duration,
        int attempts,
        long requestBytes,
        long responseBytes
) implements CallMetrics {

    /**
     * Compact constructor validating non-null mandatory fields.
     */
    public SimpleCallMetrics {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(duration, "duration");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1, was: " + attempts);
        }
    }
}
