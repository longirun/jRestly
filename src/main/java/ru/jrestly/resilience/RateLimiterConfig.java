package ru.jrestly.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a client-side {@link RateLimiter}.
 *
 * @param limitForPeriod number of allowed permissions per refresh period
 * @param limitRefreshPeriod period in which permissions are refreshed
 * @param timeoutDuration default timeout duration to wait for a permission before failing
 */
public record RateLimiterConfig(
        int limitForPeriod,
        Duration limitRefreshPeriod,
        Duration timeoutDuration
) {

    /** Default permissions limit per period: 10. */
    public static final int DEFAULT_LIMIT_FOR_PERIOD = 10;
    /** Default refresh period: 1 second. */
    public static final Duration DEFAULT_LIMIT_REFRESH_PERIOD = Duration.ofSeconds(1);
    /** Default wait timeout: zero (fail-fast without blocking). */
    public static final Duration DEFAULT_TIMEOUT_DURATION = Duration.ZERO;

    /**
     * Validates configuration parameters.
     */
    public RateLimiterConfig {
        if (limitForPeriod <= 0) {
            throw new IllegalArgumentException("limitForPeriod must be > 0, got: " + limitForPeriod);
        }
        Objects.requireNonNull(limitRefreshPeriod, "limitRefreshPeriod must not be null");
        if (limitRefreshPeriod.isNegative() || limitRefreshPeriod.isZero()) {
            throw new IllegalArgumentException("limitRefreshPeriod must be positive, got: " + limitRefreshPeriod);
        }
        Objects.requireNonNull(timeoutDuration, "timeoutDuration must not be null");
        if (timeoutDuration.isNegative()) {
            throw new IllegalArgumentException("timeoutDuration must be non-negative, got: " + timeoutDuration);
        }
    }

    /**
     * Returns a default configuration with 10 requests per second and zero wait timeout.
     *
     * @return default configuration
     */
    public static RateLimiterConfig ofDefaults() {
        return builder().build();
    }

    /**
     * Creates a new fluent {@link Builder}.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link RateLimiterConfig}.
     */
    public static final class Builder {
        private int limitForPeriod = DEFAULT_LIMIT_FOR_PERIOD;
        private Duration limitRefreshPeriod = DEFAULT_LIMIT_REFRESH_PERIOD;
        private Duration timeoutDuration = DEFAULT_TIMEOUT_DURATION;

        private Builder() {}

        /**
         * Sets the number of permissions allowed per period.
         *
         * @param limitForPeriod permissions limit (> 0)
         * @return this builder
         */
        public Builder limitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
            return this;
        }

        /**
         * Sets the refresh period.
         *
         * @param refreshPeriod refresh period (positive)
         * @return this builder
         */
        public Builder limitRefreshPeriod(Duration refreshPeriod) {
            this.limitRefreshPeriod = Objects.requireNonNull(refreshPeriod, "refreshPeriod must not be null");
            return this;
        }

        /**
         * Sets the default timeout duration to wait for a permission.
         *
         * @param timeoutDuration timeout duration (non-negative)
         * @return this builder
         */
        public Builder timeoutDuration(Duration timeoutDuration) {
            this.timeoutDuration = Objects.requireNonNull(timeoutDuration, "timeoutDuration must not be null");
            return this;
        }

        /**
         * Builds the immutable configuration.
         *
         * @return the configuration
         */
        public RateLimiterConfig build() {
            return new RateLimiterConfig(limitForPeriod, limitRefreshPeriod, timeoutDuration);
        }
    }
}
