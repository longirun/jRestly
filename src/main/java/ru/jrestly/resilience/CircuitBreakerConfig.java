package ru.jrestly.resilience;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.http.UnexpectedStatusException;

/**
 * Immutable configuration for a {@link CircuitBreaker}.
 *
 * @param failureRateThreshold percentage of failures in the sliding window required to open the circuit breaker (0.0, 100.0]
 * @param minimumNumberOfCalls minimum number of calls in the sliding window before the failure rate is calculated
 * @param slidingWindowSize size of the sliding window of recorded calls
 * @param waitDurationInOpenState time the circuit breaker remains in {@link CircuitBreaker.State#OPEN} before switching to {@link CircuitBreaker.State#HALF_OPEN}
 * @param permittedNumberOfCallsInHalfOpenState number of probe calls allowed in {@link CircuitBreaker.State#HALF_OPEN}
 * @param recordException predicate used to evaluate whether an exception counts as a failure
 */
public record CircuitBreakerConfig(
        float failureRateThreshold,
        int minimumNumberOfCalls,
        int slidingWindowSize,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState,
        Predicate<Throwable> recordException
) {

    /** Default failure rate threshold: 50%. */
    public static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50.0f;
    /** Default minimum number of calls: 10. */
    public static final int DEFAULT_MINIMUM_NUMBER_OF_CALLS = 10;
    /** Default sliding window size: 20. */
    public static final int DEFAULT_SLIDING_WINDOW_SIZE = 20;
    /** Default wait duration in open state: 5 seconds. */
    public static final Duration DEFAULT_WAIT_DURATION_IN_OPEN = Duration.ofSeconds(5);
    /** Default permitted calls in half-open state: 3. */
    public static final int DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN = 3;

    /**
     * Default predicate classifying server failures: {@link IOException}, {@link TimeoutException},
     * {@link ServerErrorException} (HTTP 5xx) and {@link UnexpectedStatusException} with status &gt;= 500.
     * Client errors (HTTP 4xx) are considered normal remote responses and do not trigger the circuit breaker.
     */
    public static final Predicate<Throwable> DEFAULT_RECORD_EXCEPTION = throwable -> {
        if (throwable == null) {
            return false;
        }
        if (isFailureType(throwable)) {
            return true;
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isFailureType(cause);
        }
        return false;
    };

    private static boolean isFailureType(Throwable t) {
        if (t instanceof IOException || t instanceof TimeoutException || t instanceof ServerErrorException) {
            return true;
        }
        if (t instanceof UnexpectedStatusException use) {
            return use.getStatusCode() >= 500;
        }
        return false;
    }

    /**
     * Validates configuration properties.
     */
    public CircuitBreakerConfig {
        if (failureRateThreshold <= 0.0f || failureRateThreshold > 100.0f) {
            throw new IllegalArgumentException("failureRateThreshold must be in (0.0, 100.0], got: " + failureRateThreshold);
        }
        if (minimumNumberOfCalls <= 0) {
            throw new IllegalArgumentException("minimumNumberOfCalls must be > 0, got: " + minimumNumberOfCalls);
        }
        if (slidingWindowSize < minimumNumberOfCalls) {
            throw new IllegalArgumentException("slidingWindowSize (" + slidingWindowSize
                    + ") must be >= minimumNumberOfCalls (" + minimumNumberOfCalls + ")");
        }
        Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
        if (waitDurationInOpenState.isNegative() || waitDurationInOpenState.isZero()) {
            throw new IllegalArgumentException("waitDurationInOpenState must be positive, got: " + waitDurationInOpenState);
        }
        if (permittedNumberOfCallsInHalfOpenState <= 0) {
            throw new IllegalArgumentException("permittedNumberOfCallsInHalfOpenState must be > 0, got: " + permittedNumberOfCallsInHalfOpenState);
        }
        Objects.requireNonNull(recordException, "recordException must not be null");
    }

    /**
     * Creates a configuration with default values.
     *
     * @return a default {@link CircuitBreakerConfig}
     */
    public static CircuitBreakerConfig ofDefaults() {
        return builder().build();
    }

    /**
     * Creates a new fluent {@link Builder}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link CircuitBreakerConfig}.
     */
    public static final class Builder {
        private float failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
        private int minimumNumberOfCalls = DEFAULT_MINIMUM_NUMBER_OF_CALLS;
        private int slidingWindowSize = DEFAULT_SLIDING_WINDOW_SIZE;
        private Duration waitDurationInOpenState = DEFAULT_WAIT_DURATION_IN_OPEN;
        private int permittedNumberOfCallsInHalfOpenState = DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN;
        private Predicate<Throwable> recordException = DEFAULT_RECORD_EXCEPTION;

        private Builder() {}

        /**
         * Sets the failure rate threshold percentage.
         *
         * @param failureRateThreshold percentage in (0.0, 100.0]
         * @return this builder
         */
        public Builder failureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
            return this;
        }

        /**
         * Sets the minimum number of calls required before calculating the failure rate.
         *
         * @param minimumNumberOfCalls minimum calls (> 0)
         * @return this builder
         */
        public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
            return this;
        }

        /**
         * Sets the sliding window size.
         *
         * @param slidingWindowSize size of window (>= minimumNumberOfCalls)
         * @return this builder
         */
        public Builder slidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
            return this;
        }

        /**
         * Sets the wait duration in the open state before transitioning to half-open.
         *
         * @param waitDuration wait duration
         * @return this builder
         */
        public Builder waitDurationInOpenState(Duration waitDuration) {
            this.waitDurationInOpenState = Objects.requireNonNull(waitDuration, "waitDuration must not be null");
            return this;
        }

        /**
         * Sets the permitted number of probe calls in half-open state.
         *
         * @param permittedCalls permitted probe calls (> 0)
         * @return this builder
         */
        public Builder permittedNumberOfCallsInHalfOpenState(int permittedCalls) {
            this.permittedNumberOfCallsInHalfOpenState = permittedCalls;
            return this;
        }

        /**
         * Sets the predicate classifying whether an exception is considered a circuit-breaker failure.
         *
         * @param recordException predicate
         * @return this builder
         */
        public Builder recordException(Predicate<Throwable> recordException) {
            this.recordException = Objects.requireNonNull(recordException, "recordException must not be null");
            return this;
        }

        /**
         * Builds the immutable {@link CircuitBreakerConfig}.
         *
         * @return the configuration
         */
        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(
                    failureRateThreshold,
                    minimumNumberOfCalls,
                    slidingWindowSize,
                    waitDurationInOpenState,
                    permittedNumberOfCallsInHalfOpenState,
                    recordException
            );
        }
    }
}
