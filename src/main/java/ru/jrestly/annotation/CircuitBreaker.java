package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares circuit breaker protection for a controller interface or method (ADR-0043).
 *
 * <p>When placed on an interface, the circuit breaker applies to all methods of that interface.
 * When placed on a method, it overrides interface-level or client-level circuit breakers.
 *
 * <p>Setting {@link #enabled()} to {@code false} disables circuit breaker protection for the annotated method.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CircuitBreaker {

    /**
     * Unique name of the circuit breaker.
     * Multiple methods or interfaces with the same name share the same circuit breaker state.
     *
     * @return the circuit breaker name
     */
    String name() default "default";

    /**
     * The failure rate threshold percentage in {@code (0.0, 100.0]} required to trip the circuit to OPEN.
     *
     * @return failure rate threshold percentage
     */
    float failureRateThreshold() default 50.0f;

    /**
     * Minimum number of calls in the sliding window required before failure rate calculation starts.
     *
     * @return minimum number of calls
     */
    int minimumNumberOfCalls() default 10;

    /**
     * Size of the sliding call window.
     *
     * @return sliding window size
     */
    int slidingWindowSize() default 20;

    /**
     * The duration in milliseconds the circuit breaker remains OPEN before transitioning to HALF_OPEN.
     *
     * @return wait duration in milliseconds
     */
    long waitDurationMs() default 5000;

    /**
     * The number of probe calls allowed when the circuit breaker is in HALF_OPEN state.
     *
     * @return permitted calls in half-open state
     */
    int permittedNumberOfCallsInHalfOpenState() default 3;

    /**
     * Whether circuit breaker protection is active.
     * Can be set to {@code false} on a specific method to opt out from an interface-level circuit breaker.
     *
     * @return {@code true} if enabled; {@code false} otherwise
     */
    boolean enabled() default true;
}
