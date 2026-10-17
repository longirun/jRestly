package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.cache.HttpCache;
import ru.jrestly.cache.HttpCaches;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock integration tests for RFC 9111 HTTP Cache SPI + In-Memory Store (ADR-0063).
 */
class HttpCacheWireMockTest extends BaseWireMockTest {

    private final List<AbstractHttpClient> createdClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDownCreatedClients() {
        for (AbstractHttpClient c : createdClients) {
            c.close();
        }
        createdClients.clear();
    }

    private JRestlyClient createCachedClient(HttpCache cache) {
        JRestlyClient newClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .httpCache(cache)
                .build();
        createdClients.add(newClient);
        return newClient;
    }

    private JRestlyClient createCachedClient(HttpCache cache, ClientMetricsListener metricsListener) {
        JRestlyClient newClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .httpCache(cache)
                .metricsListener(metricsListener)
                .build();
        createdClients.add(newClient);
        return newClient;
    }

    public interface CachedResourceClient {

        @Get(path = "/data")
        String getData();

        @Get(path = "/data")
        String getDataWithHeaders(ResponseHeaders headers);

        @Get(path = "/data")
        String getDataWithCacheControl(@RequestHeader(name = "Cache-Control") String cacheControl);

        @Get(path = "/data/async")
        CompletableFuture<String> getDataAsync(ResponseHeaders headers);

        @Get(path = "/data/bytes")
        byte[] getBytes();

        @Get(path = "/data/stream")
        byte[] getStreamDataBytes();

        @Get(path = "/data/stream")
        InputStream getStream();

        @Get(path = "/data/stream-reval")
        byte[] getStreamRevalBytes();

        @Get(path = "/data/stream-reval")
        InputStream getStreamReval(@RequestHeader(name = "Cache-Control") String cacheControl);

        @Post(path = "/data")
        String mutateData();

        @Delete(path = "/data")
        void deleteData();
    }

    @Test
    @DisplayName("Cache HIT: serves response from memory without touching network, Age header present")
    void cacheHitServesFromMemoryWithoutTouchingServer() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withHeader("ETag", "\"tag-1\"")
                        .withBody("hello-cached")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        ResponseHeaders h1 = new ResponseHeaders();
        String res1 = testClient.getDataWithHeaders(h1);
        assertEquals("hello-cached", res1);
        assertEquals(200, h1.statusCode());
        assertEquals(1, cache.size());

        ResponseHeaders h2 = new ResponseHeaders();
        String res2 = testClient.getDataWithHeaders(h2);
        assertEquals("hello-cached", res2);
        assertEquals(200, h2.statusCode());
        assertTrue(h2.age().isPresent(), "Age header must be present on cached response");

        verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Expired entry revalidates with If-None-Match and updates on 304 Not Modified")
    void expiredEntryRevalidatesWithIfNoneMatchAndUpdatesOn304() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=0")
                        .withHeader("ETag", "\"tag-v1\"")
                        .withBody("initial-payload")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        String first = testClient.getData();
        assertEquals("initial-payload", first);
        assertEquals(1, cache.size());

        stubFor(get(urlEqualTo("/data"))
                .withHeader("If-None-Match", equalTo("\"tag-v1\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"tag-v1\"")
                        .withHeader("Cache-Control", "max-age=120")));

        ResponseHeaders h2 = new ResponseHeaders();
        String second = testClient.getDataWithHeaders(h2);
        assertEquals("initial-payload", second, "Cached payload returned transparently on 304");
        assertEquals(200, h2.statusCode());
        assertTrue(h2.isSuccess());

        verify(1, getRequestedFor(urlEqualTo("/data")).withoutHeader("If-None-Match"));
        verify(1, getRequestedFor(urlEqualTo("/data")).withHeader("If-None-Match", equalTo("\"tag-v1\"")));
    }

    @Test
    @DisplayName("Revalidation on server modification returns new 200 OK and updates cache")
    void revalidationOnServerModificationReturnsNew200() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=0")
                        .withHeader("ETag", "\"tag-v1\"")
                        .withBody("version-1")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        assertEquals("version-1", testClient.getData());

        stubFor(get(urlEqualTo("/data"))
                .withHeader("If-None-Match", equalTo("\"tag-v1\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withHeader("ETag", "\"tag-v2\"")
                        .withBody("version-2")));

        assertEquals("version-2", testClient.getData());

        // Third call is fresh within 60s, hits cache
        assertEquals("version-2", testClient.getData());
        verify(2, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Request Cache-Control: no-cache forces revalidation even when entry is fresh")
    void requestCacheControlNoCacheForcesRevalidation() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=3600")
                        .withHeader("ETag", "\"tag-static\"")
                        .withBody("static-data")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        assertEquals("static-data", testClient.getData());

        stubFor(get(urlEqualTo("/data"))
                .withHeader("If-None-Match", equalTo("\"tag-static\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"tag-static\"")));

        String result = testClient.getDataWithCacheControl("no-cache");
        assertEquals("static-data", result);
        verify(2, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Request Cache-Control: no-store bypasses cache entirely")
    void requestCacheControlNoStoreBypassesCache() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=3600")
                        .withBody("ephemeral")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        String result = testClient.getDataWithCacheControl("no-store");
        assertEquals("ephemeral", result);
        assertEquals(0, cache.size(), "Cache-Control: no-store on request prevents caching");
    }

    @Test
    @DisplayName("Response Cache-Control: no-store is not stored in cache")
    void responseCacheControlNoStoreNotCached() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "no-store")
                        .withBody("do-not-cache")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        assertEquals("do-not-cache", testClient.getData());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("Unsafe method (POST/DELETE) invalidates cached entry on same URI")
    void unsafeMethodInvalidatesCachedEntry() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withHeader("ETag", "\"tag-1\"")
                        .withBody("pre-mutation")));

        stubFor(post(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("mutated")));

        stubFor(delete(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(204)));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        assertEquals("pre-mutation", testClient.getData());
        assertEquals(1, cache.size());

        // Mutate via POST -> invalidates cache
        testClient.mutateData();
        assertEquals(0, cache.size(), "Cache entry must be invalidated after POST");

        // Next GET hits network again
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withBody("post-mutation")));

        assertEquals("post-mutation", testClient.getData());
        assertEquals(1, cache.size());

        // Invalidate via DELETE
        testClient.deleteData();
        assertEquals(0, cache.size(), "Cache entry must be invalidated after DELETE");
    }

    @Test
    @DisplayName("Async CompletableFuture twin: cache hit resolves without network call and carries Age header")
    void asyncCompletableFutureCacheHit() {
        stubFor(get(urlEqualTo("/data/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withBody("async-content")));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        ResponseHeaders h1 = new ResponseHeaders();
        String res1 = testClient.getDataAsync(h1).join();
        assertEquals("async-content", res1);
        assertEquals(1, cache.size());

        ResponseHeaders h2 = new ResponseHeaders();
        String res2 = testClient.getDataAsync(h2).join();
        assertEquals("async-content", res2);
        assertTrue(h2.age().isPresent());

        verify(1, getRequestedFor(urlEqualTo("/data/async")));
    }

    @Test
    @DisplayName("byte[] return type: cached payload is preserved and served as byte[]")
    void byteArrayReturnHitFromCache() {
        stubFor(get(urlEqualTo("/data/bytes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Cache-Control", "max-age=60")
                        .withBody("streamed-bytes".getBytes(StandardCharsets.UTF_8))));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        byte[] b1 = testClient.getBytes();
        assertNotNull(b1);
        assertEquals("streamed-bytes", new String(b1, StandardCharsets.UTF_8));

        byte[] b2 = testClient.getBytes();
        assertNotNull(b2);
        assertEquals("streamed-bytes", new String(b2, StandardCharsets.UTF_8));

        verify(1, getRequestedFor(urlEqualTo("/data/bytes")));
    }

    @Test
    @DisplayName("ClientMetricsListener: cache HIT reports attempts = 0")
    void clientMetricsAttemptsReflectsCacheHit() {
        stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Cache-Control", "max-age=60")
                        .withBody("metrics-data")));

        AtomicInteger lastAttempts = new AtomicInteger(-1);
        ClientMetricsListener listener = metrics -> lastAttempts.set(metrics.attempts());

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache, listener).get(CachedResourceClient.class);

        testClient.getData();
        assertEquals(1, lastAttempts.get(), "First call over wire has attempts = 1");

        testClient.getData();
        assertEquals(0, lastAttempts.get(), "Cache HIT reports attempts = 0");
    }

    @Test
    @DisplayName("InputStream return type: cache hit transforms cached bytes into fresh InputStream")
    void streamReturnHitFromCache() throws IOException {
        stubFor(get(urlEqualTo("/data/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Cache-Control", "max-age=60")
                        .withBody("stream-cache-bytes".getBytes(StandardCharsets.UTF_8))));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        // First call populates cache
        byte[] initial = testClient.getStreamDataBytes();
        assertNotNull(initial);
        assertEquals("stream-cache-bytes", new String(initial, StandardCharsets.UTF_8));
        assertEquals(1, cache.size());

        // Second call is Cache HIT for streaming method -> triggers toStreamWireResponse(cachedResp)!
        try (InputStream in = testClient.getStream()) {
            assertNotNull(in);
            assertEquals("stream-cache-bytes", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        verify(1, getRequestedFor(urlEqualTo("/data/stream")));
    }

    @Test
    @DisplayName("InputStream return type: conditional 304 revalidation transforms updated cached bytes into fresh InputStream")
    void streamReturnRevalidation304FromCache() throws IOException {
        stubFor(get(urlEqualTo("/data/stream-reval"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Cache-Control", "max-age=1")
                        .withHeader("ETag", "\"tag-reval\"")
                        .withBody("stream-reval-bytes".getBytes(StandardCharsets.UTF_8))));

        HttpCache cache = HttpCaches.inMemory();
        CachedResourceClient testClient = createCachedClient(cache).get(CachedResourceClient.class);

        // First call populates cache with ETag
        byte[] initial = testClient.getStreamRevalBytes();
        assertEquals("stream-reval-bytes", new String(initial, StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/data/stream-reval"))
                .withHeader("If-None-Match", equalTo("\"tag-reval\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"tag-reval\"")));

        // Revalidation with 304 -> triggers toStreamWireResponse(updated)!
        try (InputStream in2 = testClient.getStreamReval("no-cache")) {
            assertEquals("stream-reval-bytes", new String(in2.readAllBytes(), StandardCharsets.UTF_8));
        }

        verify(2, getRequestedFor(urlEqualTo("/data/stream-reval")));
    }
}
