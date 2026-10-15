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
            lock.lock();
            try {
                long remainingWaitNanos = timeoutNanos;
                while (true) {
                    refill();
                    if (availableTokens >= 1.0) {
                        availableTokens -= 1.0;
                        return true;
                    }
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
                        long waitTime = Math.min(nanosNeeded, remainingWaitNanos);
                        long waited = canAcquire.awaitNanos(waitTime);
                        remainingWaitNanos -= (waitTime - waited);
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
