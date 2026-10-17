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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TokenManager} (ADR-0046): single-flight acquisition
 * under a 50-thread stampede, cache reuse until expiration or invalidation,
 * the refresh buffer, failure propagation with a subsequent retry, and the
 * helper factories with their null validation. Pure unit tests, no HTTP.
 */
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

    @Test
    @DisplayName("A finishing leader clears only its own in-flight future, never a newer acquisition")
    void finishingLeaderDoesNotClobberNewerInFlightAcquisition() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);

        TokenManager tokenManager = TokenManager.of(() -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                leaderEntered.countDown();
                await(releaseLeader);
                // Already expired: forces later callers back through acquireToken().
                return Token.of("tok-1", Duration.ofSeconds(-1));
            }
            if (n == 2) {
                secondEntered.countDown();
                await(releaseSecond);
                return Token.of("tok-2", Duration.ofMinutes(10));
            }
            thirdEntered.countDown();
            return Token.of("tok-3", Duration.ofMinutes(10));
        });

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<String> leader = executor.submit(tokenManager::getToken);
            assertTrue(leaderEntered.await(5, TimeUnit.SECONDS));

            // Invalidate while the leader is acquiring, then let a second caller
            // install a fresh in-flight future (the leader's future is orphaned).
            tokenManager.invalidate();
            Future<String> second = executor.submit(tokenManager::getToken);
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));

            // The leader finishes. It must clear only its own future, not the newer one.
            releaseLeader.countDown();
            assertEquals("tok-1", leader.get(5, TimeUnit.SECONDS));

            // A third caller must join the still-in-flight second acquisition,
            // not start a brand new supplier invocation.
            Future<String> third = executor.submit(tokenManager::getToken);
            assertFalse(thirdEntered.await(300, TimeUnit.MILLISECONDS),
                    "single-flight broken: a third acquisition started while one was still in flight");

            releaseSecond.countDown();
            assertEquals("tok-2", second.get(5, TimeUnit.SECONDS));
            assertEquals("tok-2", third.get(5, TimeUnit.SECONDS));
            assertEquals(2, invocations.get());
        } finally {
            releaseSecond.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting on latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SingleFlightTokenManager throws IllegalStateException when supplier returns null")
    void supplierReturningNullThrowsIllegalStateException() {
        TokenManager tm = TokenManager.of(() -> null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, tm::getToken);
        assertTrue(ex.getMessage().contains("returned null Token"));
    }

    @Test
    @DisplayName("SingleFlightTokenManager propagates Errors directly")
    void supplierThrowingErrorPropagatesError() {
        TokenManager tm = TokenManager.of(() -> {
            throw new AssertionError("assertion failed");
        });
        assertThrows(AssertionError.class, tm::getToken);
    }

    @Test
    @DisplayName("SingleFlightTokenManager accepts null refreshBuffer defaulting to ZERO")
    void nullRefreshBufferDefaultsToZero() {
        TokenManager tm = new SingleFlightTokenManager(() -> Token.of("token-val", Duration.ofMinutes(5)), null);
        assertEquals("token-val", tm.getToken());

        assertThrows(NullPointerException.class, () -> TokenManager.builder().refreshBuffer(null));
    }

    @Test
    @DisplayName("TokenManager.Builder stringSupplier configures TTL and validates nulls")
    void builderStringSupplierAndPerpetualNullChecks() {
        TokenManager tm = TokenManager.builder()
                .stringSupplier(() -> "string-token-abc", Duration.ofMinutes(15))
                .refreshBuffer(Duration.ofSeconds(10))
                .build();
        assertEquals("string-token-abc", tm.getToken());

        // Null checks for stringSupplier
        assertThrows(NullPointerException.class, () ->
                TokenManager.builder().stringSupplier(null, Duration.ofMinutes(5)));
        assertThrows(NullPointerException.class, () ->
                TokenManager.builder().stringSupplier(() -> "val", null));

        // Null checks for ofPerpetual
        assertThrows(NullPointerException.class, () -> TokenManager.ofPerpetual(null));
    }
}


