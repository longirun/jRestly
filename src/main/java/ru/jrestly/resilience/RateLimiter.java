package ru.jrestly.resilience;

import java.time.Duration;

/**
 * Client-side rate limiter controlling the throughput of outgoing HTTP requests.
 */
public interface RateLimiter {

    /**
     * Returns the name of this rate limiter.
     *
     * @return the name
     */
    String name();

    /**
     * Attempts to acquire a single permission immediately without waiting.
     *
     * @return {@code true} if permission was acquired; {@code false} otherwise
     */
    default boolean tryAcquire() {
        return tryAcquire(Duration.ZERO);
    }

    /**
     * Attempts to acquire a single permission, waiting up to the specified timeout duration if necessary.
     *
     * @param timeout maximum duration to wait for a permission
     * @return {@code true} if permission was acquired within the timeout; {@code false} otherwise
     */
    boolean tryAcquire(Duration timeout);

    /**
     * Acquires a permission, waiting up to the configured {@link RateLimiterConfig#timeoutDuration()}
     * and throwing {@link RateLimitExceededException} if permission cannot be obtained.
     *
     * @throws RateLimitExceededException if permission was not acquired
     */
    default void acquire() throws RateLimitExceededException {
        if (!tryAcquire(config().timeoutDuration())) {
            throw new RateLimitExceededException(name());
        }
    }

    /**
     * Returns the configuration of this rate limiter.
     *
     * @return the configuration
     */
    RateLimiterConfig config();

    /**
     * Returns a snapshot of current metrics for this rate limiter.
     *
     * @return current metrics snapshot
     */
    RateLimiterMetrics metrics();

    /**
     * Creates a new in-memory {@link RateLimiter} with default configuration.
     *
     * @param name name of the rate limiter
     * @return the rate limiter
     */
    static RateLimiter of(String name) {
        return RateLimiters.tokenBucket(name, RateLimiterConfig.ofDefaults());
    }

    /**
     * Creates a new in-memory {@link RateLimiter} with the given configuration.
     *
     * @param name name of the rate limiter
     * @param config configuration
     * @return the rate limiter
     */
    static RateLimiter of(String name, RateLimiterConfig config) {
        return RateLimiters.tokenBucket(name, config);
    }

    /**
     * Creates a new in-memory {@link RateLimiter} with the given limit and refresh period.
     *
     * @param name name of the rate limiter
     * @param limitForPeriod number of calls permitted per period
     * @param limitRefreshPeriod refresh period
     * @return the rate limiter
     */
    static RateLimiter of(String name, int limitForPeriod, Duration limitRefreshPeriod) {
        return RateLimiters.tokenBucket(name, RateLimiterConfig.builder()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(limitRefreshPeriod)
                .build());
    }
}
