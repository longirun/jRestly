package ru.jrestly.tracing;

import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Invocation context passed to {@link TracePropagator} containing the request metadata
 * and currently prepared headers.
 *
 * @param controllerClass the controller interface class
 * @param method the method being executed, or {@code null} if dynamically constructed
 * @param url the full destination URL
 * @param httpMethod the HTTP method
 * @param existingHeaders unmodifiable snapshot of headers already accumulated for the request
 */
public record TraceRequestContext(
        Class<?> controllerClass,
        Method method,
        String url,
        HttpMethod httpMethod,
        List<Header> existingHeaders
) {

    /**
     * Compact constructor ensuring immutable header list.
     */
    public TraceRequestContext {
        existingHeaders = existingHeaders != null ? Collections.unmodifiableList(existingHeaders) : List.of();
    }

    /**
     * Checks whether a header with the specified name (case-insensitive) is already present.
     *
     * @param name header name to check
     * @return {@code true} if present, {@code false} otherwise
     */
    public boolean hasHeader(String name) {
        if (name == null || existingHeaders == null) {
            return false;
        }
        for (Header h : existingHeaders) {
            if (name.equalsIgnoreCase(h.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first value of the specified header (case-insensitive) if present.
     *
     * @param name header name
     * @return the header value or {@code null}
     */
    public String firstHeader(String name) {
        if (name == null || existingHeaders == null) {
            return null;
        }
        for (Header h : existingHeaders) {
            if (name.equalsIgnoreCase(h.name())) {
                return h.value();
            }
        }
        return null;
    }
}
