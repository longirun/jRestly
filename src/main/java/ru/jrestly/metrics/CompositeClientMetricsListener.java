package ru.jrestly.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;

/**
 * Composite implementation of {@link ClientMetricsListener} delegating calls to a sequence of listeners.
 *
 * @param listeners unmodifiable list of child listeners, must not be {@code null}
 */
record CompositeClientMetricsListener(List<ClientMetricsListener> listeners) implements ClientMetricsListener {

    CompositeClientMetricsListener {
        Objects.requireNonNull(listeners, "listeners");
    }

    @Override
    public void onCallComplete(CallMetrics metrics) {
        for (ClientMetricsListener listener : listeners) {
            listener.onCallComplete(metrics);
        }
    }

    @Override
    public void onCallStart(RequestContext context, WireRequest request) {
        for (ClientMetricsListener listener : listeners) {
            listener.onCallStart(context, request);
        }
    }

    @Override
    public void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
        for (ClientMetricsListener listener : listeners) {
            listener.onRetry(context, request, attempt, reason, delay);
        }
    }

    @Override
    public void onAttemptComplete(
            RequestContext context,
            WireRequest request,
            int attempt,
            Optional<WireResponse> response,
            Optional<Throwable> failure,
            Duration duration
    ) {
        for (ClientMetricsListener listener : listeners) {
            listener.onAttemptComplete(context, request, attempt, response, failure, duration);
        }
    }
}
