package ru.jrestly.resilience;

import java.time.Duration;

/**
 * Client-side circuit breaker protecting the system against cascading remote failures.
 *
 * <p>A circuit breaker tracks the outcome of remote HTTP calls and transitions through
 * three states:
 * <ul>
 *   <li>{@link State#CLOSED}: normal operation, calls pass through and their outcomes
 *       are recorded in a sliding window. If the failure rate exceeds
 *       {@link CircuitBreakerConfig#failureRateThreshold()}, the circuit breaker trips to {@link State#OPEN}.</li>
 *   <li>{@link State#OPEN}: the circuit breaker is tripped; further calls fail immediately
 *       with {@link CircuitBreakerOpenException} without reaching the network.</li>
 *   <li>{@link State#HALF_OPEN}: after {@link CircuitBreakerConfig#waitDurationInOpenState()}
 *       has elapsed, a limited number of probe calls are allowed. If the probe calls succeed,
 *       the circuit breaker resets to {@link State#CLOSED}; otherwise it reopens to {@link State#OPEN}.</li>
 * </ul>
 */
public interface CircuitBreaker {

    /**
     * Circuit breaker operating states.
     */
    enum State {
        /** The circuit is closed; calls are allowed and monitored. */
        CLOSED,
        /** The circuit is open; calls are rejected fast without network execution. */
        OPEN,
        /** The circuit is half-open; limited trial calls are permitted to probe remote health. */
        HALF_OPEN
    }

    /**
     * Returns the unique name of this circuit breaker.
     *
     * @return the name
     */
    String name();

    /**
     * Returns the current state of this circuit breaker.
     *
     * @return the current state
     */
    State getState();

    /**
     * Attempts to acquire permission to execute a call.
     *
     * @return {@code true} if permission is granted; {@code false} if the circuit is open
     */
    boolean tryAcquirePermission();

    /**
     * Acquires permission to execute a call or throws {@link CircuitBreakerOpenException}
     * if the circuit is open.
     *
     * @throws CircuitBreakerOpenException if the circuit breaker is open
     */
    default void acquirePermission() throws CircuitBreakerOpenException {
        if (!tryAcquirePermission()) {
            throw new CircuitBreakerOpenException(name(), remainingWaitDuration());
        }
    }

    /**
     * Records a successful execution.
     *
     * @param durationNanos execution duration in nanoseconds
     */
    void recordSuccess(long durationNanos);

    /**
     * Records an execution failure.
     *
     * @param durationNanos execution duration in nanoseconds
     * @param throwable the cause of failure
     */
    void recordFailure(long durationNanos, Throwable throwable);

    /**
     * Returns the remaining wait duration before transitioning from {@link State#OPEN}
     * to {@link State#HALF_OPEN}.
     *
     * @return remaining duration, or {@link Duration#ZERO} if not in {@link State#OPEN}
     */
    Duration remainingWaitDuration();

    /**
     * Returns the configuration of this circuit breaker.
     *
     * @return the configuration
     */
    CircuitBreakerConfig config();

    /**
     * Returns a snapshot of current metrics for this circuit breaker.
     *
     * @return current metrics snapshot
     */
    CircuitBreakerMetrics metrics();

    /**
     * Manually transitions this circuit breaker to {@link State#CLOSED}.
     */
    void transitionToClosedState();

    /**
     * Manually transitions this circuit breaker to {@link State#OPEN}.
     */
    void transitionToOpenState();

    /**
     * Manually transitions this circuit breaker to {@link State#HALF_OPEN}.
     */
    void transitionToHalfOpenState();

    /**
     * Creates a new in-memory {@link CircuitBreaker} with default configuration.
     *
     * @param name name of the circuit breaker
     * @return a new circuit breaker
     */
    static CircuitBreaker of(String name) {
        return CircuitBreakers.inMemory(name, CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Creates a new in-memory {@link CircuitBreaker} with custom configuration.
     *
     * @param name name of the circuit breaker
     * @param config configuration
     * @return a new circuit breaker
     */
    static CircuitBreaker of(String name, CircuitBreakerConfig config) {
        return CircuitBreakers.inMemory(name, config);
    }
}
