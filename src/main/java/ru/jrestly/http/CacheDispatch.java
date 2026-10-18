package ru.jrestly.http;

import ru.jrestly.cache.HttpCache;

import java.io.ByteArrayInputStream;

/**
 * The RFC 9111 dispatch performed after the wire (ADR-0063): decides what a
 * response means for the transparent cache.
 *
 * <p>A conditional {@code 304} revalidation is resolved through the store's
 * {@code update} (whose synthesized {@code 200} is returned, or the raw
 * {@code 304} passes through when the entry vanished meanwhile); fresh
 * {@code 200}/{@code 301}/{@code 404} GETs are stored; unsafe methods with a
 * {@code 200..399} outcome invalidate the target URI. Everything else passes
 * through untouched. The byte-payload response of a streaming return is
 * re-wrapped as a live stream so the synthesized body is consumed uniformly.
 *
 * <p>Extracted from {@code HttpTemplate} (ADR-0066) — a cohesive dispatch
 * concern whose only inputs are the request, the response and the store, so
 * the branch matrix is pinned by unit tests without a wire round-trip (a
 * {@code null} cache short-circuits before any store call).
 */
final class CacheDispatch {

    private CacheDispatch() {
    }

    /**
     * Applies the ADR-0063 post-wire dispatch to {@code resp}.
     *
     * @param req        the effective request (its method and URI drive the dispatch)
     * @param resp       the wire response, may be {@code null}
     * @param cache      the configured store, may be {@code null} (zero-garbage fast path)
     * @param streamBody {@code true} for an {@code InputStream} return type; a synthesized
     *                   cache body is then re-wrapped as a live stream
     * @return the response to hand to the pipeline — {@code resp} when untouched
     */
    static WireResponse handle(WireRequest req, WireResponse resp, HttpCache cache, boolean streamBody) {
        if (cache == null || resp == null || req == null) {
            return resp;
        }
        if (req.method() == HttpMethod.GET) {
            if (resp.statusCode() == 304) {
                WireResponse updated = cache.update(req, resp);
                if (updated != null) {
                    if (streamBody && updated.body() != null) {
                        return toStreamWireResponse(updated);
                    }
                    return updated;
                }
            } else if (resp.statusCode() == 200 || resp.statusCode() == 301 || resp.statusCode() == 404) {
                cache.put(req, resp);
            }
        } else if (resp.statusCode() >= 200 && resp.statusCode() < 400) {
            cache.invalidate(req.uri());
        }
        return resp;
    }

    /**
     * Re-wraps a byte-payload response as a live body stream (the streaming
     * compaction of a cache hit or of a synthesized {@code 200}).
     */
    static WireResponse toStreamWireResponse(WireResponse response) {
        return new WireResponse(
                response.statusCode(),
                response.headers(),
                null,
                new ByteArrayInputStream(response.body()),
                response.version(),
                response.trailers(),
                response.trailersSupplier()
        );
    }
}
