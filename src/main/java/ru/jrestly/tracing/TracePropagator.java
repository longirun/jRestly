package ru.jrestly.tracing;

import java.util.Map;

/**
 * Service Provider Interface (SPI) for injecting distributed tracing headers
 * and correlation identifiers into outgoing HTTP requests.
 */
@FunctionalInterface
public interface TracePropagator {

    /**
     * Injects tracing headers based on the current request context.
     *
     * @param context the trace request context
     * @return map of header names to values to be added to the request, never {@code null}
     */
    Map<String, String> inject(TraceRequestContext context);
}
