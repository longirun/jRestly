package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link RetryPolicy} value and its backoff math — the
 * ADR-0008 unit layer (Aspect 7): pure delay function through the
 * package-private seam, builder validation, and the {@code defaults()} /
 * {@code off()} statics. No WireMock here; integration scenarios are a
 * later step of the epic.
 */
class RetryPolicyTest {

    /**
     * A generator that always takes the top of the jitter range, so the
     * drawn wait equals the deterministic bound: it exposes the bound
     * shape without any real waiting.
     */
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

    // --- statics -----------------------------------------------------------

    @Test
    @DisplayName("defaults(): 3 attempts, 200 ms -> 5 s cap, default statuses, transport on, the rest off")
    void defaultsPolicyIsTheAdrShape() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofMillis(200), policy.getInitialBackoff());
        assertEquals(Duration.ofSeconds(5), policy.getMaxBackoff());
        assertEquals(RetryPolicy.DEFAULT_RETRYABLE_STATUSES, policy.getRetryableStatuses());
        assertTrue(policy.isRetryTransport());
        assertFalse(policy.isRetryOnErrorStatuses());
        assertFalse(policy.isRetryNonIdempotent());
        assertTrue(policy.isEnabled());
    }

    @Test
    @DisplayName("off(): maxAttempts = 1, disabled, other fields keep their defaults")
    void offPolicyNeverRetries() {
        RetryPolicy policy = RetryPolicy.off();

        assertEquals(1, policy.maxAttempts());
        assertFalse(policy.isEnabled());
        assertEquals(Duration.ofMillis(200), policy.getInitialBackoff());
        assertEquals(Duration.ofSeconds(5), policy.getMaxBackoff());
        assertEquals(RetryPolicy.DEFAULT_RETRYABLE_STATUSES, policy.getRetryableStatuses());
        assertTrue(policy.isRetryTransport());
    }

    @Test
    @DisplayName("defaults() equals a plain builder().build()")
    void builderDefaultsMatchTheStatics() {
        assertEquals(RetryPolicy.defaults().maxAttempts(), RetryPolicy.builder().build().maxAttempts());
        assertEquals(RetryPolicy.defaults().getInitialBackoff(), RetryPolicy.builder().build().getInitialBackoff());
        assertEquals(RetryPolicy.defaults().getMaxBackoff(), RetryPolicy.builder().build().getMaxBackoff());
    }

    // --- builder validation --------------------------------------------------

    @Test
    @DisplayName("maxAttempts = 0 or negative: rejected (it counts the first attempt)")
    void maxAttemptsIsAtLeastOne() {
        IllegalStateException zero = assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().maxAttempts(0).build());
        assertTrue(zero.getMessage().contains("maxAttempts"));

        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().maxAttempts(-1).build());
    }

    @Test
    @DisplayName("initialBackoff > maxBackoff: rejected; equal is the fixed-curve case and allowed")
    void initialBackoffMustNotExceedMaxBackoff() {
        IllegalStateException out = assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder()
                        .initialBackoff(Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofMillis(200))
                        .build());
        assertTrue(out.getMessage().contains("initialBackoff"));

        RetryPolicy fixed = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .build();
        assertEquals(Duration.ofMillis(100), fixed.getInitialBackoff());
        assertEquals(Duration.ofMillis(100), fixed.getMaxBackoff());
    }

    @Test
    @DisplayName("negative backoff Durations: rejected")
    void backoffsAreNonNegative() {
        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().initialBackoff(Duration.ofMillis(-1)).build());
        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().maxBackoff(Duration.ofMillis(-1)).build());
    }

    @Test
    @DisplayName("null backoff Duration: rejected")
    void backoffsAreNotNull() {
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.builder().initialBackoff(null));
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.builder().maxBackoff(null));
    }

    @Test
    @DisplayName("retryableStatuses: null collection, null element, and out-of-range values are rejected")
    void retryableStatusesValidateTheirContents() {
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.builder().retryableStatuses(null));
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.builder().retryableStatuses(List.of(404, null)));

        IllegalStateException low = assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().retryableStatuses(Set.of(99)).build());
        assertTrue(low.getMessage().contains("99"));

        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().retryableStatuses(Set.of(600)).build());
    }

    @Test
    @DisplayName("retryableStatuses: duplicates collapse, the input is copied, the result is unmodifiable")
    void retryableStatusesAreCopiedIntoAnUnmodifiableSet() {
        List<Integer> input = new ArrayList<>(List.of(404, 404, 429, 404));
        RetryPolicy policy = RetryPolicy.builder().retryableStatuses(input).build();

        assertEquals(Set.of(404, 429), policy.getRetryableStatuses());

        input.add(503);
        assertEquals(Set.of(404, 429), policy.getRetryableStatuses());

        assertThrows(UnsupportedOperationException.class,
                () -> policy.getRetryableStatuses().add(500));
    }

    // --- backoff math: the package-private seam ------------------------------

    @Test
    @DisplayName("exponential shape with cap: 200, 400, 800, 1600, 3200, then pinned at the 5 s cap")
    void delayDoublesPerFailedAttemptUntilTheCap() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(200, policy.nextDelayMillis(1, null, maxDraw()));
        assertEquals(400, policy.nextDelayMillis(2, null, maxDraw()));
        assertEquals(800, policy.nextDelayMillis(3, null, maxDraw()));
        assertEquals(1600, policy.nextDelayMillis(4, null, maxDraw()));
        assertEquals(3200, policy.nextDelayMillis(5, null, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(6, null, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(7, null, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(50, null, maxDraw()));
    }

    @Test
    @DisplayName("full jitter: the draw lives in [0, bound] — 0 at the bottom, bound at the top")
    void jitterStaysWithinTheBound() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(0, policy.nextDelayMillis(1, null, minDraw()));
        assertEquals(0, policy.nextDelayMillis(3, null, minDraw()));

        Random seeded = new Random(42);
        for (long attempt = 1; attempt <= 6; attempt++) {
            long bound = policy.nextDelayMillis(attempt, null, maxDraw());
            for (int draw = 0; draw < 20; draw++) {
                long waited = policy.nextDelayMillis(attempt, null, seeded);
                assertTrue(waited >= 0 && waited <= bound,
                        "wait " + waited + " outside [0, " + bound + "] on attempt " + attempt);
            }
        }
    }

    @Test
    @DisplayName("fixed curve: initialBackoff == maxBackoff pins every wait to that constant")
    void fixedCurveIsTheEqualBoundariesSpecialCase() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .build();

        for (long attempt = 1; attempt <= 8; attempt++) {
            assertEquals(100, policy.nextDelayMillis(attempt, null, maxDraw()));
        }
    }

    @Test
    @DisplayName("zero backoff: every wait is 0, the draw is over [0, 0]")
    void zeroBackoffWaitsNothing() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .build();

        assertEquals(0, policy.nextDelayMillis(1, null, maxDraw()));
        assertEquals(0, policy.nextDelayMillis(9, null, maxDraw()));
    }

    @Test
    @DisplayName("Retry-After beats the bound: the wait is exactly max(bound, retryAfter), deterministic")
    void retryAfterRaisesTheWaitDeterministically() {
        RetryPolicy policy = RetryPolicy.defaults();

        // bound 200 ms on attempt 1, the server asks for 1 s
        assertEquals(1000, policy.nextDelayMillis(1, 1L, maxDraw()));
        assertEquals(1000, policy.nextDelayMillis(1, 1L, minDraw()));

        // bound already at the 5 s cap, a 3 s header must not lower it
        assertEquals(5000, policy.nextDelayMillis(6, 3L, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(6, 3L, minDraw()));

        // a zero header must not lower the bound either
        assertEquals(800, policy.nextDelayMillis(3, 0L, maxDraw()));
        assertEquals(800, policy.nextDelayMillis(3, 0L, minDraw()));
    }

    @Test
    @DisplayName("the seam is deterministic: the same seed reproduces the same jitter sequence")
    void theSameSeedReproducesTheSameSequence() {
        RetryPolicy policy = RetryPolicy.defaults();

        long[] firstPass = new long[4];
        for (int attempt = 0; attempt < 4; attempt++) {
            firstPass[attempt] = policy.nextDelayMillis(attempt + 1, null, new Random(7));
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals(firstPass[attempt],
                    policy.nextDelayMillis(attempt + 1, null, new Random(7)),
                    "attempt " + (attempt + 1));
        }
    }
}
