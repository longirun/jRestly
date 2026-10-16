package ru.jrestly.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Factory and in-memory implementation of {@link RateLimiter}.
 */
public final class RateLimiters {

    private RateLimiters() {}

    /**
     * Creates an in-memory token-bucket {@link RateLimiter}.
     *
     * @param name name of the rate limiter
     * @param config configuration
     * @return the rate limiter
     */
    public static RateLimiter tokenBucket(String name, RateLimiterConfig config) {
        return new TokenBucketRateLimiter(name, config);
    }

    /**
     * Computes the remaining wait budget relative to an absolute {@code nanoTime}
     * deadline, clamped to zero. Package-private seam for tests.
     *
     * @param deadlineNanos absolute nanoTime deadline of the wait
     * @param nowNanos current nanoTime
     * @return remaining budget, or zero once the deadline is reached
     */
    static long remainingBudget(long deadlineNanos, long nowNanos) {
        return Math.max(0L, deadlineNanos - nowNanos);
    }

    private static final class TokenBucketRateLimiter implements RateLimiter {

        private final String name;
        private final RateLimiterConfig config;
        private final double tokensPerNano;

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition canAcquire = lock.newCondition();

        private double availableTokens;
        private long lastRefillNanos;
        private int waitingThreads = 0;

        TokenBucketRateLimiter(String name, RateLimiterConfig config) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.tokensPerNano = (double) config.limitForPeriod() / config.limitRefreshPeriod().toNanos();
            this.availableTokens = config.limitForPeriod();
            this.lastRefillNanos = System.nanoTime();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean tryAcquire(Duration timeout) {
            long timeoutNanos = timeout != null ? Math.max(0L, timeout.toNanos()) : 0L;
            // Anchor the wait budget to an absolute nanoTime deadline: the value
            // returned by awaitNanos is a platform-dependent estimate of the
            // time actually slept (it can even be negative), so incremental
            // bookkeeping on it is not a reliable bound on total wait time.
            long deadlineNanos = System.nanoTime() + timeoutNanos;
            lock.lock();
            try {
                while (true) {
                    refill();
                    if (availableTokens >= 1.0) {
                        availableTokens -= 1.0;
                        return true;
                    }
                    long remainingWaitNanos = remainingBudget(deadlineNanos, System.nanoTime());
                    if (remainingWaitNanos <= 0L) {
                        return false;
                    }
                    double missing = 1.0 - availableTokens;
                    long nanosNeeded = (long) Math.ceil(missing / tokensPerNano);
                    if (nanosNeeded > remainingWaitNanos) {
                        return false;
                    }
                    try {
                        waitingThreads++;
                        canAcquire.awaitNanos(Math.min(nanosNeeded, remainingWaitNanos));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    } finally {
                        waitingThreads--;
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public RateLimiterConfig config() {
            return config;
        }

        @Override
        public RateLimiterMetrics metrics() {
            lock.lock();
            try {
                refill();
                return new RateLimiterMetrics((int) Math.floor(availableTokens), waitingThreads);
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                double newTokens = elapsed * tokensPerNano;
                availableTokens = Math.min((double) config.limitForPeriod(), availableTokens + newTokens);
                lastRefillNanos = now;
            }
        }
    }
}
