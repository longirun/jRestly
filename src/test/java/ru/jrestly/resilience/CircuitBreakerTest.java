package ru.jrestly.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.ClientErrorException;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the in-memory {@link CircuitBreaker} (ADR-0043): state
 * transitions, the sliding-window failure-rate trip, the default
 * {@code recordException} classification (5xx/IO/timeout are failures, 4xx
 * are remote successes), manual state transitions and the name-keyed
 * {@link CircuitBreakerRegistry}. Pure unit tests, no HTTP.
 */
class CircuitBreakerTest {

    @Test
    @DisplayName("CircuitBreaker starts in CLOSED state with 0 calls")
    void startsClosed() {
        CircuitBreaker cb = CircuitBreaker.of("test-cb");
        assertEquals("test-cb", cb.name());
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquirePermission());

        CircuitBreakerMetrics metrics = cb.metrics();
        assertEquals(0, metrics.totalCalls());
        assertEquals(0, metrics.successfulCalls());
        assertEquals(0, metrics.failedCalls());
        assertEquals(0.0f, metrics.failureRate());
        assertEquals(CircuitBreaker.State.CLOSED, metrics.state());
    }

    @Test
    @DisplayName("Calls under minimumNumberOfCalls do not trip circuit even on 100% failures")
    void respectsMinimumNumberOfCalls() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .minimumNumberOfCalls(5)
                .slidingWindowSize(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();
        CircuitBreaker cb = CircuitBreaker.of("min-calls", config);

        // 4 failures out of 5 minimum
        for (int i = 0; i < 4; i++) {
            assertTrue(cb.tryAcquirePermission());
            cb.recordFailure(100, new IOException("transport error"));
            assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        }

        assertEquals(4, cb.metrics().failedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // 5th call failure -> trips to OPEN
        assertTrue(cb.tryAcquirePermission());
        cb.recordFailure(100, new IOException("transport error"));

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquirePermission());

        CircuitBreakerOpenException ex = assertThrows(CircuitBreakerOpenException.class, cb::acquirePermission);
        assertEquals("min-calls", ex.circuitBreakerName());
        assertNotNull(ex.remainingWaitDuration());
    }

    @Test
    @DisplayName("Transitions to OPEN when failure rate exceeds threshold in sliding window")
    void transitionsToOpenOnFailureRate() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .minimumNumberOfCalls(4)
                .slidingWindowSize(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .build();
        CircuitBreaker cb = CircuitBreaker.of("rate-test", config);

        // 2 successes, 2 failures -> 50% failures >= 50% threshold
        cb.recordSuccess(10);
        cb.recordSuccess(10);
        cb.recordFailure(10, new ServerErrorException(500, "Error", java.util.List.of()));
        cb.recordFailure(10, new ServerErrorException(502, "Bad Gateway", java.util.List.of()));

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquirePermission());
    }

    @Test
    @DisplayName("Transitions from OPEN to HALF_OPEN after waitDuration and recovers to CLOSED")
    void transitionsToHalfOpenAndRecovers() throws InterruptedException {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .minimumNumberOfCalls(2)
                .slidingWindowSize(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(50))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        CircuitBreaker cb = CircuitBreaker.of("recovery-test", config);

        // Trip to OPEN
        cb.recordFailure(10, new IOException("fail 1"));
        cb.recordFailure(10, new IOException("fail 2"));
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait for open duration
        Thread.sleep(80);

        // First check -> automatically transitions to HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // 2 probe calls permitted
        assertTrue(cb.tryAcquirePermission());
        assertTrue(cb.tryAcquirePermission());
        // 3rd call in half-open is rejected until trial completes
        assertFalse(cb.tryAcquirePermission());

        // Complete 2 probe calls successfully
        cb.recordSuccess(10);
        cb.recordSuccess(10);

        // Circuit must recover to CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquirePermission());
    }

    @Test
    @DisplayName("Failure in HALF_OPEN immediately trips back to OPEN")
    void failureInHalfOpenTripsBackToOpen() throws InterruptedException {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .minimumNumberOfCalls(2)
                .slidingWindowSize(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(50))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreaker cb = CircuitBreaker.of("half-open-fail", config);

        // Trip to OPEN
        cb.recordFailure(10, new IOException("fail 1"));
        cb.recordFailure(10, new IOException("fail 2"));
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(80);
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        assertTrue(cb.tryAcquirePermission());
        cb.recordSuccess(10);

        // Second probe fails
        assertTrue(cb.tryAcquirePermission());
        cb.recordFailure(10, new IOException("probe failed"));

        // Immediately back to OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquirePermission());
    }

    @Test
    @DisplayName("Client errors (4xx) are treated as remote success and do not trigger circuit breaker")
    void clientErrorsDoNotTripCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .minimumNumberOfCalls(2)
                .slidingWindowSize(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();
        CircuitBreaker cb = CircuitBreaker.of("client-errors", config);

        // HTTP 404 and HTTP 400 are ClientErrorException
        cb.recordFailure(10, new ClientErrorException(404, "Not Found", java.util.List.of()));
        cb.recordFailure(10, new ClientErrorException(400, "Bad Request", java.util.List.of()));

        // Since client errors are not remote server failures, they are recorded as success
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(2, cb.metrics().successfulCalls());
        assertEquals(0, cb.metrics().failedCalls());
    }

    @Test
    @DisplayName("Default recordException predicate classifies 5xx, UnexpectedStatusException >= 500, Timeout and IO as failures")
    void defaultRecordExceptionClassification() {
        var predicate = CircuitBreakerConfig.DEFAULT_RECORD_EXCEPTION;

        assertTrue(predicate.test(new IOException("conn reset")));
        assertTrue(predicate.test(new TimeoutException("timeout")));
        assertTrue(predicate.test(new ServerErrorException(503, "Unavailable", java.util.List.of())));
        assertTrue(predicate.test(new UnexpectedStatusException(500, "Err", java.util.List.of())));
        assertTrue(predicate.test(new RuntimeException(new IOException("wrapped"))));

        assertFalse(predicate.test(new ClientErrorException(404, "Not Found", java.util.List.of())));
        assertFalse(predicate.test(new UnexpectedStatusException(400, "Bad Request", java.util.List.of())));
        assertFalse(predicate.test(new IllegalArgumentException("local bug")));
        assertFalse(predicate.test(null));
    }

    @Test
    @DisplayName("Manual state transitions work as expected")
    void manualStateTransitions() {
        CircuitBreaker cb = CircuitBreaker.of("manual");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        cb.transitionToOpenState();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquirePermission());

        cb.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        cb.transitionToClosedState();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquirePermission());
    }

    @Test
    @DisplayName("CircuitBreakerRegistry caches and returns instances by name")
    void registryCachesByName() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker cb1 = registry.getOrCreate("billing");
        CircuitBreaker cb2 = registry.getOrCreate("billing");

        assertSame(cb1, cb2);
        assertSame(cb1, registry.get("billing"));

        CircuitBreaker cbCustom = registry.getOrCreate("custom", CircuitBreakerConfig.builder()
                .failureRateThreshold(30.0f)
                .build());
        assertEquals("custom", cbCustom.name());
        assertEquals(30.0f, cbCustom.config().failureRateThreshold());
    }
}
