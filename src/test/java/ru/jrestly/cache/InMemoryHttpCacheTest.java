package ru.jrestly.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryHttpCacheTest {

    @Test
    @DisplayName("rejects non-positive maxEntries")
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryHttpCache(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryHttpCache(-1));
    }

    @Test
    @DisplayName("basic put and get cache hit with max-age")
    void basicPutAndGet() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/items/1");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        WireResponse res = WireResponse.builder()
                .statusCode(200)
                .header("Cache-Control", "public, max-age=3600")
                .header("ETag", "\"v1\"")
                .body("item 1")
                .build();

        cache.put(req, res);
        assertEquals(1, cache.size());

        Optional<CachedResponse> cachedOpt = cache.get(req);
        assertTrue(cachedOpt.isPresent());
        CachedResponse cached = cachedOpt.get();
        assertEquals(200, cached.statusCode());
        assertArrayEquals("item 1".getBytes(StandardCharsets.UTF_8), cached.body());
        assertTrue(cached.isFresh());
        assertFalse(cached.mustRevalidate());
        assertTrue(cached.hasValidators());
        assertEquals("\"v1\"", cached.etag().orElse(null));

        WireResponse wire = cached.toWireResponse();
        assertEquals(200, wire.statusCode());
        assertArrayEquals("item 1".getBytes(StandardCharsets.UTF_8), wire.body());
        assertTrue(wire.header("Age").isPresent());
    }

    @Test
    @DisplayName("freshness calculation with simulated elapsed time")
    void freshnessCalculation() {
        HttpCache cache = HttpCaches.inMemory(5);
        URI uri = URI.create("https://api.example.com/items/1");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        WireResponse res = WireResponse.builder()
                .statusCode(200)
                .header("Cache-Control", "max-age=60")
                .body("payload")
                .build();

        cache.put(req, res);
        CachedResponse entry = cache.get(req).orElseThrow();

        Instant cachedAt = entry.cachedAt();
        assertTrue(entry.isFresh(cachedAt.plus(30, ChronoUnit.SECONDS)));
        assertFalse(entry.isFresh(cachedAt.plus(65, ChronoUnit.SECONDS)));
    }

    @Test
    @DisplayName("no-store in request or response prevents caching")
    void noStorePreventsCaching() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri = URI.create("https://api.example.com/private");

        // Request with no-store
        WireRequest reqNoStore = new WireRequest(HttpMethod.GET, uri, List.of(new Header("Cache-Control", "no-store")), null, null);
        WireResponse res = WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=100").body("data").build();
        cache.put(reqNoStore, res);
        assertEquals(0, cache.size());

        // Response with no-store
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        WireResponse resNoStore = WireResponse.builder().statusCode(200).header("Cache-Control", "no-store").body("data").build();
        cache.put(req, resNoStore);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("unsafe methods (POST, PUT, DELETE, PATCH) are not cached")
    void unsafeMethodsNotCached() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri = URI.create("https://api.example.com/items");

        WireRequest postReq = new WireRequest(HttpMethod.POST, uri, List.of(), "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        WireResponse res = WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=100").body("data").build();

        cache.put(postReq, res);
        assertEquals(0, cache.size());
        assertTrue(cache.get(postReq).isEmpty());
    }

    @Test
    @DisplayName("update with 304 Not Modified merges headers and retains body")
    void update304NotModified() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri = URI.create("https://api.example.com/user");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        WireResponse initial = WireResponse.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("ETag", "\"tag1\"")
                .header("Cache-Control", "max-age=60")
                .body("{\"id\":1}")
                .build();
        cache.put(req, initial);

        WireResponse response304 = WireResponse.builder()
                .statusCode(304)
                .header("ETag", "\"tag2\"")
                .header("Cache-Control", "max-age=3600")
                .header("X-New-Header", "value")
                .build();

        WireResponse updated = cache.update(req, response304);
        assertNotNull(updated);
        assertEquals(200, updated.statusCode());
        assertArrayEquals("{\"id\":1}".getBytes(StandardCharsets.UTF_8), updated.body());
        assertEquals("application/json", updated.header("Content-Type").orElse(null));
        assertEquals("\"tag2\"", updated.header("ETag").orElse(null));
        assertEquals("max-age=3600", updated.header("Cache-Control").orElse(null));
        assertEquals("value", updated.header("X-New-Header").orElse(null));

        CachedResponse cachedEntry = cache.get(req).orElseThrow();
        assertEquals(3600L, cachedEntry.freshnessLifetimeSeconds());
        assertEquals("\"tag2\"", cachedEntry.etag().orElse(null));
    }

    @Test
    @DisplayName("invalidate removes entry by URI and clear empties cache")
    void invalidateAndClear() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri1 = URI.create("https://api.example.com/items/1");
        URI uri2 = URI.create("https://api.example.com/items/2");

        cache.put(new WireRequest(HttpMethod.GET, uri1, List.of(), null, null),
                WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("1").build());
        cache.put(new WireRequest(HttpMethod.GET, uri2, List.of(), null, null),
                WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("2").build());
        assertEquals(2, cache.size());

        cache.invalidate(uri1);
        assertEquals(1, cache.size());
        assertTrue(cache.get(new WireRequest(HttpMethod.GET, uri1, List.of(), null, null)).isEmpty());
        assertTrue(cache.get(new WireRequest(HttpMethod.GET, uri2, List.of(), null, null)).isPresent());

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("LRU eviction removes eldest entry when capacity is exceeded")
    void lruEviction() {
        HttpCache cache = HttpCaches.inMemory(2);
        URI uri1 = URI.create("https://api.example.com/items/1");
        URI uri2 = URI.create("https://api.example.com/items/2");
        URI uri3 = URI.create("https://api.example.com/items/3");

        cache.put(new WireRequest(HttpMethod.GET, uri1, List.of(), null, null),
                WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("1").build());
        cache.put(new WireRequest(HttpMethod.GET, uri2, List.of(), null, null),
                WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("2").build());
        assertEquals(2, cache.size());

        // Access uri1 to make uri2 the eldest
        cache.get(new WireRequest(HttpMethod.GET, uri1, List.of(), null, null));

        // Insert uri3 -> uri2 should be evicted
        cache.put(new WireRequest(HttpMethod.GET, uri3, List.of(), null, null),
                WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("3").build());
        assertEquals(2, cache.size());

        assertTrue(cache.get(new WireRequest(HttpMethod.GET, uri1, List.of(), null, null)).isPresent());
        assertTrue(cache.get(new WireRequest(HttpMethod.GET, uri2, List.of(), null, null)).isEmpty());
        assertTrue(cache.get(new WireRequest(HttpMethod.GET, uri3, List.of(), null, null)).isPresent());
    }

    @Test
    @DisplayName("Expires and Date headers compute freshness lifetime when max-age is absent")
    void expiresAndDateFreshnessCalculation() {
        InMemoryHttpCache cache = new InMemoryHttpCache(10);
        assertEquals(10, cache.maxEntries());

        URI uri = URI.create("https://api.example.com/legacy-item");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(now);
        String expiresStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(300));

        WireResponse res = WireResponse.builder()
                .statusCode(200)
                .header("Date", dateStr)
                .header("Expires", expiresStr)
                .header("ETag", "\"tag1\"")
                .body("legacy body")
                .build();

        cache.put(req, res);
        assertEquals(1, cache.size());

        CachedResponse cached = cache.get(req).orElseThrow();
        assertEquals(300L, cached.freshnessLifetimeSeconds());
        assertTrue(cached.isFresh());

        // Update 304 with newer Expires header
        String updatedExpiresStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(1800));
        WireResponse res304 = WireResponse.builder()
                .statusCode(304)
                .header("Date", dateStr)
                .header("Expires", updatedExpiresStr)
                .build();

        WireResponse updated = cache.update(req, res304);
        assertNotNull(updated);
        CachedResponse updatedCached = cache.get(req).orElseThrow();
        assertEquals(1800L, updatedCached.freshnessLifetimeSeconds());
    }

    @Test
    @DisplayName("null and invalid arguments handled safely without exceptions")
    void safeNullHandling() {
        InMemoryHttpCache cache = new InMemoryHttpCache();
        assertTrue(cache.get(null).isEmpty());
        assertTrue(cache.get(new WireRequest(HttpMethod.GET, null, List.of(), null, null)).isEmpty());
        assertNull(cache.update(null, null));
        cache.put(null, null);
        cache.invalidate(null);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("update returns null when entry does not exist in cache")
    void updateNonExistentEntry() {
        HttpCache cache = HttpCaches.inMemory();
        WireRequest req = new WireRequest(HttpMethod.GET, URI.create("https://api.example.com/not-cached"), List.of(), null, null);
        WireResponse res304 = WireResponse.builder().statusCode(304).build();
        assertNull(cache.update(req, res304));
    }

    @Test
    @DisplayName("put ignores non-cacheable status, null body, and responses without freshness/validators")
    void putIgnoresNonCacheableResponses() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri = URI.create("https://api.example.com/data");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        // 500 status
        WireResponse res500 = WireResponse.builder().statusCode(500).header("Cache-Control", "max-age=100").body("error").build();
        cache.put(req, res500);
        assertEquals(0, cache.size());

        // null body
        WireResponse resNullBody = WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=100").build();
        cache.put(req, resNullBody);
        assertEquals(0, cache.size());

        // 200 without freshness lifetime and without validators
        WireResponse resNoDirectives = WireResponse.builder().statusCode(200).body("data").build();
        cache.put(req, resNoDirectives);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("invalid Date or Expires header falls back safely without parsing exception")
    void invalidHttpDateFormat() {
        HttpCache cache = HttpCaches.inMemory();
        URI uri = URI.create("https://api.example.com/bad-date");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        WireResponse res = WireResponse.builder()
                .statusCode(200)
                .header("Date", "not-a-valid-date")
                .header("Expires", "another-invalid-date")
                .header("ETag", "\"v1\"")
                .body("content")
                .build();
        cache.put(req, res);
        assertEquals(1, cache.size());
        CachedResponse cached = cache.get(req).orElseThrow();
        assertEquals(0L, cached.freshnessLifetimeSeconds());
    }

    @Test
    @DisplayName("SimpleCachedResponse contract and edge cases: null headers/body/version, validator combinations, existing Age replacement")
    void simpleCachedResponseContractAndEdgeCases() {
        Instant now = Instant.now();
        SimpleCachedResponse resp = new SimpleCachedResponse(
                200,
                null,
                null,
                now,
                -10, // negative freshness clamps to 0
                false,
                false,
                null,
                null,
                null // defaults to HTTP_1_1
        );

        assertEquals(200, resp.statusCode());
        assertEquals(List.of(), resp.headers());
        assertNull(resp.body());
        assertEquals(0L, resp.freshnessLifetimeSeconds());
        assertFalse(resp.hasValidators());
        assertTrue(resp.etag().isEmpty());
        assertTrue(resp.lastModified().isEmpty());
        assertFalse(resp.mustRevalidate());

        WireResponse wire = resp.toWireResponse();
        assertNull(wire.body());
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, wire.version());

        // null cachedAt throws NPE
        assertThrows(NullPointerException.class, () ->
                new SimpleCachedResponse(200, List.of(), new byte[0], null, 60, false, false, null, null, null));

        // validator combinations
        SimpleCachedResponse etagOnly = new SimpleCachedResponse(
                200, List.of(), "b".getBytes(StandardCharsets.UTF_8), now, 60, false, false, "\"etag\"", null, null);
        assertTrue(etagOnly.hasValidators());
        assertEquals("\"etag\"", etagOnly.etag().orElse(null));

        SimpleCachedResponse lastModOnly = new SimpleCachedResponse(
                200, List.of(), "b".getBytes(StandardCharsets.UTF_8), now, 60, false, false, null, "Wed, 21 Oct 2026 07:28:00 GMT", null);
        assertTrue(lastModOnly.hasValidators());
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", lastModOnly.lastModified().orElse(null));

        // replacement of existing Age header
        List<Header> existingHeaders = List.of(new Header("Age", "999"), new Header("Content-Type", "text/plain"));
        SimpleCachedResponse withExistingAge = new SimpleCachedResponse(
                200, existingHeaders, "body".getBytes(StandardCharsets.UTF_8), now.minusSeconds(15), 60, false, false, null, null, null);
        WireResponse wireWithAge = withExistingAge.toWireResponse(now);
        List<Header> ageHeaders = wireWithAge.headers().stream().filter(h -> h.name().equalsIgnoreCase("Age")).toList();
        assertEquals(1, ageHeaders.size());
        assertEquals("15", ageHeaders.getFirst().value());
    }

    @Test
    @DisplayName("301 and 404 GET responses carrying freshness are cacheable")
    void cachesPermanentRedirectAndNotFound() {
        HttpCache cache = HttpCaches.inMemory(10);
        WireRequest movedReq = new WireRequest(HttpMethod.GET, URI.create("https://api.example.com/moved"), List.of(), null, null);
        WireRequest missingReq = new WireRequest(HttpMethod.GET, URI.create("https://api.example.com/missing"), List.of(), null, null);

        cache.put(movedReq, WireResponse.builder()
                .statusCode(301)
                .header("Location", "https://api.example.com/new")
                .header("Cache-Control", "max-age=60")
                .body("moved")
                .build());
        cache.put(missingReq, WireResponse.builder()
                .statusCode(404)
                .header("ETag", "\"missing\"")
                .body("not found")
                .build());

        assertEquals(2, cache.size());
        assertEquals(301, cache.get(movedReq).orElseThrow().statusCode());
        assertEquals(404, cache.get(missingReq).orElseThrow().statusCode());
    }

    @Test
    @DisplayName("Expires without a Date header bases freshness on the receipt time")
    void expiresWithoutDateUsesReceiptTime() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/expires-only");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        String expires = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120));

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Expires", expires)
                .header("ETag", "\"v1\"")
                .body("payload")
                .build());

        CachedResponse cached = cache.get(req).orElseThrow();
        // Second-precision RFC 1123 formatting makes the delta 119-120s off the receipt time.
        assertTrue(cached.freshnessLifetimeSeconds() >= 118 && cached.freshnessLifetimeSeconds() <= 120,
                "expected ~120s, was " + cached.freshnessLifetimeSeconds());
        assertTrue(cached.isFresh());
    }

    @Test
    @DisplayName("a lone Last-Modified response is stored as an always-revalidate validator")
    void lastModifiedOnlyIsStoredAsValidator() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/last-modified-only");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                .body("valueless freshness")
                .build());

        assertEquals(1, cache.size());
        CachedResponse cached = cache.get(req).orElseThrow();
        assertEquals(0L, cached.freshnessLifetimeSeconds());
        assertFalse(cached.isFresh());
        assertTrue(cached.hasValidators());
        assertTrue(cached.lastModified().isPresent());
    }

    @Test
    @DisplayName("response no-cache with a freshness lifetime is stored but never served fresh")
    void responseNoCacheStoredButNotFresh() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/no-cache");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Cache-Control", "no-cache, max-age=60")
                .header("ETag", "\"v1\"")
                .body("payload")
                .build());

        assertEquals(1, cache.size());
        CachedResponse cached = cache.get(req).orElseThrow();
        assertTrue(cached.mustRevalidate());
        assertFalse(cached.isFresh());
    }

    @Test
    @DisplayName("request no-cache poisons the stored entry so it is never served fresh")
    void requestNoCachePreventsFreshServing() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/request-no-cache");
        WireRequest reqNoCache = new WireRequest(HttpMethod.GET, uri,
                List.of(new Header("Cache-Control", "no-cache")), null, null);

        cache.put(reqNoCache, WireResponse.builder()
                .statusCode(200)
                .header("Cache-Control", "max-age=60")
                .header("ETag", "\"v1\"")
                .body("payload")
                .build());

        CachedResponse cached = cache.get(reqNoCache).orElseThrow();
        assertTrue(cached.mustRevalidate());
        assertFalse(cached.isFresh());
    }

    @Test
    @DisplayName("put and update ignore a null response and a request without a URI")
    void putAndUpdateIgnoreNullResponseAndNullRequestUri() {
        InMemoryHttpCache cache = new InMemoryHttpCache(10);
        WireRequest valid = new WireRequest(HttpMethod.GET, URI.create("https://api.example.com/x"), List.of(), null, null);
        WireRequest nullUri = new WireRequest(HttpMethod.GET, null, List.of(), null, null);
        WireResponse validRes = WireResponse.builder().statusCode(200).header("Cache-Control", "max-age=60").body("b").build();

        cache.put(valid, null);
        cache.put(nullUri, validRes);
        assertEquals(0, cache.size());

        assertNull(cache.update(valid, null));
        assertNull(cache.update(nullUri, WireResponse.of(304)));
    }

    @Test
    @DisplayName("update keeps the stored freshness when the 304 carries neither max-age nor Expires")
    void updateWithoutMaxAgeKeepsStoredFreshness() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/keep-freshness");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Cache-Control", "max-age=60")
                .header("ETag", "\"tag1\"")
                .body("body")
                .build());

        WireResponse updated = cache.update(req, WireResponse.builder()
                .statusCode(304)
                .header("ETag", "\"tag2\"")
                .build());

        assertNotNull(updated);
        CachedResponse cached = cache.get(req).orElseThrow();
        assertEquals(60L, cached.freshnessLifetimeSeconds());
        assertEquals("\"tag2\"", cached.etag().orElse(null));
    }

    @Test
    @DisplayName("update recomputes freshness from Expires when neither max-age nor Date is present")
    void updateExpiresWithoutDate() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/update-expires");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);
        String initialExpires = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60));
        String refreshedExpires = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(600));

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Expires", initialExpires)
                .header("ETag", "\"v1\"")
                .body("body")
                .build());

        WireResponse updated = cache.update(req, WireResponse.builder()
                .statusCode(304)
                .header("Expires", refreshedExpires)
                .build());

        assertNotNull(updated);
        CachedResponse cached = cache.get(req).orElseThrow();
        assertTrue(cached.freshnessLifetimeSeconds() >= 598 && cached.freshnessLifetimeSeconds() <= 600,
                "expected ~600s, was " + cached.freshnessLifetimeSeconds());
    }

    @Test
    @DisplayName("update without max-age or Expires keeps the stored freshness lifetime")
    void updateWithoutFreshnessOrExpiresKeepsStoredLifetime() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/update-no-freshness");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("ETag", "\"tag1\"")
                .header("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                .body("body")
                .build());

        WireResponse updated = cache.update(req, WireResponse.builder()
                .statusCode(304)
                .header("ETag", "\"tag2\"")
                .build());

        assertNotNull(updated);
        CachedResponse cached = cache.get(req).orElseThrow();
        assertEquals(0L, cached.freshnessLifetimeSeconds());
        assertEquals("\"tag2\"", cached.etag().orElse(null));
        assertTrue(cached.lastModified().isPresent());
    }

    @Test
    @DisplayName("blank Date and Expires header values are treated as absent")
    void blankHttpDateIsTreatedAsAbsent() {
        HttpCache cache = HttpCaches.inMemory(10);
        URI uri = URI.create("https://api.example.com/blank-date");
        WireRequest req = new WireRequest(HttpMethod.GET, uri, List.of(), null, null);

        cache.put(req, WireResponse.builder()
                .statusCode(200)
                .header("Date", "   ")
                .header("Expires", "   ")
                .header("ETag", "\"v1\"")
                .body("body")
                .build());

        assertEquals(1, cache.size());
        assertEquals(0L, cache.get(req).orElseThrow().freshnessLifetimeSeconds());
    }

    @Test
    @DisplayName("a noCache entry is never fresh and reports mustRevalidate; an explicit flag does too")
    void noCacheAndExplicitMustRevalidateFlags() {
        Instant now = Instant.now();
        byte[] body = "b".getBytes(StandardCharsets.UTF_8);

        SimpleCachedResponse noCache = new SimpleCachedResponse(
                200, List.of(), body, now, 3600, true, false, "\"e\"", null, null);
        assertFalse(noCache.isFresh(now));
        assertTrue(noCache.mustRevalidate());

        SimpleCachedResponse explicit = new SimpleCachedResponse(
                200, List.of(), body, now, 3600, false, true, "\"e\"", null, null);
        assertTrue(explicit.mustRevalidate());
        assertTrue(explicit.isFresh(now));
    }
}


