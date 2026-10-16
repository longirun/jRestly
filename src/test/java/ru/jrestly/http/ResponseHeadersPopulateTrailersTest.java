package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseHeadersPopulateTrailersTest {

    @Test
    @DisplayName("populateTrailers safely updates trailers, firstTrailer, and trailerValues")
    void populateTrailersUpdatesMetadata() {
        ResponseHeaders headers = new ResponseHeaders();
        headers.populate(200, List.of(new Header("Trailer", "Server-Timing, X-Checksum")), Duration.ofMillis(10));

        assertTrue(headers.trailers().isEmpty());
        assertEquals(Optional.empty(), headers.firstTrailer("Server-Timing"));
        assertEquals(List.of("Server-Timing", "X-Checksum"), headers.declaredTrailers());

        List<Header> trailers = List.of(
                new Header("Server-Timing", "app;dur=25.4"),
                new Header("X-Checksum", "a1b2c3d4"),
                new Header("X-Multi", "v1"),
                new Header("X-Multi", "v2")
        );

        headers.populateTrailers(trailers);

        assertEquals(4, headers.trailers().size());
        assertEquals(Optional.of("app;dur=25.4"), headers.firstTrailer("Server-Timing"));
        assertEquals(Optional.of("app;dur=25.4"), headers.firstTrailer("server-timing"));
        assertEquals(Optional.of("a1b2c3d4"), headers.firstTrailer("x-checksum"));
        assertEquals(List.of("v1", "v2"), headers.trailerValues("x-multi"));
    }

    @Test
    @DisplayName("populateTrailers is idempotent and ignores null or empty lists")
    void populateTrailersIdempotence() {
        ResponseHeaders headers = new ResponseHeaders();
        headers.populate(200, List.of(), Duration.ZERO);

        headers.populateTrailers(null);
        assertTrue(headers.trailers().isEmpty());

        headers.populateTrailers(List.of());
        assertTrue(headers.trailers().isEmpty());

        List<Header> trailers = List.of(new Header("X-T", "val1"));
        headers.populateTrailers(trailers);
        assertEquals(Optional.of("val1"), headers.firstTrailer("X-T"));

        // Idempotent re-invocation
        headers.populateTrailers(trailers);
        assertEquals(Optional.of("val1"), headers.firstTrailer("X-T"));
    }

    @Test
    @DisplayName("populateTrailers is thread-safe under concurrent invocation")
    void populateTrailersThreadSafety() throws InterruptedException {
        ResponseHeaders headers = new ResponseHeaders();
        headers.populate(200, List.of(), Duration.ZERO);

        int threads = 16;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        headers.populateTrailers(List.of(new Header("X-Trailer-" + idx, "value-" + idx)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
        }

        // At least one set of trailers was populated without throwing exceptions or corrupting state
        assertTrue(!headers.trailers().isEmpty());
    }
}
