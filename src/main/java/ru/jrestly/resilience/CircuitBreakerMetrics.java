package ru.jrestly.resilience;

/**
 * Snapshot of execution metrics for a {@link CircuitBreaker}.
 *
 * @param totalCalls the number of calls in the current sliding window or probe phase
 * @param successfulCalls the number of successful calls in the current window
 * @param failedCalls the number of failed calls in the current window
 * @param failureRate the failure rate percentage in the range [0.0, 100.0]
 * @param state the state of the circuit breaker at the time of the snapshot
 */
public record CircuitBreakerMetrics(
        int totalCalls,
        int successfulCalls,
        int failedCalls,
        float failureRate,
        CircuitBreaker.State state
) {}
