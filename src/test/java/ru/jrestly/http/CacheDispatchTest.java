package ru.jrestly.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.cache.CachedResponse;
import ru.jrestly.cache.HttpCache;
import ru.jrestly.cache.SimpleCachedResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code CacheDispatch} — the ADR-0063 RFC 9111 dispatch on
 * both sides of the wire. After the wire: a conditional 304 revalidation
 * resolves through the store's {@code update} (the synthesized 200 is
 * returned, or the raw 304 passes through when the entry vanished), fresh
 * 200/301/404 GETs are stored, and unsafe methods with a 200..399 outcome
 * invalidate the target URI. Before the wire: {@code lookup} consults the
 * store, classifies the entry as a fresh hit or a stale revalidation
 * candidate and injects the conditional request headers, and
 * {@code synthesizeHit} materializes the entry for the buffered or the
 * streaming return. No network — the collaborator is package-private on
 * purpose and the cache is a recording fake, so the store contract and the
 * dispatch contract are pinned separately.
 */
class CacheDispatchTest {

    private static final URI TARGET = URI.create("http://example.com/items/1");

    private final RecordingCache cache = new RecordingCache();

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
        WireResponse resp = WireResponse.of(200, "fresh");

        assertSame(resp, CacheDispatch.handle(request(HttpMethod.GET), resp, null, false));
    }

    @Test
    @DisplayName("null response or null request: returned as-is, no store interaction")
    void nullsPassThrough() {
        WireResponse resp = WireResponse.of(200, "fresh");

        assertNull(CacheDispatch.handle(request(HttpMethod.GET), null, cache, false));
        assertSame(resp, CacheDispatch.handle(null, resp, cache, false));
        assertTrue(cache.puts.isEmpty() && cache.updates.isEmpty() && cache.invalidations.isEmpty());
    }

    @Test
    @DisplayName("GET 304 revalidation: the store's synthesized 200 replaces the raw 304")
    void get304ReturnsSynthesizedResponse() {
        cache.updateResult = WireResponse.of(200, "cached-body");
        WireResponse raw304 = WireResponse.of(304, "");

        WireResponse out = CacheDispatch.handle(request(HttpMethod.GET), raw304, cache, false);

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

        WireResponse out = CacheDispatch.handle(request(HttpMethod.GET), raw304, cache, false);

        assertSame(raw304, out);
        assertEquals(304, out.statusCode());
    }

    @Test
    @DisplayName("GET 304 on a streaming return: the synthesized 200 is re-wrapped as a live stream")
    void get304StreamingReturnIsStreamified() {
        cache.updateResult = new WireResponse(200, List.of(new Header("Content-Type", "text/plain")),
                "cached-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        WireResponse out = CacheDispatch.handle(request(HttpMethod.GET), WireResponse.of(304, ""), cache, true);

        assertEquals(200, out.statusCode());
        assertNull(out.body());
        assertTrue(out.stream() instanceof java.io.ByteArrayInputStream);
    }

    @Test
    @DisplayName("GET 304 streaming return with a body-less synthesized response: no streamify, returned as-is")
    void get304StreamingWithNullBodyIsReturnedPlain() {
        cache.updateResult = new WireResponse(200, List.of(), null);

        WireResponse out = CacheDispatch.handle(request(HttpMethod.GET), WireResponse.of(304, ""), cache, true);

        assertSame(cache.updateResult, out);
        assertNull(out.stream());
    }

    @Test
    @DisplayName("streaming compaction: a byte-payload response is re-wrapped as a live body stream")
    void toStreamWireResponseCarriesHeadersAndVersion() {
        WireResponse resp = WireResponse.of(200, "payload");

        WireResponse streamed = CacheDispatch.toStreamWireResponse(resp);

        assertEquals(200, streamed.statusCode());
        assertNull(streamed.body());
        assertTrue(streamed.stream() instanceof java.io.ByteArrayInputStream);
        assertEquals(resp.headers(), streamed.headers());
        assertEquals(resp.version(), streamed.version());
    }

    private static CachedResponse freshEntry(String etag, String lastModified) {
        return cachedEntry(List.of(), null, 60, etag, lastModified);
    }

    private static CachedResponse staleEntry(String etag, String lastModified) {
        return cachedEntry(List.of(), null, 0, etag, lastModified);
    }

    private static CachedResponse cachedEntry(List<Header> headers, byte[] body, long freshnessSeconds,
                                              String etag, String lastModified) {
        return new SimpleCachedResponse(200, headers, body, Instant.now(), freshnessSeconds, false, false, etag,
                lastModified, java.net.http.HttpClient.Version.HTTP_1_1);
    }

    private static String headerValue(List<Header> headers, String name) {
        return headers.stream()
                .filter(h -> h.name().equalsIgnoreCase(name))
                .map(Header::value)
                .findFirst()
                .orElse(null);
    }

    private static void assertConditional(WireRequest req, String etag, String lastModified) {
        assertEquals(etag, headerValue(req.headers(), "If-None-Match"), "If-None-Match");
        assertEquals(lastModified, headerValue(req.headers(), "If-Modified-Since"), "If-Modified-Since");
    }

    @Test
    @DisplayName("lookup: absent store — no hit, no entry, the request passes through unchanged")
    void absentStoreLookupIsNotHit() {
        WireRequest req = request(HttpMethod.GET);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(req, null);

        assertNull(result.entry());
        assertTrue(!result.isCacheHit());
        assertSame(req, result.request());
    }

    @Test
    @DisplayName("lookup: null request — defensive not-hit without touching the store")
    void nullRequestLookupIsDefensivelyNotHit() {
        CacheDispatch.CacheLookup result = CacheDispatch.lookup(null, cache);

        assertNull(result.entry());
        assertTrue(!result.isCacheHit());
        assertEquals(0, cache.getCalls);
    }

    @Test
    @DisplayName("lookup: non-GET skips the store entirely even when the store holds entries")
    void nonGetLookupBypassesTheStore() {
        cache.getEntry = staleEntry("etag-1", null);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.POST), cache);

        assertNull(result.entry());
        assertTrue(!result.isCacheHit());
        assertEquals(0, cache.getCalls);
    }

    @Test
    @DisplayName("lookup: a fresh entry is a hit and the request is returned unchanged")
    void freshEntryIsCacheHitWithUnchangedRequest() {
        CachedResponse entry = freshEntry("etag-1", "lm-1");
        cache.getEntry = entry;
        WireRequest req = request(HttpMethod.GET);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(req, cache);

        assertSame(entry, result.entry());
        assertTrue(result.isCacheHit());
        assertSame(req, result.request());
        assertConditional(result.request(), null, null);
    }

    @Test
    @DisplayName("lookup: a stale entry is not a hit and both validators become conditional request headers")
    void staleEntryInjectsBothValidators() {
        cache.getEntry = staleEntry("etag-1", "lm-1");

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.GET), cache);

        assertSame(cache.getEntry, result.entry());
        assertTrue(!result.isCacheHit());
        assertConditional(result.request(), "etag-1", "lm-1");
    }

    @Test
    @DisplayName("lookup: an etag-only stale entry injects only If-None-Match")
    void staleEntryWithEtagOnlyInjectsIfNoneMatch() {
        cache.getEntry = staleEntry("etag-1", null);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.GET), cache);

        assertConditional(result.request(), "etag-1", null);
    }

    @Test
    @DisplayName("lookup: a Last-Modified-only stale entry injects only If-Modified-Since")
    void staleEntryWithLastModifiedOnlyInjectsIfModifiedSince() {
        cache.getEntry = staleEntry(null, "lm-1");

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.GET), cache);

        assertConditional(result.request(), null, "lm-1");
    }

    @Test
    @DisplayName("lookup: existing conditional headers are never overridden")
    void existingConditionalHeadersAreNotOverridden() {
        cache.getEntry = staleEntry("etag-1", "lm-1");
        WireRequest req = request(HttpMethod.GET)
                .withHeader("If-None-Match", "mine")
                .withHeader("If-Modified-Since", "mine-date");

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(req, cache);

        assertConditional(result.request(), "mine", "mine-date");
    }

    @Test
    @DisplayName("lookup: a fresh entry with request Cache-Control: no-cache is not a hit, but validators are still injected")
    void requestNoCacheDisablesHitButStillInjectsValidators() {
        cache.getEntry = freshEntry("etag-1", null);
        WireRequest req = request(HttpMethod.GET).withHeader("Cache-Control", "no-cache");

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(req, cache);

        assertTrue(!result.isCacheHit());
        assertConditional(result.request(), "etag-1", null);
    }

    @Test
    @DisplayName("lookup: a stale entry without validators is left untouched (nothing to revalidate with)")
    void staleEntryWithoutValidatorsIsUntouched() {
        cache.getEntry = staleEntry(null, null);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.GET), cache);

        assertTrue(!result.isCacheHit());
        assertConditional(result.request(), null, null);
    }

    @Test
    @DisplayName("lookup: a fresh entry with must-revalidate is not a hit (revalidation is always required)")
    void mustRevalidateEntryIsNeverAHit() {
        cache.getEntry = new SimpleCachedResponse(200, List.of(), null, Instant.now(), 60, false, true, null, null,
                java.net.http.HttpClient.Version.HTTP_1_1);

        CacheDispatch.CacheLookup result = CacheDispatch.lookup(request(HttpMethod.GET), cache);

        assertTrue(!result.isCacheHit());
        assertConditional(result.request(), null, null);
    }

    @Test
    @DisplayName("synthesizeHit: a buffered return gets the entry's wire response as-is")
    void bufferedHitReturnsTheEntryResponse() {
        CachedResponse entry = cachedEntry(List.of(new Header("Content-Type", "text/plain")),
                "hit-body".getBytes(StandardCharsets.UTF_8), 60, null, null);

        WireResponse hit = CacheDispatch.synthesizeHit(entry, false);

        assertEquals(200, hit.statusCode());
        assertTrue(java.util.Arrays.equals("hit-body".getBytes(StandardCharsets.UTF_8), hit.body()));
        assertEquals("text/plain", headerValue(hit.headers(), "Content-Type"));
    }

    @Test
    @DisplayName("synthesizeHit: a streaming return re-wraps the byte body as a live stream")
    void streamingHitReWrapsByteBodyAsLiveStream() throws Exception {
        CachedResponse entry = cachedEntry(List.of(), "hit-body".getBytes(StandardCharsets.UTF_8), 60, null, null);

        WireResponse hit = CacheDispatch.synthesizeHit(entry, true);

        assertNull(hit.body());
        assertEquals("hit-body", new String(hit.stream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("synthesizeHit: a body-less entry on a streaming return is returned without streamify")
    void streamingHitWithNullBodyStaysAsIs() {
        CachedResponse entry = cachedEntry(List.of(), null, 60, null, null);

        WireResponse hit = CacheDispatch.synthesizeHit(entry, true);

        assertEquals(200, hit.statusCode());
        assertNull(hit.body());
        assertNull(hit.stream());
    }

    @Test
    @DisplayName("GET 200/301/404: the response is stored, the original instance passes through")
    void getCacheableStatusesAreStored() {
        for (int status : new int[]{200, 301, 404}) {
            reset();
            WireResponse resp = WireResponse.of(status, "body");

            assertSame(resp, CacheDispatch.handle(request(HttpMethod.GET), resp, cache, false));
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

            assertSame(resp, CacheDispatch.handle(request(HttpMethod.GET), resp, cache, false));
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

            assertSame(resp, CacheDispatch.handle(request(HttpMethod.POST), resp, cache, false));
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

            assertSame(resp, CacheDispatch.handle(request(HttpMethod.PUT), resp, cache, false));
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

            assertSame(resp, CacheDispatch.handle(request(method), resp, cache, false));
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
        private CachedResponse getEntry;
        private int getCalls;

        void reset() {
            puts.clear();
            updates.clear();
            invalidations.clear();
            putResponse = null;
            updatedWith = null;
            updateResult = WireResponse.of(200, "cached");
            getEntry = null;
            getCalls = 0;
        }

        @Override
        public Optional<CachedResponse> get(WireRequest request) {
            getCalls++;
            return getEntry == null ? Optional.empty() : Optional.of(getEntry);
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
