package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Jitter} strategies, exponential multiplier, and
 * Retry-After capping in {@link RetryPolicy} (ADR-0040).
 */
class RetryPolicyJitterTest {

    private static Random maxDraw() {
        return new Random() {
            @Override
            public long nextLong(long bound) {
                return bound - 1;
            }
        };
    }

    private static Random minDraw() {
        return new Random() {
            @Override
            public long nextLong(long bound) {
                return 0;
            }
        };
    }

    @Test
    @DisplayName("defaults: Jitter.FULL, multiplier 2.0, respectRetryAfter true, maxRetryAfter null")
    void defaultsReflectAdr0040() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(Jitter.FULL, policy.getJitter());
        assertEquals(2.0, policy.getBackoffMultiplier(), 0.0001);
        assertTrue(policy.isRespectRetryAfter());
        assertNull(policy.getMaxRetryAfter());
    }

    @Test
    @DisplayName("builder sets all ADR-0040 fields accurately")
    void builderConfiguresJitterAndRetryAfterFields() {
        RetryPolicy policy = RetryPolicy.builder()
                .jitter(Jitter.EQUAL)
                .backoffMultiplier(1.5)
                .respectRetryAfter(false)
                .maxRetryAfter(Duration.ofSeconds(10))
                .build();

        assertEquals(Jitter.EQUAL, policy.getJitter());
        assertEquals(1.5, policy.getBackoffMultiplier(), 0.0001);
        assertFalse(policy.isRespectRetryAfter());
        assertEquals(Duration.ofSeconds(10), policy.getMaxRetryAfter());
    }

    @Test
    @DisplayName("builder validates multiplier >= 1.0, non-negative maxRetryAfter, non-null jitter")
    void builderValidatesFields() {
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.builder().jitter(null));

        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().backoffMultiplier(0.9).build());

        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().backoffMultiplier(Double.NaN).build());

        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().maxRetryAfter(Duration.ofMillis(-1)).build());
    }

    @Test
    @DisplayName("Jitter.NONE: strictly deterministic bound without randomness")
    void jitterNoneIsStrictlyDeterministic() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(1000))
                .backoffMultiplier(2.0)
                .jitter(Jitter.NONE)
                .build();

        assertEquals(100, policy.nextDelayMillis(1, null, minDraw()));
        assertEquals(100, policy.nextDelayMillis(1, null, maxDraw()));
        assertEquals(200, policy.nextDelayMillis(2, null, minDraw()));
        assertEquals(200, policy.nextDelayMillis(2, null, maxDraw()));
        assertEquals(400, policy.nextDelayMillis(3, null, minDraw()));
        assertEquals(800, policy.nextDelayMillis(4, null, minDraw()));
        assertEquals(1000, policy.nextDelayMillis(5, null, minDraw()));
    }

    @Test
    @DisplayName("Jitter.EQUAL: wait is bounded between [bound / 2, bound]")
    void jitterEqualGuaranteesHalfBound() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(5))
                .jitter(Jitter.EQUAL)
                .build();

        // attempt 1: bound = 200, half = 100, diff = 100 -> minDraw gives 100, maxDraw gives 200
        assertEquals(100, policy.nextDelayMillis(1, null, minDraw()));
        assertEquals(200, policy.nextDelayMillis(1, null, maxDraw()));

        // attempt 2: bound = 400, half = 200 -> minDraw gives 200, maxDraw gives 400
        assertEquals(200, policy.nextDelayMillis(2, null, minDraw()));
        assertEquals(400, policy.nextDelayMillis(2, null, maxDraw()));

        Random random = new Random(12345);
        for (long attempt = 1; attempt <= 5; attempt++) {
            long bound = 200 * (1L << (attempt - 1));
            long half = bound / 2;
            for (int i = 0; i < 50; i++) {
                long wait = policy.nextDelayMillis(attempt, null, random);
                assertTrue(wait >= half && wait <= bound,
                        "wait " + wait + " outside [" + half + ", " + bound + "] on attempt " + attempt);
            }
        }
    }

    @Test
    @DisplayName("Jitter.DECORRELATED: adaptive scaling from previous sleep duration")
    void jitterDecorrelatedScalesFromPreviousDelay() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(1000))
                .jitter(Jitter.DECORRELATED)
                .build();

        // When previous is 0, defaults to initialBackoff (100) -> minDraw gives 100, maxDraw gives 300
        assertEquals(100, policy.nextDelayMillis(1, 0L, null, minDraw()));
        assertEquals(300, policy.nextDelayMillis(1, 0L, null, maxDraw()));

        // When previous is 200 -> ceiling min(1000, 200 * 3) = 600 -> minDraw gives 100, maxDraw gives 600
        assertEquals(100, policy.nextDelayMillis(2, 200L, null, minDraw()));
        assertEquals(600, policy.nextDelayMillis(2, 200L, null, maxDraw()));

        // When previous is 500 -> ceiling min(1000, 500 * 3) = 1000 -> maxDraw gives 1000
        assertEquals(1000, policy.nextDelayMillis(3, 500L, null, maxDraw()));

        // Never exceeds maxBackoff (1000)
        Random random = new Random(42);
        long prev = 100;
        for (int i = 0; i < 50; i++) {
            long wait = policy.nextDelayMillis(i + 1, prev, null, random);
            assertTrue(wait >= 100 && wait <= 1000, "Decorrelated wait outside [100, 1000]: " + wait);
            prev = wait;
        }
    }

    @Test
    @DisplayName("backoffMultiplier: custom 3.0 scales 100 -> 300 -> 900 -> 2700 -> 5000 cap")
    void customMultiplierScalesBound() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(5000))
                .backoffMultiplier(3.0)
                .jitter(Jitter.NONE)
                .build();

        assertEquals(100, policy.nextDelayMillis(1, null, maxDraw()));
        assertEquals(300, policy.nextDelayMillis(2, null, maxDraw()));
        assertEquals(900, policy.nextDelayMillis(3, null, maxDraw()));
        assertEquals(2700, policy.nextDelayMillis(4, null, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(5, null, maxDraw()));
    }

    @Test
    @DisplayName("respectRetryAfter = false: ignores Retry-After header and follows local jitter")
    void respectRetryAfterFalseIgnoresServerHeader() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(1000))
                .jitter(Jitter.NONE)
                .respectRetryAfter(false)
                .build();

        // Server sends 5000 ms, but respectRetryAfter is false -> returns local bound (100 ms)
        assertEquals(100, policy.nextDelayMillis(1, 5000L, maxDraw()));
    }

    @Test
    @DisplayName("maxRetryAfter cap: restricts excessive server Retry-After")
    void maxRetryAfterCapsExcessiveWait() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(10000))
                .maxRetryAfter(Duration.ofMillis(500))
                .build();

        // Server sends 86400000 ms (24 hours), but maxRetryAfter is 500 ms -> capped at max(100, 500) = 500
        assertEquals(500, policy.nextDelayMillis(1, 86_400_000L, maxDraw()));
        assertEquals(500, policy.nextDelayMillis(1, 86_400_000L, minDraw()));
    }

    @Test
    @DisplayName("maxRetryAfter unset: the effective cap falls back to maxBackoff (ADR-0040 default)")
    void defaultRetryAfterCapFallsBackToMaxBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(1000))
                .build();

        // Server sends 24 hours; the documented default cap is maxBackoff -> 1000 ms.
        assertEquals(1000, policy.nextDelayMillis(1, 86_400_000L, maxDraw()));
        assertEquals(1000, policy.nextDelayMillis(1, 86_400_000L, minDraw()));

        // A Retry-After below the cap is still honored verbatim when it beats the bound.
        assertEquals(700, policy.nextDelayMillis(1, 700L, maxDraw()));
    }

    @Test
    @DisplayName("toString includes all ADR-0040 properties")
    void toStringIncludesNewProperties() {
        RetryPolicy policy = RetryPolicy.builder()
                .jitter(Jitter.EQUAL)
                .backoffMultiplier(2.5)
                .respectRetryAfter(false)
                .maxRetryAfter(Duration.ofSeconds(1))
                .build();

        String str = policy.toString();
        assertTrue(str.contains("jitter=EQUAL"));
        assertTrue(str.contains("backoffMultiplier=2.5"));
        assertTrue(str.contains("respectRetryAfter=false"));
        assertTrue(str.contains("maxRetryAfter=PT1S"));
    }
}
