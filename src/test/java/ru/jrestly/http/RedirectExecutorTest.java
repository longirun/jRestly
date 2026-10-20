package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD test suite for {@link RedirectExecutor} — the ADR-0092 collaborator extracted from
 * {@link HttpTemplate} as part of god-class decomposition (ADR-0081).
 *
 * <p>Pins the synchronous and asynchronous redirect execution loops, hop budget exhaustion,
 * intermediate stream closing, cross-origin auth header hygiene, and status/method transitions.
 */
class RedirectExecutorTest {

    private static final System.Logger LOGGER = System.getLogger("RedirectExecutorTest");

    private static WireRequest get(String uri) {
        return new WireRequest(
                HttpMethod.GET,
                URI.create(uri),
                List.of(new Header("Authorization", "Bearer ambient"), new Header("X-Custom", "keep")),
                null,
                null);
    }

    private static WireRequest post(String uri) {
        return new WireRequest(
                HttpMethod.POST,
                URI.create(uri),
                List.of(new Header("Authorization", "Bearer ambient")),
                "application/json",
                new byte[] {1, 2, 3});
    }

    private static WireResponse redirect(int status, String location) {
        return new WireResponse(
                status,
                List.of(new Header("Location", location)),
                null,
                null,
                null,
                List.of(),
                null
        );
    }

    private static WireResponse redirectWithStream(int status, String location, InputStream stream) {
        return new WireResponse(
                status,
                List.of(new Header("Location", location)),
                null,
                stream,
                null,
                List.of(),
                null
        );
    }

    private static WireResponse ok(String uri) {
        return new WireResponse(
                200,
                List.of(new Header("X-Final-Uri", uri)),
                new byte[] {42},
                null,
                null,
                List.of(),
                null
        );
    }

    @Test
    @DisplayName("execute sync: no hop budget delegates directly to sender without inspecting response")
    void executeSyncNoBudget() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(null, List.of("Authorization"), LOGGER);
        WireRequest req = get("https://example.com/start");

        WireResponse resp = executor.execute(req, r -> redirect(302, "/redirected"));

        assertEquals(302, resp.statusCode());
    }

    @Test
    @DisplayName("execute sync: single hop redirect to 200 OK")
    void executeSyncSingleHop() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(5, List.of("Authorization"), LOGGER);
        WireRequest req = get("https://example.com/start");

        List<URI> requestedUris = new ArrayList<>();
        WireResponse resp = executor.execute(req, r -> {
            requestedUris.add(r.uri());
            if (r.uri().getPath().equals("/start")) {
                return redirect(302, "/second");
            }
            return ok(r.uri().toString());
        });

        assertEquals(200, resp.statusCode());
        assertEquals(List.of(URI.create("https://example.com/start"), URI.create("https://example.com/second")), requestedUris);
    }

    @Test
    @DisplayName("execute sync: multi-hop redirect stops when 200 OK reached")
    void executeSyncMultiHopSuccess() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/1");

        List<URI> requestedUris = new ArrayList<>();
        WireResponse resp = executor.execute(req, r -> {
            requestedUris.add(r.uri());
            switch (r.uri().getPath()) {
                case "/1" -> { return redirect(301, "/2"); }
                case "/2" -> { return redirect(302, "/3"); }
                default -> { return ok(r.uri().toString()); }
            }
        });

        assertEquals(200, resp.statusCode());
        assertEquals(3, requestedUris.size());
    }

    @Test
    @DisplayName("execute sync: multi-hop stops when hop budget is exhausted")
    void executeSyncExhaustBudget() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(2, List.of(), LOGGER);
        WireRequest req = get("https://example.com/1");

        List<URI> requestedUris = new ArrayList<>();
        WireResponse resp = executor.execute(req, r -> {
            requestedUris.add(r.uri());
            return redirect(302, "/" + (requestedUris.size() + 1));
        });

        // 1 initial request + 2 redirect hops = 3 calls total
        assertEquals(3, requestedUris.size());
        assertEquals(302, resp.statusCode());
    }

    @Test
    @DisplayName("execute sync: stops when Location header is missing on redirect status")
    void executeSyncMissingLocation() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        WireResponse resp = executor.execute(req, r -> WireResponse.of(302));

        assertEquals(302, resp.statusCode());
    }

    @Test
    @DisplayName("execute sync: intermediate response stream is closed between hops")
    void executeSyncClosesIntermediateStream() throws Exception {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        AtomicBoolean streamClosed = new AtomicBoolean(false);
        InputStream closingStream = new ByteArrayInputStream(new byte[] {1, 2}) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        WireResponse resp = executor.execute(req, r -> {
            if (r.uri().getPath().equals("/start")) {
                return redirectWithStream(302, "/final", closingStream);
            }
            return ok("/final");
        });

        assertEquals(200, resp.statusCode());
        assertTrue(streamClosed.get(), "Intermediate stream must be closed on redirect hop");
    }

    @Test
    @DisplayName("execute sync: propagates IOException from sender")
    void executeSyncPropagatesIoException() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        assertThrows(IOException.class, () -> executor.execute(req, r -> {
            throw new IOException("Connection refused");
        }));
    }

    @Test
    @DisplayName("execute sync: propagates InterruptedException from sender")
    void executeSyncPropagatesInterruptedException() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        assertThrows(InterruptedException.class, () -> executor.execute(req, r -> {
            throw new InterruptedException("Thread interrupted");
        }));
    }

    @Test
    @DisplayName("execute async: no hop budget delegates directly to sender")
    void executeAsyncNoBudget() {
        RedirectExecutor executor = new RedirectExecutor(null, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> CompletableFuture.completedFuture(redirect(302, "/next")));

        assertEquals(302, future.join().statusCode());
    }

    @Test
    @DisplayName("execute async: single hop redirect to 200 OK")
    void executeAsyncSingleHop() {
        RedirectExecutor executor = new RedirectExecutor(5, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        List<URI> requestedUris = new ArrayList<>();
        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> {
            requestedUris.add(r.uri());
            if (r.uri().getPath().equals("/start")) {
                return CompletableFuture.completedFuture(redirect(302, "/second"));
            }
            return CompletableFuture.completedFuture(ok(r.uri().toString()));
        });

        assertEquals(200, future.join().statusCode());
        assertEquals(List.of(URI.create("https://example.com/start"), URI.create("https://example.com/second")), requestedUris);
    }

    @Test
    @DisplayName("execute async: multi-hop redirect stops when budget exhausted")
    void executeAsyncExhaustBudget() {
        RedirectExecutor executor = new RedirectExecutor(2, List.of(), LOGGER);
        WireRequest req = get("https://example.com/1");

        List<URI> requestedUris = new ArrayList<>();
        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> {
            requestedUris.add(r.uri());
            return CompletableFuture.completedFuture(redirect(302, "/" + (requestedUris.size() + 1)));
        });

        WireResponse resp = future.join();
        assertEquals(3, requestedUris.size());
        assertEquals(302, resp.statusCode());
    }

    @Test
    @DisplayName("execute async: stops when Location is missing")
    void executeAsyncMissingLocation() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> CompletableFuture.completedFuture(WireResponse.of(302)));

        assertEquals(302, future.join().statusCode());
    }

    @Test
    @DisplayName("execute async: intermediate response stream is closed between hops")
    void executeAsyncClosesIntermediateStream() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        AtomicBoolean streamClosed = new AtomicBoolean(false);
        InputStream closingStream = new ByteArrayInputStream(new byte[] {1, 2}) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> {
            if (r.uri().getPath().equals("/start")) {
                return CompletableFuture.completedFuture(redirectWithStream(302, "/final", closingStream));
            }
            return CompletableFuture.completedFuture(ok("/final"));
        });

        assertEquals(200, future.join().statusCode());
        assertTrue(streamClosed.get());
    }

    @Test
    @DisplayName("execute async: propagates sender failure")
    void executeAsyncPropagatesSenderFailure() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);
        WireRequest req = get("https://example.com/start");

        CompletableFuture<WireResponse> future = executor.executeAsync(req, r -> CompletableFuture.failedFuture(new IllegalStateException("Async boom")));

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("closeIntermediateStream: handles null response and null stream silently")
    void closeIntermediateStreamNullSafe() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);

        assertDoesNotThrow(() -> executor.closeIntermediateStream(null));
        assertDoesNotThrow(() -> executor.closeIntermediateStream(WireResponse.of(200)));
    }

    @Test
    @DisplayName("closeIntermediateStream: catches and logs IOException when stream close fails")
    void closeIntermediateStreamLogsIOException() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);

        InputStream throwingStream = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() throws IOException {
                throw new IOException("Stream close failed");
            }
        };

        assertDoesNotThrow(() -> executor.closeIntermediateStream(redirectWithStream(302, "/b", throwingStream)));
    }

    @Test
    @DisplayName("nextHop: 301/302/303 downgrade to GET and drop body")
    void nextHopDowngrades301302303() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);

        for (int status : List.of(301, 302, 303)) {
            WireRequest next = executor.nextHop(post("https://example.com/a"), redirect(status, "/b"));
            assertNotNull(next);
            assertEquals(HttpMethod.GET, next.method());
            assertNull(next.body());
            assertNull(next.contentType());
            assertEquals(URI.create("https://example.com/b"), next.uri());
        }
    }

    @Test
    @DisplayName("nextHop: 307/308 preserve method and body")
    void nextHopPreserves307308() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of(), LOGGER);

        for (int status : List.of(307, 308)) {
            WireRequest next = executor.nextHop(post("https://example.com/a"), redirect(status, "/b"));
            assertNotNull(next);
            assertEquals(HttpMethod.POST, next.method());
            assertArrayEquals(new byte[] {1, 2, 3}, next.body());
            assertEquals("application/json", next.contentType());
            assertEquals(URI.create("https://example.com/b"), next.uri());
        }
    }

    @Test
    @DisplayName("nextHop: cross-origin drop auth headers, keeps custom headers")
    void nextHopCrossOriginAuthHygiene() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of("Authorization"), LOGGER);
        WireRequest req = get("https://example.com/a");

        WireRequest next = executor.nextHop(req, redirect(302, "https://other.com/b"));
        assertNotNull(next);
        assertTrue(next.headers().stream().noneMatch(h -> h.name().equalsIgnoreCase("Authorization")));
        assertTrue(next.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase("X-Custom")));
    }

    @Test
    @DisplayName("nextHop: same-origin preserves auth headers")
    void nextHopSameOriginPreservesAuth() {
        RedirectExecutor executor = new RedirectExecutor(3, List.of("Authorization"), LOGGER);
        WireRequest req = get("https://example.com/a");

        WireRequest next = executor.nextHop(req, redirect(302, "/b"));
        assertNotNull(next);
        assertTrue(next.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase("Authorization")));
    }

    @Test
    @DisplayName("nextHop: stops when no hop budget or not a redirect status")
    void nextHopStopsWhenNoBudgetOrNotRedirect() {
        RedirectExecutor noBudget = new RedirectExecutor(null, List.of(), LOGGER);
        assertNull(noBudget.nextHop(get("https://example.com/a"), redirect(302, "/b")));

        RedirectExecutor withBudget = new RedirectExecutor(3, List.of(), LOGGER);
        assertNull(withBudget.nextHop(get("https://example.com/a"), WireResponse.of(200)));
        assertNull(withBudget.nextHop(get("https://example.com/a"), WireResponse.of(302)));
    }

    @Test
    @DisplayName("location: returns null for non-redirect status and case-insensitively finds Location header")
    void locationHeaderLookup() {
        assertNull(RedirectExecutor.location(WireResponse.of(200)));
        assertNull(RedirectExecutor.location(WireResponse.of(404)));

        WireResponse withLoc = new WireResponse(
                302,
                List.of(new Header("LOCATION", "https://example.com/target")),
                null, null, null, List.of(), null
        );
        assertEquals("https://example.com/target", RedirectExecutor.location(withLoc));
    }

    @Test
    @DisplayName("isCrossOrigin: validates scheme, host, and effective port")
    void isCrossOriginBranches() {
        URI base = URI.create("https://example.com:443/a");
        URI sameOrigin = URI.create("https://example.com/b");
        URI diffScheme = URI.create("http://example.com/a");
        URI diffHost = URI.create("https://other.com/a");
        URI diffPort = URI.create("https://example.com:8443/a");

        org.junit.jupiter.api.Assertions.assertFalse(RedirectExecutor.isCrossOrigin(base, sameOrigin));
        assertTrue(RedirectExecutor.isCrossOrigin(base, diffScheme));
        assertTrue(RedirectExecutor.isCrossOrigin(base, diffHost));
        assertTrue(RedirectExecutor.isCrossOrigin(base, diffPort));
        assertTrue(RedirectExecutor.isCrossOrigin(URI.create("/rel1"), URI.create("https://example.com/rel2")));
    }

    @Test
    @DisplayName("null logger handles cross-origin drop and exhaustion without NPE")
    void nullLoggerSafety() {
        RedirectExecutor nullLogger = new RedirectExecutor(3, List.of("Authorization"), null);
        WireRequest crossReq = get("https://example.com/a");

        WireRequest next = nullLogger.nextHop(crossReq, redirect(302, "https://other.com/b"));
        assertNotNull(next);
        assertDoesNotThrow(() -> nullLogger.logExhausted(redirect(302, "/b")));
    }

    @Test
    @DisplayName("effectivePort: defaults 443 for https and 80 for http")
    void effectivePortDefaults() {
        assertEquals(443, RedirectExecutor.effectivePort(URI.create("https://example.com/path")));
        assertEquals(80, RedirectExecutor.effectivePort(URI.create("http://example.com/path")));
        assertEquals(8080, RedirectExecutor.effectivePort(URI.create("http://example.com:8080/path")));
        assertEquals(9443, RedirectExecutor.effectivePort(URI.create("https://example.com:9443/path")));
    }
}
