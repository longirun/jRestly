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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RetryPolicy}.
 */
class RetryPolicyTest {

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
    @DisplayName("defaults(): 3 attempts, 200 ms -> 5 s cap, default statuses, transport on, the rest off")
    void defaultsPolicyIsTheAdrShape() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(3, policy.getMaxAttempts());
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

        assertEquals(1, policy.getMaxAttempts());
        assertFalse(policy.isEnabled());
        assertEquals(Duration.ofMillis(200), policy.getInitialBackoff());
        assertEquals(Duration.ofSeconds(5), policy.getMaxBackoff());
        assertEquals(RetryPolicy.DEFAULT_RETRYABLE_STATUSES, policy.getRetryableStatuses());
        assertTrue(policy.isRetryTransport());
    }

    @Test
    @DisplayName("retryDecodeFailures: default off in all statics (a distinct opt-in), the builder sets it")
    void retryDecodeFailuresIsADistinctOptIn() {
        assertFalse(RetryPolicy.off().isRetryDecodeFailures());
        assertFalse(RetryPolicy.defaults().isRetryDecodeFailures());
        assertFalse(RetryPolicy.builder().build().isRetryDecodeFailures());

        assertTrue(RetryPolicy.builder().retryDecodeFailures(true).build().isRetryDecodeFailures());
    }

    @Test
    @DisplayName("defaults() equals a plain builder().build()")
    void builderDefaultsMatchTheStatics() {
        assertEquals(RetryPolicy.defaults().getMaxAttempts(), RetryPolicy.builder().build().getMaxAttempts());
        assertEquals(RetryPolicy.defaults().getInitialBackoff(), RetryPolicy.builder().build().getInitialBackoff());
        assertEquals(RetryPolicy.defaults().getMaxBackoff(), RetryPolicy.builder().build().getMaxBackoff());
    }

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

    @Test
    @DisplayName("null random: rejected")
    void nullRandomIsRejected() {
        assertThrows(NullPointerException.class,
                () -> RetryPolicy.defaults().nextDelayMillis(1, null, null));
    }

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
        for (int attempt = 1; attempt <= 6; attempt++) {
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

        assertEquals(100, policy.nextDelayMillis(1, null, maxDraw()));
        assertEquals(100, policy.nextDelayMillis(2, null, maxDraw()));
        assertEquals(100, policy.nextDelayMillis(9, null, maxDraw()));
    }

    @Test
    @DisplayName("Retry-After beats the bound and the jitter: max(bound, retryAfter), same for any random")
    void retryAfterIsADeterministicLowerBound() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(1000, policy.nextDelayMillis(1, 1_000L, maxDraw()));
        assertEquals(1000, policy.nextDelayMillis(1, 1_000L, minDraw()));
        assertEquals(1000, policy.nextDelayMillis(1, 1_000L, new Random(42)));

        assertEquals(800, policy.nextDelayMillis(3, 100L, maxDraw()));
        assertEquals(800, policy.nextDelayMillis(3, 100L, minDraw()));
    }

    @Test
    @DisplayName("a negative Retry-After is clamped to 0: the deterministic bound wins, no jitter")
    void negativeRetryAfterClampsToTheBound() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(200, policy.nextDelayMillis(1, -5_000L, minDraw()));
        assertEquals(200, policy.nextDelayMillis(1, -5_000L, maxDraw()));
    }

    @Test
    @DisplayName("default cap: an unbounded server Retry-After (24 h) falls back to maxBackoff")
    void defaultRetryAfterCapFallsBackToMaxBackoff() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(5000, policy.nextDelayMillis(1, 86_400_000L, maxDraw()));
        assertEquals(5000, policy.nextDelayMillis(1, 86_400_000L, minDraw()));
    }

    @Test
    @DisplayName("explicit cap: the header raises the wait up to maxRetryAfter, never past it")
    void explicitCapBoundsTheHeaderWait() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .maxRetryAfter(Duration.ofMillis(500))
                .build();

        assertEquals(500, policy.nextDelayMillis(1, 86_400_000L, maxDraw()));
        assertEquals(500, policy.nextDelayMillis(1, 86_400_000L, minDraw()));

        assertEquals(400, policy.nextDelayMillis(1, 400L, maxDraw()));
        assertEquals(400, policy.nextDelayMillis(1, 400L, minDraw()));
    }

    @Test
    @DisplayName("respectRetryAfter = false: the header is ignored, pure jitter applies")
    void respectRetryAfterOffIgnoresTheHeader() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .respectRetryAfter(false)
                .build();

        assertEquals(0, policy.nextDelayMillis(1, 60_000L, minDraw()));
        assertEquals(100, policy.nextDelayMillis(1, 60_000L, maxDraw()));
    }

    @Test
    @DisplayName("maxRetryAfter must not be negative; null is the unset default")
    void maxRetryAfterIsValidated() {
        assertThrows(IllegalStateException.class,
                () -> RetryPolicy.builder().maxRetryAfter(Duration.ofMillis(-1)).build());

        RetryPolicy unset = RetryPolicy.builder().build();
        assertNull(unset.getMaxRetryAfter());
        assertTrue(unset.isRespectRetryAfter());
    }
}
