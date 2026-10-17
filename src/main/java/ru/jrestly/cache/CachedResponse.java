package ru.jrestly.cache;

import ru.jrestly.http.Header;
import ru.jrestly.http.WireResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Represents an immutable cached HTTP response entry with RFC 9111 metadata.
 */
public interface CachedResponse {

    /**
     * The cached HTTP status code (e.g. 200).
     *
     * @return status code
     */
    int statusCode();

    /**
     * The cached HTTP response headers.
     *
     * @return unmodifiable list of headers
     */
    List<Header> headers();

    /**
     * The cached response payload bytes.
     *
     * @return payload bytes, or {@code null}
     */
    byte[] body();

    /**
     * The instant when this response was received and cached.
     *
     * @return cached timestamp
     */
    Instant cachedAt();

    /**
     * The freshness lifetime in seconds determined from {@code max-age} or {@code Expires}.
     *
     * @return freshness lifetime in seconds
     */
    long freshnessLifetimeSeconds();

    /**
     * Checks if this cached entry is currently fresh against {@link Instant#now()}.
     *
     * @return {@code true} if fresh, {@code false} if stale
     */
    boolean isFresh();

    /**
     * Checks if this cached entry is fresh against the specified timestamp.
     *
     * @param now reference instant
     * @return {@code true} if fresh, {@code false} if stale
     */
    boolean isFresh(Instant now);

    /**
     * Checks if this cached response requires revalidation with the origin server
     * before use (due to {@code no-cache} or {@code must-revalidate}).
     *
     * @return {@code true} if revalidation is required
     */
    boolean mustRevalidate();

    /**
     * Checks if this cached entry has conditional validators ({@code ETag} or {@code Last-Modified}).
     *
     * @return {@code true} if at least one validator is present
     */
    boolean hasValidators();

    /**
     * The cached entity tag (ETag) value, if present.
     *
     * @return optional ETag
     */
    Optional<String> etag();

    /**
     * The cached {@code Last-Modified} header value, if present.
     *
     * @return optional Last-Modified value
     */
    Optional<String> lastModified();

    /**
     * Synthesizes a {@link WireResponse} from this cached entry with updated {@code Age} header.
     *
     * @return wire response ready to be consumed by the pipeline
     */
    WireResponse toWireResponse();

    /**
     * Synthesizes a {@link WireResponse} from this cached entry with {@code Age} computed against the specified instant.
     *
     * @param now reference instant for age calculation
     * @return wire response
     */
    WireResponse toWireResponse(Instant now);
}
