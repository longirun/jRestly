package ru.jrestly.http;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * How an SSE stream is reconnected after a mid-stream failure or a clean EOF
 * (ADR-0059): how many attempts, how long to wait, and whether {@code [DONE]}
 * ends the stream for good.
 *
 * <p>The policy follows the WHATWG EventSource reconnection model, bounded:
 * a broken stream (an {@link java.io.UncheckedIOException} from the transport
 * or a failing opener) or a clean EOF repeats the request with the last seen
 * event id replayed as {@code Last-Event-ID}. The base wait is the last server
 * {@code retry:} field (persisted across events by the parser), falling back
 * to {@link #getInitialBackoff()}. The wait is capped by
 * {@link #getMaxBackoff()} and may grow exponentially
 * ({@link #getBackoffMultiplier()}) with configurable {@link Jitter jitter} —
 * both off by default, so a policy out of the box waits exactly what the
 * server asked for (3000 ms per WHATWG when it asked nothing).
 *
 * <p>{@link #maxAttempts()} bounds the reconnects of a single stream
 * ({@code 0} disables reconnection entirely); each failed attempt reopens the
 * request with the current {@code Last-Event-ID}. When the attempts are
 * exhausted, the failure that triggered the last attempt is rethrown as the
 * original {@link java.io.UncheckedIOException} (no silent truncation);
 * exhaustion after clean EOFs just ends the stream — there is nothing to
 * report. Exceptions other than {@code UncheckedIOException} never trigger a
 * reconnect: they propagate to the consumer as programming errors.
 *
 * <p>{@link #isStopOnDone()} (default {@code true}) is the LLM-ergonomic
 * extension over the spec: a {@code [DONE]} event ends the stream and cancels
 * reconnection, because EventSource itself has no terminal event.
 *
 * <p>A policy is a pure value: immutable and thread-safe, created through the
 * {@link Builder builder} or {@link #defaults()}.
 */
public final class ReconnectPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double backoffMultiplier;
    private final Jitter jitter;
    private final boolean stopOnDone;
    private final Consumer<ReconnectInfo> onReconnect;
    private final Duration stallTimeout;

    private ReconnectPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.jitter = builder.jitter;
        this.stopOnDone = builder.stopOnDone;
        this.onReconnect = builder.onReconnect;
        this.stallTimeout = builder.stallTimeout;
    }

    private static final ReconnectPolicy DEFAULTS = builder().build();

    /**
     * A policy with the ADR-0059 defaults: 3 reconnect attempts, a 3000 ms
     * fallback wait (the WHATWG default) capped at 30 s, no exponential growth
     * or jitter, and {@code [DONE]} terminating the stream.
     *
     * @return the shared default policy instance
     */
    public static ReconnectPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * A new builder for a {@link ReconnectPolicy}, pre-set to the ADR-0059
     * defaults.
     *
     * @return a new builder; call {@link Builder#build()} to obtain the policy
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The maximum number of reconnects after the initial connection.
     *
     * @return the reconnect bound, always {@code >= 0}
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The fallback wait used when no server {@code retry:} field was seen.
     *
     * @return the fallback wait; default 3000 ms per WHATWG
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * The cap applied to every effective wait, server-suggested or grown.
     *
     * @return the cap applied to the wait; default 30 s
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * The factor applied to the wait on each consecutive reconnect.
     *
     * @return the growth multiplier; default 1.0 (no growth, the WHATWG model)
     */
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * The jitter strategy applied to the computed wait (ADR-0040).
     *
     * @return the jitter strategy; default {@link Jitter#NONE} — the wait is
     *         exactly what the server asked for
     */
    public Jitter getJitter() {
        return jitter;
    }

    /**
     * Whether a {@code [DONE]} event ends the stream and cancels reconnection.
     *
     * @return {@code true} when {@code [DONE]} is terminal; default {@code true}
     */
    public boolean isStopOnDone() {
        return stopOnDone;
    }

    /**
     * The hook invoked before each reconnect wait, for metrics and logs.
     *
     * @return the hook; default a no-op
     */
    public Consumer<ReconnectInfo> getOnReconnect() {
        return onReconnect;
    }

    /**
     * The maximum duration to wait for data between events before treating
     * the connection as stalled and forcing a reconnect (ADR-0059 phase 3).
     *
     * @return the idle-stall timeout, or {@code null} when disabled (the default)
     */
    public Duration stallTimeout() {
        return stallTimeout;
    }

    /**
     * Whether an idle-stall guard is enabled on this policy.
     *
     * @return {@code true} when {@link #stallTimeout()} is set and non-zero
     */
    public boolean hasStallTimeout() {
        return stallTimeout != null;
    }

    /**
     * Whether this policy reconnects at all.
     *
     * @return {@code true} when the policy reconnects ({@code maxAttempts > 0})
     */
    public boolean isEnabled() {
        return maxAttempts > 0;
    }

    /**
     * The wait before the {@code reconnectAttempt}-th reconnect. Pure on the
     * policy, the server hint and the given {@code random}: the base is the
     * last server {@code retry:} value or {@link #getInitialBackoff()},
     * capped by {@link #getMaxBackoff()}, grown by
     * {@link #getBackoffMultiplier()} on every consecutive attempt, then
     * jittered.
     *
     * @param reconnectAttempt    the 1-based reconnect number, {@code >= 1}
     * @param serverRetryMillis   the last server {@code retry:} value in
     *                            milliseconds, or {@code null} when none was seen
     * @param previousDelayMillis the previous computed wait, used by
     *                            {@link Jitter#DECORRELATED}
     * @param random              the generator for the jitter draw; never {@code null}
     * @return the wait in milliseconds, {@code >= 0}
     */
    long nextDelayMillis(int reconnectAttempt, Long serverRetryMillis, long previousDelayMillis, Random random) {
        Objects.requireNonNull(random, "random");
        long base = serverRetryMillis != null ? serverRetryMillis : initialBackoff.toMillis();
        long bound = Math.max(0L, Math.min(maxBackoff.toMillis(), base));
        for (int attempt = 2; attempt <= reconnectAttempt; attempt++) {
            bound = Math.min(maxBackoff.toMillis(), Math.round(bound * backoffMultiplier));
        }

        if (bound <= 0) {
            return 0;
        }

        return switch (jitter) {
            case NONE -> bound;
            case EQUAL -> {
                long half = bound / 2;
                long diff = bound - half;
                yield half + random.nextLong(diff + 1);
            }
            case DECORRELATED -> {
                // The decorrelated floor is the server hint (or initialBackoff), but the
                // whole draw must stay within maxBackoff: an unbounded server retry: must
                // never uncap the wait (ADR-0040/ADR-0059 phase 1).
                long max = maxBackoff.toMillis();
                long min = Math.min(max, base);
                long prev = previousDelayMillis <= 0 ? min : previousDelayMillis;
                long ceiling = Math.min(max, Math.max(min, Math.round(prev * 3.0)));
                long range = ceiling - min;
                long draw = range > 0 ? random.nextLong(range + 1) : 0;
                yield Math.min(max, min + draw);
            }
            case FULL -> random.nextLong(bound + 1);
        };
    }

    /**
     * A single-line rendering of the policy for logs and error messages.
     *
     * @return the policy's state as a string
     */
    @Override
    public String toString() {
        return "ReconnectPolicy[maxAttempts=" + maxAttempts
                + ", initialBackoff=" + initialBackoff
                + ", maxBackoff=" + maxBackoff
                + ", backoffMultiplier=" + backoffMultiplier
                + ", jitter=" + jitter
                + ", stopOnDone=" + stopOnDone
                + ", stallTimeout=" + stallTimeout + "]";
    }

    /**
     * A builder for {@link ReconnectPolicy}, pre-set to the ADR-0059 defaults:
     * 3 reconnect attempts, 3000 ms fallback capped at 30 s, multiplier 1.0,
     * {@link Jitter#NONE}, {@code [DONE]} terminal, no reconnect hook.
     *
     * <p>Each setter replaces one field; {@link #build()} validates the
     * combination and returns the immutable policy.
     */
    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofSeconds(3);
        private Duration maxBackoff = Duration.ofSeconds(30);
        private double backoffMultiplier = 1.0;
        private Jitter jitter = Jitter.NONE;
        private boolean stopOnDone = true;
        private Consumer<ReconnectInfo> onReconnect = info -> {
        };
        private Duration stallTimeout = null;

        private Builder() {
        }

        /**
         * The maximum number of reconnects after the initial connection;
         * {@code 0} disables reconnection entirely.
         *
         * @param maxAttempts the reconnect bound, {@code >= 0}
         * @return this builder, for chaining
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * The fallback wait used when no server {@code retry:} field was seen.
         *
         * @param initialBackoff the fallback wait; non-negative and not above
         *                       {@code maxBackoff}
         * @return this builder, for chaining
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
            return this;
        }

        /**
         * The cap applied to every effective wait, server-suggested or grown.
         *
         * @param maxBackoff the wait cap; non-negative and not below
         *                   {@code initialBackoff}
         * @return this builder, for chaining
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
            return this;
        }

        /**
         * The factor applied to the wait on each consecutive reconnect.
         *
         * @param backoffMultiplier the growth multiplier; {@code >= 1.0},
         *                          default 1.0 (no growth)
         * @return this builder, for chaining
         */
        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /**
         * The jitter strategy applied to the computed wait (ADR-0040).
         *
         * @param jitter the jitter strategy; never {@code null}
         * @return this builder, for chaining
         */
        public Builder jitter(Jitter jitter) {
            this.jitter = Objects.requireNonNull(jitter, "jitter");
            return this;
        }

        /**
         * Whether a {@code [DONE]} event ends the stream and cancels reconnection.
         *
         * @param stopOnDone {@code true} to treat {@code [DONE]} as terminal;
         *                   default {@code true}
         * @return this builder, for chaining
         */
        public Builder stopOnDone(boolean stopOnDone) {
            this.stopOnDone = stopOnDone;
            return this;
        }

        /**
         * The hook invoked before each reconnect wait, for metrics and logs.
         *
         * @param onReconnect the hook; never {@code null}
         * @return this builder, for chaining
         */
        public Builder onReconnect(Consumer<ReconnectInfo> onReconnect) {
            this.onReconnect = Objects.requireNonNull(onReconnect, "onReconnect");
            return this;
        }

        /**
         * The maximum duration to wait for data between events before treating
         * the connection as stalled and forcing a reconnect (ADR-0059 phase 3).
         *
         * <p>When set, a background watchdog monitors data delivery. If no event data
         * is received within {@code stallTimeout}, the active stream is closed from the
         * outside, unblocking the consumer with a {@link java.net.SocketTimeoutException}
         * and triggering the next reconnect attempt.
         *
         * @param stallTimeout the idle timeout; must be positive, or {@code null} to disable
         * @return this builder, for chaining
         */
        public Builder stallTimeout(Duration stallTimeout) {
            if (stallTimeout != null && stallTimeout.compareTo(Duration.ZERO) <= 0) {
                throw new IllegalStateException("stallTimeout must be positive, got " + stallTimeout);
            }
            this.stallTimeout = stallTimeout;
            return this;
        }

        /**
         * Validates the combination and returns the immutable policy.
         *
         * @return the built policy
         * @throws IllegalStateException when a field is out of range:
         *         {@code maxAttempts < 0}, a negative backoff,
         *         {@code initialBackoff > maxBackoff},
         *         {@code backoffMultiplier < 1.0}, or a non-positive {@code stallTimeout}
         */
        public ReconnectPolicy build() {
            if (maxAttempts < 0) {
                throw new IllegalStateException("maxAttempts must be >= 0, got " + maxAttempts);
            }
            if (initialBackoff.isNegative()) {
                throw new IllegalStateException("initialBackoff must not be negative, got " + initialBackoff);
            }
            if (maxBackoff.isNegative()) {
                throw new IllegalStateException("maxBackoff must not be negative, got " + maxBackoff);
            }
            if (initialBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalStateException(
                        "initialBackoff must not exceed maxBackoff, got " + initialBackoff + " > " + maxBackoff);
            }
            if (Double.isNaN(backoffMultiplier) || backoffMultiplier < 1.0) {
                throw new IllegalStateException("backoffMultiplier must be >= 1.0, got " + backoffMultiplier);
            }
            return new ReconnectPolicy(this);
        }
    }
}
