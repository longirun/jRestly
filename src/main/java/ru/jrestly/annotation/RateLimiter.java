package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares client-side rate limiting for a controller interface or method (ADR-0043).
 *
 * <p>When placed on an interface, the rate limiter applies to all methods of that interface.
 * When placed on a method, it overrides interface-level or client-level rate limiters.
 *
 * <p>Setting {@link #enabled()} to {@code false} disables rate limiting for the annotated method.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * Unique name of the rate limiter.
     * Multiple methods or interfaces with the same name share the same token bucket.
     *
     * @return rate limiter name
     */
    String name() default "default";

    /**
     * Number of permissions allowed during the refresh period.
     *
     * @return limit for period
     */
    int limitForPeriod() default 10;

    /**
     * The refresh period in milliseconds.
     *
     * @return refresh period in milliseconds
     */
    long limitRefreshPeriodMs() default 1000;

    /**
     * The timeout in milliseconds to wait for a permission before rejecting with {@code RateLimitExceededException}.
     * A value of {@code 0} indicates immediate fail-fast.
     *
     * @return timeout duration in milliseconds
     */
    long timeoutDurationMs() default 0;

    /**
     * Whether rate limiting is active.
     * Can be set to {@code false} on a specific method to opt out from an interface-level rate limiter.
     *
     * @return {@code true} if enabled; {@code false} otherwise
     */
    boolean enabled() default true;
}
