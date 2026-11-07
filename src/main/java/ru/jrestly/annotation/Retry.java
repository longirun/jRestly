package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a retry policy for an HTTP method, overriding the client-level policy.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /** Total number of attempts, including the initial call (1 disables retries). */
    int times() default 1;

    /** Initial backoff duration in milliseconds. */
    long initialBackoffMs() default 200;

    /** Maximum backoff duration in milliseconds. */
    long maxBackoffMs() default 5_000;

    /** HTTP status codes that trigger a retry. */
    int[] statuses() default {408, 429, 500, 502, 503, 504};

    /** Whether to retry on I/O transport failures. */
    boolean transport() default true;

    /** Whether to retry status codes handled by {@link OnError}. */
    boolean onErrorStatuses() default false;

    /** Whether to retry non-idempotent HTTP methods (POST, PATCH). */
    boolean nonIdempotent() default false;

    /** Whether to retry when response body deserialization fails. */
    boolean decodeFailures() default false;

    /** Whether to respect the {@code Retry-After} response header. */
    boolean respectRetryAfter() default true;

    /** Maximum wait in milliseconds for {@code Retry-After}, or {@code -1} for {@link #maxBackoffMs}. */
    long maxRetryAfterMs() default -1;
}
