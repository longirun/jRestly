package ru.jrestly.metrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;

/**
 * Listener SPI for HTTP client observability, metrics, tracing, and timing.
 * <p>
 * Allows consumers to integrate with telemetry and monitoring systems (such as Micrometer,
 * OpenTelemetry, Prometheus, Dropwizard Metrics, or custom loggers) with zero external
 * runtime dependencies in the jRestly core.
 * <p>
 * As a {@link FunctionalInterface}, basic consumers can register a simple lambda
 * listening to completed logical calls:
 * <pre>{@code
 * JRestlyClient.builder()
 *     .metricsListener(metrics -> System.out.printf("Call to %s took %d ms, status: %d%n",
 *             metrics.uri(), metrics.duration().toMillis(), metrics.statusCode()))
 *     .build();
 * }</pre>
 * <p>
 * Comprehensive implementations can override additional default lifecycle hooks for
 * in-flight request gauges, distributed tracing spans, per-attempt wire latencies, and
 * retry counters.
 */
@FunctionalInterface
public interface ClientMetricsListener {

    /**
     * Invoked when an entire logical HTTP call completes (including all retries and redirects),
     * either successfully or exceptionally.
     *
     * @param metrics the complete call telemetry and metrics snapshot, never {@code null}
     */
    void onCallComplete(CallMetrics metrics);

    /**
     * Invoked once at the start of a logical call, immediately before the
     * first transmission is placed on the wire. Also invoked when a request
     * interceptor short-circuits or fails the call, so that no wire
     * transmission ever occurs — it pairs 1:1 with {@link #onCallComplete}.
     * Useful for starting a tracing span or incrementing in-flight request gauges.
     *
     * @param context the request context containing method metadata and attributes
     * @param request the initial wire request
     */
    default void onCallStart(RequestContext context, WireRequest request) {
    }

    /**
     * Invoked when an individual transmission attempt fails and a retry is about to be scheduled.
     *
     * @param context the request context
     * @param request the wire request that failed
     * @param attempt the 1-based index of the failed transmission within the logical
     *                call (per-call numbering, as in {@link #onAttemptComplete})
     * @param reason the failure reason (e.g. exception or HTTP response status)
     * @param delay the backoff delay before the next attempt
     */
    default void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
    }

    /**
     * Invoked when an individual transmission attempt completes across the wire (success or failure).
     *
     * @param context the request context
     * @param request the wire request for this attempt
     * @param attempt the 1-based index of this transmission within the logical call —
     *                continuous across the transparent 401-refresh phase, so the last
     *                value reported for a call equals {@link CallMetrics#attempts()}
     * @param response the response if received across the wire, or empty on transport failure
     * @param failure the failure exception if transport failed, or empty
     * @param duration the duration of this specific attempt
     */
    default void onAttemptComplete(
            RequestContext context,
            WireRequest request,
            int attempt,
            Optional<WireResponse> response,
            Optional<Throwable> failure,
            Duration duration
    ) {
    }

    /**
     * Combines multiple listeners into a composite listener executing in iteration order.
     *
     * @param listeners the listeners to combine, must not be {@code null}
     * @return a composite {@link ClientMetricsListener} delegating to all supplied listeners
     * @throws NullPointerException if {@code listeners} is {@code null} or contains {@code null}
     */
    static ClientMetricsListener composite(ClientMetricsListener... listeners) {
        Objects.requireNonNull(listeners, "listeners");
        List<ClientMetricsListener> list = new ArrayList<>();
        for (ClientMetricsListener listener : listeners) {
            Objects.requireNonNull(listener, "listener element must not be null");
            if (listener instanceof CompositeClientMetricsListener composite) {
                list.addAll(composite.listeners());
            } else {
                list.add(listener);
            }
        }
        if (list.isEmpty()) {
            return metrics -> {
            };
        }
        if (list.size() == 1) {
            return list.getFirst();
        }
        return new CompositeClientMetricsListener(List.copyOf(list));
    }
}
