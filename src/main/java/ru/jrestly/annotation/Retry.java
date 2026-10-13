package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a self-contained retry policy for this method (ADR-0008).
 *
 * <p>The annotation is a complete retry policy: when present it replaces
 * the client-level policy entirely — there is no field-by-field merging
 * (predictability over cleverness). A bare {@code @Retry} (all defaults,
 * {@code times = 1}) is the explicit off-switch on a method of a client
 * that has retry enabled: the method never retries, whatever the client
 * does.
 *
 * <p>What is retried under the declared policy:
 * <ul>
 *   <li>transport failures (timeouts, connection resets, DNS blips) —
 *       when {@link #transport() transport} is on (default);</li>
 *   <li>responses whose status is in {@link #statuses() statuses}
 *       (default {@code {408, 429, 500, 502, 503, 504}} — the transient
 *       server family);</li>
 *   <li>statuses a method declares in {@code @OnError} — only when
 *       {@link #onErrorStatuses() onErrorStatuses} is on (default off: a
 *       declared status is a terminal contract, and the final failure
 *       stays a typed {@code HandledException});</li>
 *   <li>non-idempotent methods (POST, PATCH) — only when
 *       {@link #nonIdempotent() nonIdempotent} is on (default off, per
 *       RFC 9110: a retry happens after the request was sent, so a lost
 *       response on a successful POST is a classic double-submit).</li>
 * </ul>
 * The wait between attempts is exponential with a cap and full jitter:
 * after the {@code n}-th failed attempt the bound grows as
 * {@code min(maxBackoffMs, initialBackoffMs · 2^(n-1))} and the actual
 * wait is a uniform draw in {@code [0, bound]}. A parseable
 * {@code Retry-After} header on a failed response (delta-seconds or
 * HTTP-date) sets a deterministic lower bound on that wait.
 *
 * <p>The declared policy applies to synchronous and
 * {@code CompletableFuture}-returning methods alike: the same retry
 * decisions, backoff and final exception on both circuits (ADR-0008).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /**
     * Total attempts including the first, {@code >= 1}. The default of
     * {@code 1} never retries — it is the explicit off-switch; any real
     * retry declaration states its budget explicitly.
     */
    int times() default 1;

    /**
     * The bound of the wait after the first failed attempt, in
     * milliseconds. Non-negative and must not exceed
     * {@link #maxBackoffMs()} (validated when the policy is built).
     */
    long initialBackoffMs() default 200;

    /**
     * The cap applied to the exponential growth of the wait, in
     * milliseconds. Non-negative and must not be less than
     * {@link #initialBackoffMs()} (validated when the policy is built).
     */
    long maxBackoffMs() default 5_000;

    /**
     * The statuses a response is retried for. Replaces the default
     * transient family ({@code {408, 429, 500, 502, 503, 504}})
     * entirely; values must be in {@code [100, 599]} (validated when the
     * policy is built).
     */
    int[] statuses() default {408, 429, 500, 502, 503, 504};

    /**
     * Whether transport failures are retried.
     */
    boolean transport() default true;

    /**
     * Whether statuses declared in {@code @OnError.statuses} participate
     * in the retry decision instead of being terminal.
     */
    boolean onErrorStatuses() default false;

    /**
     * Whether non-idempotent methods (POST, PATCH) are retried.
     */
    boolean nonIdempotent() default false;
}
