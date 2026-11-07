package ru.jrestly.http;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Immutable retry policy configuration defining attempt limits, backoff strategy,
 * and retry conditions.
 */
public final class RetryPolicy {

    static final Set<Integer> DEFAULT_RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Set<Integer> retryableStatuses;
    private final boolean retryTransport;
    private final boolean retryDecodeFailures;
    private final boolean retryOnErrorStatuses;
    private final boolean retryNonIdempotent;
    private final boolean respectRetryAfter;
    private final Duration maxRetryAfter;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.retryableStatuses = Collections.unmodifiableSet(builder.retryableStatuses);
        this.retryTransport = builder.retryTransport;
        this.retryDecodeFailures = builder.retryDecodeFailures;
        this.retryOnErrorStatuses = builder.retryOnErrorStatuses;
        this.retryNonIdempotent = builder.retryNonIdempotent;
        this.respectRetryAfter = builder.respectRetryAfter;
        this.maxRetryAfter = builder.maxRetryAfter;
    }

    /** Returns a policy with default retry settings (3 attempts, exponential backoff). */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    private static final RetryPolicy OFF = builder().maxAttempts(1).build();

    /** Returns a policy with retries disabled (1 attempt). */
    public static RetryPolicy off() {
        return OFF;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public Set<Integer> getRetryableStatuses() {
        return retryableStatuses;
    }

    public boolean isRetryTransport() {
        return retryTransport;
    }

    public boolean isRetryDecodeFailures() {
        return retryDecodeFailures;
    }

    public boolean isRetryOnErrorStatuses() {
        return retryOnErrorStatuses;
    }

    public boolean isRetryNonIdempotent() {
        return retryNonIdempotent;
    }

    public boolean isRespectRetryAfter() {
        return respectRetryAfter;
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public boolean isEnabled() {
        return maxAttempts > 1;
    }

    long nextDelayMillis(int failedAttempt, Long retryAfterMillis, Random random) {
        Objects.requireNonNull(random, "random");
        long bound = exponentialBoundMillis(failedAttempt);

        if (respectRetryAfter && retryAfterMillis != null) {
            long cap = maxRetryAfter != null ? maxRetryAfter.toMillis() : maxBackoff.toMillis();
            long effectiveRetryAfter = Math.clamp(retryAfterMillis, 0L, cap);
            return Math.max(bound, effectiveRetryAfter);
        }

        if (bound <= 0) {
            return 0;
        }
        return random.nextLong(bound == Long.MAX_VALUE ? bound : bound + 1);
    }

    private long exponentialBoundMillis(int failedAttempt) {
        long cap = maxBackoff.toMillis();
        double bound = initialBackoff.toMillis();
        for (int attempt = 2; attempt <= failedAttempt && bound < cap; attempt++) {
            bound = Math.min((double) cap, bound * 2);
        }
        return (long) bound;
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxAttempts=" + maxAttempts
                + ", initialBackoff=" + initialBackoff
                + ", maxBackoff=" + maxBackoff
                + ", retryableStatuses=" + retryableStatuses
                + ", retryTransport=" + retryTransport
                + ", retryDecodeFailures=" + retryDecodeFailures
                + ", retryOnErrorStatuses=" + retryOnErrorStatuses
                + ", retryNonIdempotent=" + retryNonIdempotent
                + ", respectRetryAfter=" + respectRetryAfter
                + ", maxRetryAfter=" + maxRetryAfter + "]";
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private Set<Integer> retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        private boolean retryTransport = true;
        private boolean retryDecodeFailures = false;
        private boolean retryOnErrorStatuses = false;
        private boolean retryNonIdempotent = false;
        private boolean respectRetryAfter = true;
        private Duration maxRetryAfter = null;

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
            return this;
        }

        public Builder retryableStatuses(Collection<Integer> retryableStatuses) {
            this.retryableStatuses = new LinkedHashSet<>();
            for (Integer status : Objects.requireNonNull(retryableStatuses, "retryableStatuses")) {
                this.retryableStatuses.add(Objects.requireNonNull(status, "status"));
            }
            return this;
        }

        public Builder retryTransport(boolean retryTransport) {
            this.retryTransport = retryTransport;
            return this;
        }

        public Builder retryDecodeFailures(boolean retryDecodeFailures) {
            this.retryDecodeFailures = retryDecodeFailures;
            return this;
        }

        public Builder retryOnErrorStatuses(boolean retryOnErrorStatuses) {
            this.retryOnErrorStatuses = retryOnErrorStatuses;
            return this;
        }

        public Builder retryNonIdempotent(boolean retryNonIdempotent) {
            this.retryNonIdempotent = retryNonIdempotent;
            return this;
        }

        public Builder respectRetryAfter(boolean respectRetryAfter) {
            this.respectRetryAfter = respectRetryAfter;
            return this;
        }

        public Builder maxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = maxRetryAfter;
            return this;
        }

        public RetryPolicy build() {
            if (maxAttempts < 1) {
                throw new IllegalStateException("maxAttempts must be >= 1, got " + maxAttempts);
            }
            if (initialBackoff.isNegative()) {
                throw new IllegalStateException("initialBackoff must not be negative, got " + initialBackoff);
            }
            if (maxBackoff.isNegative()) {
                throw new IllegalStateException("maxBackoff must not be negative, got " + maxBackoff);
            }
            if (initialBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalStateException(
                        "initialBackoff must not exceed maxBackoff, got "
                                + initialBackoff + " > " + maxBackoff);
            }
            if (maxRetryAfter != null && maxRetryAfter.isNegative()) {
                throw new IllegalStateException("maxRetryAfter must not be negative, got " + maxRetryAfter);
            }
            for (Integer status : retryableStatuses) {
                if (status < 100 || status > 599) {
                    throw new IllegalStateException("retryable status must be in [100, 599], got " + status);
                }
            }
            return new RetryPolicy(this);
        }
    }
}
