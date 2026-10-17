package ru.jrestly.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.cache.CachedResponse;
import ru.jrestly.cache.HttpCache;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code HttpTemplate.handleCacheResponse} — the ADR-0063
 * RFC 9111 dispatch after the wire: a conditional 304 revalidation resolves
 * through the store's {@code update} (the synthesized 200 is returned, or
 * the raw 304 passes through when the entry vanished), fresh 200/301/404
 * GETs are stored, and unsafe methods with a 200..399 outcome invalidate
 * the target URI. No network — the seam is package-private on purpose and
 * the cache is a recording fake, so the store contract and the dispatch
 * contract are pinned separately.
 */
class HttpTemplateCacheDispatchTest {

    private static final URI TARGET = URI.create("http://example.com/items/1");

    private final RecordingCache cache = new RecordingCache();
    private final HttpTemplate template = new HttpTemplate();

    HttpTemplateCacheDispatchTest() {
        template.setHttpCache(cache);
    }

    @BeforeEach
    void reset() {
        cache.reset();
    }

    private static WireRequest request(HttpMethod method) {
        return new WireRequest(method, TARGET, List.of(), null, null);
    }

    @Test
    @DisplayName("absent cache: the response passes through untouched (zero-garbage fast path)")
    void absentCachePassesThrough() {
        template.setHttpCache(null);
        WireResponse resp = WireResponse.of(200, "fresh");

        assertSame(resp, template.handleCacheResponse(request(HttpMethod.GET), resp));
    }

    @Test
    @DisplayName("null response or null request: returned as-is, no store interaction")
    void nullsPassThrough() {
        WireResponse resp = WireResponse.of(200, "fresh");

        assertNull(template.handleCacheResponse(request(HttpMethod.GET), null));
        assertSame(resp, template.handleCacheResponse(null, resp));
        assertTrue(cache.puts.isEmpty() && cache.updates.isEmpty() && cache.invalidations.isEmpty());
    }

    @Test
    @DisplayName("GET 304 revalidation: the store's synthesized 200 replaces the raw 304")
    void get304ReturnsSynthesizedResponse() {
        cache.updateResult = WireResponse.of(200, "cached-body");
        WireResponse raw304 = WireResponse.of(304, "");

        WireResponse out = template.handleCacheResponse(request(HttpMethod.GET), raw304);

        assertEquals(1, cache.updates.size());
        assertSame(raw304, cache.updatedWith);
        assertSame(cache.updateResult, out);
        assertEquals(200, out.statusCode());
    }

    @Test
    @DisplayName("GET 304 with the entry evicted meanwhile: update returns null, the raw 304 passes through")
    void get304WithEvictedEntryPassesRaw304Through() {
        cache.updateResult = null;
        WireResponse raw304 = WireResponse.of(304, "");

        WireResponse out = template.handleCacheResponse(request(HttpMethod.GET), raw304);

        assertSame(raw304, out);
        assertEquals(304, out.statusCode());
    }

    @Test
    @DisplayName("GET 304 on a streaming return: the synthesized 200 is re-wrapped as a live stream")
    void get304StreamingReturnIsStreamified() {
        cache.updateResult = new WireResponse(200, List.of(new Header("Content-Type", "text/plain")),
                "cached-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        template.setStreamBody(true);

        WireResponse out = template.handleCacheResponse(request(HttpMethod.GET), WireResponse.of(304, ""));

        assertEquals(200, out.statusCode());
        assertNull(out.body());
        assertTrue(out.stream() instanceof java.io.ByteArrayInputStream);
    }

    @Test
    @DisplayName("GET 304 streaming return with a body-less synthesized response: no streamify, returned as-is")
    void get304StreamingWithNullBodyIsReturnedPlain() {
        cache.updateResult = new WireResponse(200, List.of(), null);
        template.setStreamBody(true);

        WireResponse out = template.handleCacheResponse(request(HttpMethod.GET), WireResponse.of(304, ""));

        assertSame(cache.updateResult, out);
        assertNull(out.stream());
    }

    @Test
    @DisplayName("GET 200/301/404: the response is stored, the original instance passes through")
    void getCacheableStatusesAreStored() {
        for (int status : new int[]{200, 301, 404}) {
            reset();
            WireResponse resp = WireResponse.of(status, "body");

            assertSame(resp, template.handleCacheResponse(request(HttpMethod.GET), resp));
            assertEquals(1, cache.puts.size(), "status " + status);
            assertSame(resp, cache.putResponse);
            assertTrue(cache.updates.isEmpty() && cache.invalidations.isEmpty());
        }
    }

    @Test
    @DisplayName("GET with any other status (204, 500): no put, no update, the response passes through")
    void getNonCacheableStatusIsUntouched() {
        for (int status : new int[]{204, 302, 500}) {
            reset();
            WireResponse resp = WireResponse.of(status, "body");

            assertSame(resp, template.handleCacheResponse(request(HttpMethod.GET), resp));
            assertTrue(cache.puts.isEmpty() && cache.updates.isEmpty() && cache.invalidations.isEmpty(),
                    "status " + status);
        }
    }

    @Test
    @DisplayName("unsafe methods with 200..399: the target URI is invalidated, the response passes through")
    void unsafeMethodsInvalidateOn2xx3xx() {
        for (int status : new int[]{200, 304, 399}) {
            reset();
            WireResponse resp = WireResponse.of(status, "body");

            assertSame(resp, template.handleCacheResponse(request(HttpMethod.POST), resp));
            assertEquals(List.of(TARGET), cache.invalidations, "status " + status);
            assertTrue(cache.puts.isEmpty() && cache.updates.isEmpty());
        }
    }

    @Test
    @DisplayName("unsafe methods outside 200..399 (199, 400, 500): no invalidation")
    void unsafeMethodsDoNotInvalidateOutsideRange() {
        for (int status : new int[]{199, 400, 500}) {
            reset();
            WireResponse resp = WireResponse.of(status, "body");

            assertSame(resp, template.handleCacheResponse(request(HttpMethod.PUT), resp));
            assertTrue(cache.invalidations.isEmpty() && cache.puts.isEmpty() && cache.updates.isEmpty(),
                    "status " + status);
        }
    }

    @Test
    @DisplayName("PATCH and DELETE invalidate on 2xx, like POST and PUT")
    void patchAndDeleteInvalidateOnSuccess() {
        for (HttpMethod method : List.of(HttpMethod.PATCH, HttpMethod.DELETE)) {
            reset();
            WireResponse resp = WireResponse.of(204, "");

            assertSame(resp, template.handleCacheResponse(request(method), resp));
            assertEquals(List.of(TARGET), cache.invalidations);
        }
    }

    /** Records every store interaction and replays a configurable 304 update. */
    private static final class RecordingCache implements HttpCache {
        private final List<WireRequest> puts = new ArrayList<>();
        private final List<WireRequest> updates = new ArrayList<>();
        private final List<URI> invalidations = new ArrayList<>();
        private WireResponse putResponse;
        private WireResponse updatedWith;
        private WireResponse updateResult = WireResponse.of(200, "cached");

        void reset() {
            puts.clear();
            updates.clear();
            invalidations.clear();
            putResponse = null;
            updatedWith = null;
            updateResult = WireResponse.of(200, "cached");
        }

        @Override
        public Optional<CachedResponse> get(WireRequest request) {
            return Optional.empty();
        }

        @Override
        public void put(WireRequest request, WireResponse response) {
            puts.add(request);
            putResponse = response;
        }

        @Override
        public WireResponse update(WireRequest request, WireResponse response304) {
            updates.add(request);
            updatedWith = response304;
            return updateResult;
        }

        @Override
        public void invalidate(URI uri) {
            invalidations.add(uri);
        }

        @Override
        public void clear() {
            puts.clear();
            updates.clear();
            invalidations.clear();
        }

        @Override
        public long size() {
            return puts.size();
        }
    }
}
