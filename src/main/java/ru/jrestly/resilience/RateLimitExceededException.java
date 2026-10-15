package ru.jrestly.resilience;

import java.util.Objects;

/**
 * Thrown when a request is rejected because the {@link RateLimiter} has exhausted its available permissions.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The rate limiter name. */
    private final String rateLimiterName;

    /**
     * Constructs a new exception for the exhausted rate limiter.
     *
     * @param rateLimiterName the name of the rate limiter
     */
    public RateLimitExceededException(String rateLimiterName) {
        super(String.format("RateLimiter '%s' does not permit further calls (rate limit exceeded)", rateLimiterName));
        this.rateLimiterName = Objects.requireNonNull(rateLimiterName, "rateLimiterName must not be null");
    }

    /**
     * Returns the name of the rate limiter.
     *
     * @return the rate limiter name
     */
    public String rateLimiterName() {
        return rateLimiterName;
    }
}
