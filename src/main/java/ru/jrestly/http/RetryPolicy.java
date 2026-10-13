package ru.jrestly.http;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * How many times a failed call is repeated and how long to wait between
 * attempts (ADR-0008).
 *
 * <p>A policy is a pure value: immutable and thread-safe, created through
 * the {@link Builder builder} (or one of the statics). A client carries at
 * most one policy; a per-method {@code @Retry} override replaces it
 * entirely — the annotation is a self-contained policy (no field-by-field
 * merge), and its {@code times = 1} default is the explicit off-switch on
 * a method of a client that has retry enabled.
 *
 * <p>What is retried under this policy:
 * <ul>
 *   <li>transport failures — any {@link java.io.IOException} from the send
 *       (connect timeout, request timeout, connection reset, DNS blips):
 *       when {@link #isRetryTransport() retryTransport} is on
 *       (default);</li>
 *   <li>responses whose status is in
 *       {@link #getRetryableStatuses() retryableStatuses} (default
 *       {@code {408, 429, 500, 502, 503, 504}}) — the transient server
 *       family;</li>
 *   <li>a status-acceptable response whose body the codec cannot bind
 *       (the bytes arrived but do not fit the declared type, ADR-0011):
 *       only when {@link #isRetryDecodeFailures() retryDecodeFailures} is
 *       on (default off — unlike the axes above, the decode probe runs on
 *       every <em>success</em> response, so enabling the general retry
 *       stays a zero change on the success path); when attempts are
 *       exhausted on an unbindable body, the final failure is the codec's
 *       own unchecked exception — the same one the caller sees without
 *       retry (no new type, no wrapping);</li>
 *   <li>statuses a method declares in {@code @OnError}: only when
 *       {@link #isRetryOnErrorStatuses() retryOnErrorStatuses} is on
 *       (default off — a declared status is a terminal contract by
 *       default);</li>
 *   <li>non-idempotent methods (POST, PATCH): only when
 *       {@link #isRetryNonIdempotent() retryNonIdempotent} is on
 *       (default off, per RFC 9110).</li>
 * </ul>
 * {@code InterruptedException} is never retried, whatever these flags
 * say: the caller's thread was interrupted, so failing fast is the
 * preserved semantics.
 *
 * <p>Backoff between attempts is exponential with a cap and <em>full
 * jitter</em>: after the {@code n}-th failed attempt the deterministic bound
 * is {@code min(maxBackoff, initialBackoff · 2^(n-1))} and the actual wait
 * is a uniform draw in {@code [0, bound]} (the AWS architecture-blog
 * recipe — without jitter, all clients of a flapping server retry on the
 * same beat and re-break it). A fixed wait is expressible as
 * {@code initialBackoff == maxBackoff}, not as a separate mode. When the
 * failed response carries a parseable {@code Retry-After} header
 * (delta-seconds or HTTP-date, ADR-0009 candidate 3), the server's explicit
 * signal wins: the wait is exactly {@code max(bound, retryAfter)}
 * (deterministic, no jitter).
 *
 * <p>One policy covers both call styles: it applies identically to
 * synchronous methods and to {@code CompletableFuture}-returning methods
 * (ADR-0008: uniform across sync/async). The only visible difference is
 * how the wait is spent — the synchronous circuit sleeps the calling
 * thread, the async one defers the next attempt on
 * {@code CompletableFuture.delayedExecutor} without parking a virtual
 * thread — while the retry decisions, the backoff math and the final
 * exception are the same on both circuits.
 *
 * <p>{@link #maxAttempts() maxAttempts} counts the first attempt: a policy
 * with {@code maxAttempts = 1} never waits and never retries — it is
 * {@link #off()} and it is the client-level default, so enabling retry is
 * an opt-in (0.x behavior-change discipline).
 */
public final class RetryPolicy {

    /**
     * The transient server family (ADR-0008, Aspect 1): request timeout,
     * too many requests, and the flapping-proxy family. 501 and 505 are
     * deliberately excluded — they are deterministic.
     */
    static final Set<Integer> DEFAULT_RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Set<Integer> retryableStatuses;
    private final boolean retryTransport;
    private final boolean retryDecodeFailures;
    private final boolean retryOnErrorStatuses;
    private final boolean retryNonIdempotent;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        // The builder's set is private to it (the caller's collection is
        // copied in), so this wrapper is the contract, not a copy.
        this.retryableStatuses = Collections.unmodifiableSet(builder.retryableStatuses);
        this.retryTransport = builder.retryTransport;
        this.retryDecodeFailures = builder.retryDecodeFailures;
        this.retryOnErrorStatuses = builder.retryOnErrorStatuses;
        this.retryNonIdempotent = builder.retryNonIdempotent;
    }

    /**
     * A policy that retries with the ADR defaults: 3 attempts, 200 ms
     * initial backoff capped at 5 s, the default retryable statuses,
     * transport failures retried, {@code @OnError} statuses and
     * non-idempotent methods not.
     */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    // The client-level default sits on a per-request path
    // (ModuleInfo.getRetryPolicy), so it is one shared immutable instance
    // rather than a fresh allocation per call.
    private static final RetryPolicy OFF = builder().maxAttempts(1).build();

    /**
     * The off policy: {@code maxAttempts = 1}, so the first attempt is the
     * only one. The client-level default (a shared instance).
     */
    public static RetryPolicy off() {
        return OFF;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return total attempts including the first, always {@code >= 1}
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @return the bound of the wait after the first failed attempt
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * @return the cap applied to the exponential growth of the wait
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * @return the statuses a response is retried for; unmodifiable and
     *         owned by this policy (mutating the collection passed to the
     *         builder has no effect)
     */
    public Set<Integer> getRetryableStatuses() {
        return retryableStatuses;
    }

    public boolean isRetryTransport() {
        return retryTransport;
    }

    /**
     * @return {@code true} when a status-acceptable response whose body
     *         the codec cannot bind is retried (ADR-0011); default
     *         {@code false} — a distinct opt-in, see the class-level
     *         documentation for why it does not ride the general enable
     */
    public boolean isRetryDecodeFailures() {
        return retryDecodeFailures;
    }

    public boolean isRetryOnErrorStatuses() {
        return retryOnErrorStatuses;
    }

    public boolean isRetryNonIdempotent() {
        return retryNonIdempotent;
    }

    /**
     * @return {@code true} when this policy waits and retries at all
     *         ({@code maxAttempts > 1})
     */
    public boolean isEnabled() {
        return maxAttempts > 1;
    }

    /**
     * The wait before the next attempt after the {@code failedAttempt}-th
     * failure (1-based; the attempt that just failed). Pure on the policy
     * and the given {@code random}; the production call site supplies a
     * shared generator, tests inject a deterministic one (the seam is
     * package-private on purpose).
     *
     * @param failedAttempt   the 1-based number of the attempt that just
     *                        failed, {@code >= 1}
     * @param retryAfterMillis the failed response's {@code Retry-After}
     *                        header as a wait lower bound in milliseconds —
     *                        delta-seconds times 1000, or the HTTP-date form
     *                        as {@code max(0, date - now)} (ADR-0009
     *                        candidate 3) — or {@code null} when the header
     *                        is absent or unparseable
     * @param random          the generator for the full-jitter draw; never
     *                        {@code null}
     * @return the wait in milliseconds, {@code >= 0}
     */
    long nextDelayMillis(long failedAttempt, Long retryAfterMillis, Random random) {
        Objects.requireNonNull(random, "random");
        long bound = initialBackoff.toMillis();
        for (long attempt = 2; attempt <= failedAttempt; attempt++) {
            bound = Math.min(maxBackoff.toMillis(), bound * 2);
        }
        if (retryAfterMillis != null) {
            return Math.max(bound, retryAfterMillis);
        }
        return random.nextLong(bound + 1);
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxAttempts=" + maxAttempts
                + ", initialBackoff=" + initialBackoff
                + ", maxBackoff=" + maxBackoff
                + ", retryableStatuses=" + retryableStatuses
                + ", retryTransport=" + retryTransport
                + ", retryDecodeFailures=" + retryDecodeFailures
                + ", retryOnErrorStatuses=" + retryOnErrorStatuses
                + ", retryNonIdempotent=" + retryNonIdempotent + "]";
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private Set<Integer> retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        private boolean retryTransport = true;
        private boolean retryDecodeFailures = false;
        private boolean retryOnErrorStatuses = false;
        private boolean retryNonIdempotent = false;

        private Builder() {
        }

        /**
         * @param maxAttempts total attempts including the first,
         *                    {@code >= 1} (validated in
         *                    {@link #build() build})
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
            return this;
        }

        /**
         * Replaces the retried statuses. The given collection is copied
         * (duplicates collapse); the policy keeps its own unmodifiable
         * view, so mutating the argument after {@code build()} has no
         * effect. Status values must be in {@code [100, 599]} (validated
         * in {@link #build() build}).
         */
        public Builder retryableStatuses(Collection<Integer> retryableStatuses) {
            this.retryableStatuses = new LinkedHashSet<>();
            for (Integer status : Objects.requireNonNull(retryableStatuses, "retryableStatuses")) {
                this.retryableStatuses.add(Objects.requireNonNull(status, "status"));
            }
            return this;
        }

        public Builder retryTransport(boolean retryTransport) {
            this.retryTransport = retryTransport;
            return this;
        }

        /**
         * Whether a status-acceptable response whose body the codec cannot
         * bind is retried (ADR-0011). Default {@code false}: unlike
         * {@link #retryTransport(boolean) transport} and the status axis
         * (which only ever touch a failed response), the decode probe runs
         * on every success response — and a final success response is then
         * decoded twice (probe, then the real decode) — so this axis is a
         * distinct opt-in: "enable retry" stays a zero change on the
         * success path.
         */
        public Builder retryDecodeFailures(boolean retryDecodeFailures) {
            this.retryDecodeFailures = retryDecodeFailures;
            return this;
        }

        public Builder retryOnErrorStatuses(boolean retryOnErrorStatuses) {
            this.retryOnErrorStatuses = retryOnErrorStatuses;
            return this;
        }

        public Builder retryNonIdempotent(boolean retryNonIdempotent) {
            this.retryNonIdempotent = retryNonIdempotent;
            return this;
        }

        public RetryPolicy build() {
            if (maxAttempts < 1) {
                throw new IllegalStateException("maxAttempts must be >= 1, got " + maxAttempts);
            }
            if (initialBackoff.isNegative()) {
                throw new IllegalStateException("initialBackoff must not be negative, got " + initialBackoff);
            }
            if (maxBackoff.isNegative()) {
                throw new IllegalStateException("maxBackoff must not be negative, got " + maxBackoff);
            }
            if (initialBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalStateException(
                        "initialBackoff must not exceed maxBackoff, got "
                                + initialBackoff + " > " + maxBackoff);
            }
            for (Integer status : retryableStatuses) {
                if (status < 100 || status > 599) {
                    throw new IllegalStateException("retryable status must be in [100, 599], got " + status);
                }
            }
            return new RetryPolicy(this);
        }
    }
}
