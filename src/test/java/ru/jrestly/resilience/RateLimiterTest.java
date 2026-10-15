package ru.jrestly.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
