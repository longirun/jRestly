package ru.jrestly.tracing;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Standard factory methods and implementations for {@link TracePropagator}.
 */
public final class TracePropagators {

    private static final String DEFAULT_CORRELATION_HEADER = "X-Request-ID";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String TRACESTATE_HEADER = "tracestate";

    private static final TracePropagator W3C_DEFAULT = context -> {
        if (context.hasHeader(TRACEPARENT_HEADER)) {
            return Map.of();
        }
        TraceContext traceContext = TraceContext.random();
        return Map.of(TRACEPARENT_HEADER, traceContext.toTraceparent());
    };

    private TracePropagators() {
    }

    /**
     * Creates a propagator that injects a unique correlation ID into {@code X-Request-ID}
     * using UUID v4, unless the header was already specified by the caller.
     *
     * @return the default correlation ID propagator
     */
    public static TracePropagator correlationId() {
        return correlationId(DEFAULT_CORRELATION_HEADER, TraceIdGenerators.uuid());
    }

    /**
     * Creates a propagator that injects a unique correlation ID into the given header name
     * using UUID v4, unless the header was already specified by the caller.
     *
     * @param headerName header name to inject (e.g. {@code "X-Request-ID"} or {@code "X-Correlation-ID"})
     * @return correlation ID propagator
     */
    public static TracePropagator correlationId(String headerName) {
        return correlationId(headerName, TraceIdGenerators.uuid());
    }

    /**
     * Creates a propagator that injects a correlation ID into the given header name
     * using the provided generator, unless the header was already specified by the caller.
     *
     * @param headerName header name, not null or blank
     * @param generator generator producing correlation IDs, not null
     * @return correlation ID propagator
     */
    public static TracePropagator correlationId(String headerName, TraceIdGenerator generator) {
        Objects.requireNonNull(headerName, "headerName must not be null");
        if (headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        Objects.requireNonNull(generator, "generator must not be null");

        return context -> {
            if (context.hasHeader(headerName)) {
                return Map.of();
            }
            String id = generator.generate();
            return id != null && !id.isBlank() ? Map.of(headerName, id.trim()) : Map.of();
        };
    }

    /**
     * Returns a standard W3C TraceContext propagator that injects a {@code traceparent}
     * header with random trace ID and span ID if none is already present.
     *
     * @return W3C trace propagator
     */
    public static TracePropagator w3c() {
        return W3C_DEFAULT;
    }

    /**
     * Creates a W3C TraceContext propagator that supplies {@link TraceContext} from
     * a dynamic supplier (e.g. active context from thread or tracing framework).
     *
     * @param supplier supplier of active trace context, or null supplier
     * @param generateIfMissing if true, generates a random trace context if supplier returns null
     * @return W3C trace propagator
     */
    public static TracePropagator w3c(Supplier<TraceContext> supplier, boolean generateIfMissing) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return context -> {
            if (context.hasHeader(TRACEPARENT_HEADER)) {
                return Map.of();
            }
            TraceContext tc = supplier.get();
            if (tc == null && generateIfMissing) {
                tc = TraceContext.random();
            }
            if (tc == null) {
                return Map.of();
            }

            Map<String, String> map = new LinkedHashMap<>();
            map.put(TRACEPARENT_HEADER, tc.toTraceparent());
            if (tc.traceState() != null && !tc.traceState().isBlank() && !context.hasHeader(TRACESTATE_HEADER)) {
                map.put(TRACESTATE_HEADER, tc.traceState());
            }
            return Collections.unmodifiableMap(map);
        };
    }

    /**
     * Creates a propagator that extracts correlation ID from a {@link ThreadLocal} holder.
     * If missing and {@code generateIfMissing} is true, generates a new UUID v4.
     *
     * @param headerName header name (e.g. {@code "X-Request-ID"}), not null
     * @param threadLocal thread local holding active correlation ID
     * @param generateIfMissing whether to generate a fallback UUID if thread local is null or blank
     * @return context-aware correlation propagator
     */
    public static TracePropagator threadLocal(String headerName, ThreadLocal<String> threadLocal, boolean generateIfMissing) {
        Objects.requireNonNull(headerName, "headerName must not be null");
        if (headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        Objects.requireNonNull(threadLocal, "threadLocal must not be null");

        TraceIdGenerator generator = TraceIdGenerators.threadLocal(threadLocal,
                generateIfMissing ? () -> java.util.UUID.randomUUID().toString() : () -> null);

        return correlationId(headerName, generator);
    }

    /**
     * Combines multiple {@link TracePropagator} instances into a single composite propagator.
     * Propagators run in order; subsequent propagators do not overwrite previously injected headers.
     *
     * @param propagators the propagators to combine
     * @return composite propagator
     */
    public static TracePropagator composite(TracePropagator... propagators) {
        if (propagators == null || propagators.length == 0) {
            return context -> Map.of();
        }
        List<TracePropagator> list = Arrays.stream(propagators)
                .filter(Objects::nonNull)
                .toList();

        return context -> {
            Map<String, String> combined = new LinkedHashMap<>();
            for (TracePropagator p : list) {
                Map<String, String> injected = p.inject(context);
                if (injected != null) {
                    for (Map.Entry<String, String> entry : injected.entrySet()) {
                        String k = entry.getKey();
                        String v = entry.getValue();
                        if (k != null && v != null && !context.hasHeader(k)) {
                            combined.putIfAbsent(k, v);
                        }
                    }
                }
            }
            return Collections.unmodifiableMap(combined);
        };
    }
}
