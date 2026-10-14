package ru.jrestly.http;

import java.util.Objects;

/**
 * A pair holding a domain result and its associated response metadata
 * captured via {@link ResponseHeaders#capture(java.util.function.Supplier)}.
 *
 * @param <T> the result type
 * @param result the returned domain payload
 * @param headers the captured response headers and metadata
 */
public record CapturedResponse<T>(T result, ResponseHeaders headers) {
    /**
     * Creates a captured response pair.
     *
     * @param result the domain result
     * @param headers the response headers, not {@code null}
     */
    public CapturedResponse {
        Objects.requireNonNull(headers, "headers must not be null");
    }
}
