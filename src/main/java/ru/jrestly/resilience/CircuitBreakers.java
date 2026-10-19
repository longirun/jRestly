package ru.jrestly.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Factory and in-memory implementation for {@link CircuitBreaker}.
 */
public final class CircuitBreakers {

    private CircuitBreakers() {}

    /**
     * Creates a thread-safe in-memory {@link CircuitBreaker}.
     *
     * @param name name of the circuit breaker
     * @param config configuration
     * @return the circuit breaker
     */
    public static CircuitBreaker inMemory(String name, CircuitBreakerConfig config) {
        return new InMemoryCircuitBreaker(name, config);
    }

    private static final class InMemoryCircuitBreaker implements CircuitBreaker {

        private final String name;
        private final CircuitBreakerConfig config;
        private final ReentrantLock lock = new ReentrantLock();

        private volatile State state = State.CLOSED;
        private volatile long openTimestampNanos = 0L;

        // Sliding window data for CLOSED state
        private final boolean[] ringBuffer;
        private int ringIndex = 0;
        private int currentSize = 0;
        private int failureCount = 0;

        // Half-open trial state
        private int halfOpenCallsPermitted = 0;
        private int halfOpenSuccessCount = 0;
        private int halfOpenFailureCount = 0;

        InMemoryCircuitBreaker(String name, CircuitBreakerConfig config) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.ringBuffer = new boolean[config.slidingWindowSize()];
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public State getState() {
            checkAutoHalfOpenTransition();
            return state;
        }

        @Override
        public boolean tryAcquirePermission() {
            checkAutoHalfOpenTransition();

            if (state == State.CLOSED) {
                return true;
            }

            if (state == State.OPEN) {
                return false;
            }

            // HALF_OPEN
            lock.lock();
            try {
                if (state == State.HALF_OPEN && halfOpenCallsPermitted > 0) {
                    halfOpenCallsPermitted--;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void recordSuccess(long durationNanos) {
            lock.lock();
            try {
                if (state == State.CLOSED) {
                    recordOutcomeInRingBuffer(true);
                } else if (state == State.HALF_OPEN) {
                    halfOpenSuccessCount++;
                    evaluateHalfOpenTransition();
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void recordFailure(long durationNanos, Throwable throwable) {
            if (throwable != null && !config.recordException().test(throwable)) {
                // Not classified as a remote failure, record as success
                recordSuccess(durationNanos);
                return;
            }

            lock.lock();
            try {
                if (state == State.CLOSED) {
                    recordOutcomeInRingBuffer(false);
                    if (currentSize >= config.minimumNumberOfCalls()) {
                        float rate = (failureCount * 100.0f) / currentSize;
                        if (rate >= config.failureRateThreshold()) {
                            tripToOpen();
                        }
                    }
                } else if (state == State.HALF_OPEN) {
                    halfOpenFailureCount++;
                    // A failure in half-open immediately reopens the circuit
                    tripToOpen();
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Duration remainingWaitDuration() {
            if (state != State.OPEN) {
                return Duration.ZERO;
            }
            long elapsed = System.nanoTime() - openTimestampNanos;
            long waitNanos = config.waitDurationInOpenState().toNanos();
            long remaining = Math.max(0L, waitNanos - elapsed);
            return Duration.ofNanos(remaining);
        }

        @Override
        public CircuitBreakerConfig config() {
            return config;
        }

        @Override
        public CircuitBreakerMetrics metrics() {
            lock.lock();
            try {
                checkAutoHalfOpenTransition();
                int total = state == State.HALF_OPEN ? (halfOpenSuccessCount + halfOpenFailureCount) : currentSize;
                int failed = state == State.HALF_OPEN ? halfOpenFailureCount : failureCount;
                int success = total - failed;
                float rate = total > 0 ? (failed * 100.0f) / total : 0.0f;
                return new CircuitBreakerMetrics(total, success, failed, rate, state);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void transitionToClosedState() {
            lock.lock();
            try {
                resetClosedState();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void transitionToOpenState() {
            lock.lock();
            try {
                tripToOpen();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void transitionToHalfOpenState() {
            lock.lock();
            try {
                state = State.HALF_OPEN;
                halfOpenCallsPermitted = config.permittedNumberOfCallsInHalfOpenState();
                halfOpenSuccessCount = 0;
                halfOpenFailureCount = 0;
            } finally {
                lock.unlock();
            }
        }

        private void checkAutoHalfOpenTransition() {
            if (state == State.OPEN) {
                long elapsed = System.nanoTime() - openTimestampNanos;
                if (elapsed >= config.waitDurationInOpenState().toNanos()) {
                    lock.lock();
                    try {
                        if (state == State.OPEN) {
                            long check = System.nanoTime() - openTimestampNanos;
                            if (check >= config.waitDurationInOpenState().toNanos()) {
                                transitionToHalfOpenState();
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }

        private void recordOutcomeInRingBuffer(boolean success) {
            if (currentSize == ringBuffer.length) {
                boolean previousOutcome = ringBuffer[ringIndex];
                if (!previousOutcome) {
                    failureCount--;
                }
            } else {
                currentSize++;
            }
            ringBuffer[ringIndex] = success;
            if (!success) {
                failureCount++;
            }
            ringIndex = (ringIndex + 1) % ringBuffer.length;
        }

        private void tripToOpen() {
            state = State.OPEN;
            openTimestampNanos = System.nanoTime();
        }

        private void resetClosedState() {
            state = State.CLOSED;
            openTimestampNanos = 0L;
            ringIndex = 0;
            currentSize = 0;
            failureCount = 0;
            halfOpenCallsPermitted = 0;
            halfOpenSuccessCount = 0;
            halfOpenFailureCount = 0;
        }

        private void evaluateHalfOpenTransition() {
            if (halfOpenSuccessCount >= config.permittedNumberOfCallsInHalfOpenState()) {
                resetClosedState();
            }
        }
    }
}
