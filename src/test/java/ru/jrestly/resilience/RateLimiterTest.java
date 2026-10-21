package ru.jrestly.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the token-bucket {@link RateLimiter} (ADR-0043): the burst
 * limit per refresh period, refill over time, bounded waiting through
 * {@code tryAcquire(timeout)} (deadline-based budget seam + contested-refill
 * guard), fail-fast {@link RateLimitExceededException} and the name-keyed
 * {@link RateLimiterRegistry}. Pure unit tests, no HTTP.
 */
class RateLimiterTest {

    @Test
    @DisplayName("TokenBucket permits requests up to configured limit")
    void permitsUpToLimit() {
        RateLimiter limiter = RateLimiter.of("test-limiter", 3, Duration.ofSeconds(1));
        assertEquals("test-limiter", limiter.name());
        assertEquals(3, limiter.metrics().availablePermissions());

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());

        // 4th call exceeds limit immediately
        assertFalse(limiter.tryAcquire());

        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class, limiter::acquire);
        assertEquals("test-limiter", ex.rateLimiterName());
    }

    @Test
    @DisplayName("tryAcquire(null) treats a null timeout as an immediate grant/decline")
    void nullTimeoutBehavesAsZeroWait() {
        RateLimiter limiter = RateLimiter.of("null-timeout-test", 1, Duration.ofSeconds(60));

        // Token available: the null timeout grants immediately without waiting.
        assertTrue(limiter.tryAcquire(null));
        // Token exhausted: the zero wait budget must decline immediately too
        // (60s refill period makes the residual budget nil at this timestamp).
        assertFalse(limiter.tryAcquire((Duration) null));
    }

    @Test
    @DisplayName("Pre-interrupted thread abandons the wait and restores the interrupt flag")
    void preInterruptedTryAcquireFailsFastAndRestoresFlag() {
        RateLimiter limiter = RateLimiter.of("interrupted-test", 1, Duration.ofSeconds(60));
        assertTrue(limiter.tryAcquire()); // drains the bucket: acquisition must wait

        Thread.currentThread().interrupt(); // pre-set the interrupt flag
        try {
            assertFalse(limiter.tryAcquire(Duration.ofMillis(500)));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted(); // clear flag so the flag stays clean for other tests
        }
    }

    @Test
    @DisplayName("TokenBucket refills tokens over time")
    void refillsTokensOverTime() throws InterruptedException {
        RateLimiter limiter = RateLimiter.of("refill-test", 2, Duration.ofMillis(100));

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait for refill period
        Thread.sleep(120);

        assertTrue(limiter.tryAcquire());
    }

    @Test
    @DisplayName("TokenBucket waits up to timeout duration to acquire permission")
    void waitsUpToTimeout() {
        RateLimiter limiter = RateLimiter.of("wait-test", RateLimiterConfig.builder()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(80))
                .timeoutDuration(Duration.ofMillis(150))
                .build());

        assertTrue(limiter.tryAcquire());
        // Immediate acquire fails
        assertFalse(limiter.tryAcquire(Duration.ZERO));

        // Acquire with sufficient timeout succeeds after waiting
        assertTrue(limiter.tryAcquire(Duration.ofMillis(150)));
    }

    @Test
    @DisplayName("RateLimiterRegistry caches and provides instances by name")
    void registryCachesByName() {
        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        RateLimiter r1 = registry.getOrCreate("llm");
        RateLimiter r2 = registry.getOrCreate("llm");

        assertSame(r1, r2);
        assertSame(r1, registry.get("llm"));

        RateLimiter custom = registry.getOrCreate("custom", RateLimiterConfig.builder()
                .limitForPeriod(50)
                .build());
        assertEquals(50, custom.config().limitForPeriod());
    }

    @Test
    @DisplayName("remainingBudget computes the wait budget against an absolute deadline")
    void remainingBudgetTracksDeadline() {
        long oneSecondNanos = 1_000_000_000L;
        long start = 10_000_000_000L;
        long deadline = start + 150_000_000L;

        assertEquals(150_000_000L, RateLimiters.remainingBudget(deadline, start),
                "budget at the start of the wait equals the full timeout");
        assertEquals(50_000_000L, RateLimiters.remainingBudget(deadline, start + 100_000_000L),
                "budget shrinks by the wall-clock time already spent waiting");
        assertEquals(0L, RateLimiters.remainingBudget(deadline, deadline),
                "budget is zero once the deadline is reached");
        assertEquals(0L, RateLimiters.remainingBudget(deadline, deadline + 25_000_000L),
                "budget is clamped to zero when the deadline was overshot");
        assertEquals(oneSecondNanos, RateLimiters.remainingBudget(deadline + oneSecondNanos, deadline),
                "a later deadline keeps its full remaining budget");
    }

    @Test
    @DisplayName("tryAcquire(timeout) waits at most the timeout even when refilled tokens are contested")
    void timeoutIsHardBoundUnderContestedRefill() throws Exception {
        // Refill yields one token every 100 ms (burst cap 2). Main consumes the
        // burst, then releases two waiters that race for the single token that
        // refills within their shared 150 ms window: at most one of them can
        // legitimately succeed, and no call may block longer than the timeout
        // (plus scheduling margin).
        RateLimiter limiter = RateLimiter.of("contested", RateLimiterConfig.builder()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofMillis(200))
                .timeoutDuration(Duration.ZERO)
                .build());

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        Duration timeout = Duration.ofMillis(150);
        // Main is the third barrier party: the bucket is drained just before
        // the waiters' timeout windows start, so the refill clock and the
        // windows share one origin.
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AcquireOutcome> first = pool.submit(
                    () -> raceForToken(limiter, timeout, start));
            Future<AcquireOutcome> second = pool.submit(
                    () -> raceForToken(limiter, timeout, start));
            start.await(2, TimeUnit.SECONDS);
            AcquireOutcome a = first.get(2, TimeUnit.SECONDS);
            AcquireOutcome b = second.get(2, TimeUnit.SECONDS);

            assertTrue(a.acquired() ^ b.acquired(),
                    "exactly one of the two waiters may acquire the single refilled token, but got: "
                            + a + ", " + b);
            long marginMillis = 30;
            assertTrue(a.elapsedMillis() <= timeout.toMillis() + marginMillis,
                    "first waiter blocked " + a.elapsedMillis() + " ms beyond allowed "
                            + timeout.toMillis() + " ms timeout");
            assertTrue(b.elapsedMillis() <= timeout.toMillis() + marginMillis,
                    "second waiter blocked " + b.elapsedMillis() + " ms beyond allowed "
                            + timeout.toMillis() + " ms timeout");
        } finally {
            pool.shutdownNow();
        }
    }

    private AcquireOutcome raceForToken(RateLimiter limiter, Duration timeout, CyclicBarrier start)
            throws Exception {
        start.await(2, TimeUnit.SECONDS);
        long startNanos = System.nanoTime();
        boolean acquired = limiter.tryAcquire(timeout);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new AcquireOutcome(acquired, elapsedMillis);
    }

    @Test
    @DisplayName("RateLimiterConfig validates constructor parameters and builder methods")
    void rateLimiterConfigValidationAndBuilder() {
        RateLimiterConfig defaults = RateLimiterConfig.ofDefaults();
        assertEquals(RateLimiterConfig.DEFAULT_LIMIT_FOR_PERIOD, defaults.limitForPeriod());
        assertEquals(RateLimiterConfig.DEFAULT_LIMIT_REFRESH_PERIOD, defaults.limitRefreshPeriod());
        assertEquals(RateLimiterConfig.DEFAULT_TIMEOUT_DURATION, defaults.timeoutDuration());

        // Custom builder
        RateLimiterConfig custom = RateLimiterConfig.builder()
                .limitForPeriod(25)
                .limitRefreshPeriod(Duration.ofSeconds(2))
                .timeoutDuration(Duration.ofMillis(500))
                .build();
        assertEquals(25, custom.limitForPeriod());
        assertEquals(Duration.ofSeconds(2), custom.limitRefreshPeriod());
        assertEquals(Duration.ofMillis(500), custom.timeoutDuration());

        // Parameter validations
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimiterConfig(0, Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimiterConfig(-5, Duration.ofSeconds(1), Duration.ZERO));

        assertThrows(NullPointerException.class, () ->
                new RateLimiterConfig(10, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimiterConfig(10, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimiterConfig(10, Duration.ofMillis(-100), Duration.ZERO));

        assertThrows(NullPointerException.class, () ->
                new RateLimiterConfig(10, Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimiterConfig(10, Duration.ofSeconds(1), Duration.ofMillis(-1)));

        // Builder null checks
        assertThrows(NullPointerException.class, () ->
                RateLimiterConfig.builder().limitRefreshPeriod(null));
        assertThrows(NullPointerException.class, () ->
                RateLimiterConfig.builder().timeoutDuration(null));
    }

    @Test
    @DisplayName("tryAcquire(timeout) restores interrupt status and returns false when interrupted")
    void tryAcquireInterruptedRestoresFlagAndReturnsFalse() throws Exception {
        RateLimiter limiter = RateLimiter.of("interrupt-test", 1, Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire()); // consume single token

        Thread worker = new Thread(() -> {
            boolean acquired = limiter.tryAcquire(Duration.ofSeconds(5));
            assertFalse(acquired);
            assertTrue(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(50);
        worker.interrupt();
        worker.join(2000);
        assertFalse(worker.isAlive());
    }

    @Test
    @DisplayName("refillAt with zero or negative elapsed nanos is safe no-op")
    void refillAtNonPositiveElapsedIsNoOp() {
        RateLimiter limiter = RateLimiter.of("zero-elapsed-test", 2, Duration.ofSeconds(1));
        long now = System.nanoTime();
        // Invoke with past timestamp (elapsed <= 0)
        RateLimiters.refillAt(limiter, now - 1_000_000L);
        assertEquals(2, limiter.metrics().availablePermissions());

        // Invoke with dummy custom limiter
        RateLimiter custom = new RateLimiter() {
            @Override public String name() { return "custom"; }
            @Override public boolean tryAcquire(Duration timeout) { return true; }
            @Override public RateLimiterConfig config() { return RateLimiterConfig.ofDefaults(); }
            @Override public RateLimiterMetrics metrics() { return new RateLimiterMetrics(1, 0); }
        };
        RateLimiters.refillAt(custom, now);
    }

    private record AcquireOutcome(boolean acquired, long elapsedMillis) {}
}

