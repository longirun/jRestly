package ru.jrestly.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when a request is rejected because the target {@link CircuitBreaker} is in {@link CircuitBreaker.State#OPEN}.
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The circuit breaker name. */
    private final String circuitBreakerName;
    /** The duration remaining in open state. */
    private final Duration remainingWaitDuration;

    /**
     * Constructs a new exception for the open circuit breaker.
     *
     * @param circuitBreakerName the name of the open circuit breaker
     * @param remainingWaitDuration the duration remaining until half-open transition, or {@link Duration#ZERO}
     */
    public CircuitBreakerOpenException(String circuitBreakerName, Duration remainingWaitDuration) {
        super(String.format("CircuitBreaker '%s' is OPEN (remaining wait: %d ms)",
                circuitBreakerName, remainingWaitDuration != null ? remainingWaitDuration.toMillis() : 0));
        this.circuitBreakerName = Objects.requireNonNull(circuitBreakerName, "circuitBreakerName must not be null");
        this.remainingWaitDuration = remainingWaitDuration != null ? remainingWaitDuration : Duration.ZERO;
    }

    /**
     * Returns the name of the open circuit breaker.
     *
     * @return the circuit breaker name
     */
    public String circuitBreakerName() {
        return circuitBreakerName;
    }

    /**
     * Returns the duration remaining until the circuit breaker enters the half-open state.
     *
     * @return the remaining wait duration
     */
    public Duration remainingWaitDuration() {
        return remainingWaitDuration;
    }
}
