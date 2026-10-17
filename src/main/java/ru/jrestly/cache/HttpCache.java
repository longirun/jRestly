package ru.jrestly.cache;

import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.net.URI;
import java.util.Optional;

/**
 * HTTP caching SPI conforming to RFC 9111 (HTTP Caching).
 *
 * <p>Implementations store and serve cached responses, evaluate freshness lifetime,
 * inject conditional headers for stale entries, update cached entries upon receiving
 * {@code 304 Not Modified}, and invalidate cache entries on unsafe HTTP methods
 * ({@code POST}, {@code PUT}, {@code DELETE}, {@code PATCH}).
 */
public interface HttpCache {

    /**
     * Retrieves a cached response for the specified request if available.
     *
     * @param request the wire request
     * @return cached response if present, or {@link Optional#empty()}
     */
    Optional<CachedResponse> get(WireRequest request);

    /**
     * Stores a response in the cache if cacheable according to RFC 9111 rules.
     *
     * @param request the wire request
     * @param response the wire response received from the server
     */
    void put(WireRequest request, WireResponse response);

    /**
     * Updates a cached entry upon receiving a 304 Not Modified response per RFC 9111 §4.3.4.
     *
     * @param request the conditional wire request
     * @param response304 the 304 Not Modified response received from the server
     * @return the synthesized 200 OK response with updated headers and cached body,
     *         or {@code null} if no cached entry was found to update
     */
    WireResponse update(WireRequest request, WireResponse response304);

    /**
     * Invalidates cached entries associated with the specified URI (RFC 9111 §4.4).
     *
     * @param uri the URI to invalidate
     */
    void invalidate(URI uri);

    /**
     * Clears all entries from the cache.
     */
    void clear();

    /**
     * Returns the number of cached entries.
     *
     * @return entry count
     */
    long size();
}
