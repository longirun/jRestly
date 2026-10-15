package ru.jrestly.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenManagerTest {

    @Test
    @DisplayName("Single-flight acquisition: 50 concurrent threads trigger underlying supplier exactly once")
    void singleFlightConcurrentStampede() throws Exception {
        AtomicInteger supplierInvocations = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);

        TokenManager tokenManager = TokenManager.of(() -> {
            supplierInvocations.incrementAndGet();
            try {
                // Simulate network latency to ensure threads overlap
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Token.of("jwt-token-123", Duration.ofMinutes(10));
        });

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return tokenManager.getToken();
            }));
        }

        startLatch.countDown();

        for (Future<String> future : futures) {
            String token = future.get(5, TimeUnit.SECONDS);
            assertEquals("jwt-token-123", token);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Exactly one supplier execution occurred!
        assertEquals(1, supplierInvocations.get());
    }

    @Test
    @DisplayName("Cached token is reused until expiration or invalidation")
    void cacheReuseAndInvalidation() {
        AtomicInteger counter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> Token.of("tok-" + counter.incrementAndGet()));

        assertEquals("tok-1", tokenManager.getToken());
        assertEquals("tok-1", tokenManager.getToken());
        assertEquals(1, counter.get());

        tokenManager.invalidate();
        assertEquals("tok-2", tokenManager.getToken());
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("Refresh buffer forces early renewal when token is near expiration")
    void refreshBufferEarlyRenewal() {
        AtomicInteger counter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.builder()
                .supplier(() -> {
                    int c = counter.incrementAndGet();
                    // First token valid for 2 seconds, second token valid for 60 seconds
                    return Token.of("tok-" + c, c == 1 ? Duration.ofSeconds(2) : Duration.ofSeconds(60));
                })
                .refreshBuffer(Duration.ofSeconds(5)) // buffer 5s > 2s TTL
                .build();

        // First call gets tok-1, but because 2s < 5s buffer, next call immediately refreshes to tok-2
        assertEquals("tok-1", tokenManager.getToken());
        assertEquals("tok-2", tokenManager.getToken());
        assertEquals("tok-2", tokenManager.getToken()); // tok-2 has 60s TTL > 5s buffer, so stays cached
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("Supplier failure propagates exception and allows subsequent retry")
    void supplierFailureAndRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Auth server unavailable");
            }
            return Token.of("recovered-token");
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, tokenManager::getToken);
        assertEquals("Auth server unavailable", ex.getMessage());

        // Subsequent attempt succeeds
        assertEquals("recovered-token", tokenManager.getToken());
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("TokenManager helper factories and validation")
    void helperFactories() {
        TokenManager tm1 = TokenManager.ofPerpetual(() -> "perpetual-1");
        assertEquals("perpetual-1", tm1.getToken());

        TokenManager tm2 = TokenManager.of(() -> "ttl-2", Duration.ofMinutes(5));
        assertEquals("ttl-2", tm2.getToken());

        assertThrows(IllegalStateException.class, () -> TokenManager.builder().build());
        assertThrows(NullPointerException.class, () -> TokenManager.of(null));
        assertThrows(NullPointerException.class, () -> TokenManager.of(null, Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class, () -> TokenManager.of(() -> "val", null));
    }
}
