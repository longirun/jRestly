package ru.jrestly.http;

import ru.jrestly.cache.CacheControl;
import ru.jrestly.cache.CachedResponse;
import ru.jrestly.cache.HttpCache;

import java.io.ByteArrayInputStream;

/**
 * The RFC 9111 dispatch performed on both sides of the wire (ADR-0063):
 * consulting the transparent cache before the request and deciding what a
 * response means for it afterwards.
 *
 * <p>Before the wire, {@code lookup} resolves the request's entry in the
 * store, classifies it as a fresh hit or a stale revalidation candidate
 * (honoring the request's {@code Cache-Control: no-cache} and the entry's
 * {@code must-revalidate}) and, for a stale entry carrying validators,
 * injects the conditional request headers ({@code If-None-Match},
 * {@code If-Modified-Since}) the revalidation handshake needs — without ever
 * overriding conditional headers the caller supplied.
 *
 * <p>After the wire, {@code handle} decides what the response means for the
 * store: a conditional {@code 304} revalidation is resolved through the
 * store's {@code update} (whose synthesized {@code 200} is returned, or the
 * raw {@code 304} passes through when the entry vanished meanwhile); fresh
 * {@code 200}/{@code 301}/{@code 404} GETs are stored; unsafe methods with a
 * {@code 200..399} outcome invalidate the target URI. Everything else passes
 * through untouched. {@code synthesizeHit} materializes a cache hit for the
 * buffered or the streaming return — the byte-payload body of a streaming
 * return is re-wrapped as a live stream so a synthesized body is consumed
 * uniformly.
 *
 * <p>Extracted from {@code HttpTemplate} (ADR-0066, pre-wire consultation in
 * ADR-0093) — a cohesive dispatch concern whose only inputs are the request,
 * the response and the store, so the branch matrix is pinned by unit tests
 * without a wire round-trip (a {@code null} cache short-circuits before any
 * store call).
 */
final class CacheDispatch {

    /**
     * The outcome of consulting the store before the request: the (possibly
     * conditional) request to send upstream, whether the entry is a fresh
     * hit (no wire transmission needed) and the entry itself, or {@code null}
     * when nothing was cached or revalidation is required.
     */
    record CacheLookup(WireRequest request, boolean isCacheHit, CachedResponse entry) {
    }

    private CacheDispatch() {
    }

    /**
     * Consults the cache before the request (ADR-0063): resolves the entry
     * for {@code GET}, classifies the freshness and injects the conditional
     * request headers a revalidation needs.
     *
     * @param req   the effective request, may be {@code null} (defensive not-hit)
     * @param cache the configured store, may be {@code null} (defensive not-hit;
     *              the zero-garbage template calls this only behind an
     *              explicit {@code cache != null} guard)
     */
    static CacheLookup lookup(WireRequest req, HttpCache cache) {
        if (cache == null || req == null || req.method() != HttpMethod.GET) {
            return new CacheLookup(req, false, null);
        }
        CachedResponse entry = cache.get(req).orElse(null);
        boolean requestNoCache = false;
        if (entry != null) {
            requestNoCache = CacheControl.parse(req.headers()).noCache();
        }
        boolean isCacheHit = entry != null && entry.isFresh() && !entry.mustRevalidate() && !requestNoCache;
        WireRequest conditional = req;
        if (entry != null && !isCacheHit && entry.hasValidators()) {
            if (entry.etag().isPresent() && !hasHeader(conditional, "If-None-Match")) {
                conditional = conditional.withHeader("If-None-Match", entry.etag().get());
            }
            if (entry.lastModified().isPresent() && !hasHeader(conditional, "If-Modified-Since")) {
                conditional = conditional.withHeader("If-Modified-Since", entry.lastModified().get());
            }
        }
        return new CacheLookup(conditional, isCacheHit, entry);
    }

    /**
     * Materializes a cache hit as the response to hand to the pipeline.
     *
     * @param entry      the cached entry, must not be {@code null}
     * @param streamBody {@code true} for an {@code InputStream} return type; a
     *                   byte payload is then re-wrapped as a live stream
     */
    static WireResponse synthesizeHit(CachedResponse entry, boolean streamBody) {
        WireResponse hit = entry.toWireResponse();
        if (streamBody && hit.body() != null) {
            return toStreamWireResponse(hit);
        }
        return hit;
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
     * Case-insensitive header-name scan over the request headers.
     *
     * <p>Defensive branches (null request, null header list, null list
     * elements, null names) exist to shield against foreign {@code WireRequest}
     * shapes and are pinned by {@code HttpTemplateConditionalHeaderTest}.
     */
    static boolean hasHeader(WireRequest req, String name) {
        if (req == null || req.headers() == null) {
            return false;
        }
        for (Header h : req.headers()) {
            if (h != null && h.name() != null && h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
