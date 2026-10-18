package ru.jrestly.http;

import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.metrics.SimpleCallMetrics;

import java.time.Duration;
import java.util.Optional;

/**
 * Package-private collaborator (ADR-0066 step 6): the metric-notification
 * half of {@link HttpTemplate} — the four dispatch points to
 * {@link ClientMetricsListener} (ADR-0036: call lifecycle, per-attempt
 * telemetry, retry) plus the byte-accounting seams (ADR-0036 Amendment
 * #193). A pure static helper (pattern of {@link CacheDispatch} /
 * {@link ResponseBody}): the listener and the logger are passed as
 * arguments, template state is not read, nothing is allocated before the
 * presence checks — the zero-garbage no-metrics hot path stays allocation
 * free (ADR-0055).
 */
final class MetricsNotifier {

    private MetricsNotifier() {
    }

    static void callStart(
            ClientMetricsListener metricsListener,
            System.Logger logger,
            RequestContext context,
            WireRequest request
    ) {
        if (metricsListener != null && context != null) {
            try {
                metricsListener.onCallStart(context, request);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(System.Logger.Level.WARNING, "ClientMetricsListener.onCallStart failed", t);
                }
            }
        }
    }

    static void attemptComplete(
            ClientMetricsListener metricsListener,
            System.Logger logger,
            RequestContext context,
            WireRequest request,
            int attempt,
            WireResponse response,
            Throwable failure,
            long attemptStartNanos
    ) {
        if (metricsListener != null && context != null) {
            Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStartNanos);
            try {
                metricsListener.onAttemptComplete(
                        context,
                        request,
                        attempt,
                        Optional.ofNullable(response),
                        Optional.ofNullable(failure),
                        attemptDuration
                );
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(System.Logger.Level.WARNING, "ClientMetricsListener.onAttemptComplete failed", t);
                }
            }
        }
    }

    static void retry(
            ClientMetricsListener metricsListener,
            System.Logger logger,
            RequestContext context,
            WireRequest request,
            int attempt,
            Throwable reason,
            long delayMillis
    ) {
        if (metricsListener != null && context != null) {
            Duration delay = Duration.ofMillis(Math.max(0, delayMillis));
            try {
                metricsListener.onRetry(context, request, attempt, reason, delay);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(System.Logger.Level.WARNING, "ClientMetricsListener.onRetry failed", t);
                }
            }
        }
    }

    static void callComplete(
            ClientMetricsListener metricsListener,
            System.Logger logger,
            RequestContext context,
            WireRequest request,
            WireResponse response,
            Throwable failure,
            long startNanos,
            int attempts
    ) {
        if (metricsListener != null && context != null) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            long reqBytes = requestBytes(request);
            long respBytes = responseBytes(response);
            // ADR-0036 Amendment #195: report the true transmission count —
            // 0 for a short-circuited call (ADR-0047), no clamp to 1
            SimpleCallMetrics metrics = new SimpleCallMetrics(
                    context,
                    request,
                    Optional.ofNullable(response),
                    Optional.ofNullable(failure),
                    duration,
                    attempts,
                    reqBytes,
                    respBytes
            );
            try {
                metricsListener.onCallComplete(metrics);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(System.Logger.Level.WARNING, "ClientMetricsListener.onCallComplete failed", t);
                }
            }
        }
    }

    // Package-private seam (ADR-0036 Amendment #193): a supplier-rendered
    // body (ADR-0035 streaming payloads) is materialized per send, so the
    // probe must not invoke the supplier — it would run the user's producer
    // one extra time per call and swallow its exceptions. Like multipart
    // bodies, a streaming body's size is unknown to the metrics layer (-1)
    static long requestBytes(WireRequest request) {
        if (request == null) {
            return -1;
        }
        if (request.body() != null) {
            return request.body().length;
        }
        return -1;
    }

    // Package-private seam for the byte-accounting unit gate
    static long responseBytes(WireResponse response) {
        if (response == null) {
            return -1;
        }
        if (response.body() != null) {
            return response.body().length;
        }
        // WireResponse normalizes headers to non-null — this guard is
        // structurally unreachable through the public API (defensive,
        // pattern of the InMemoryHttpCache ADR-0063 seams)
        if (response.headers() != null) {
            for (Header h : response.headers()) {
                if ("content-length".equalsIgnoreCase(h.name())) {
                    try {
                        long val = Long.parseLong(h.value().trim());
                        if (val >= 0) {
                            return val;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return -1;
    }
}
