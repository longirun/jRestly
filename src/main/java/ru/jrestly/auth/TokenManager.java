package ru.jrestly.auth;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-safe token manager that caches tokens, evaluates expiration with a safety buffer,
 * and guarantees single-flight token acquisition across concurrent threads (preventing
 * thundering herd / stampede on auth servers).
 */
public interface TokenManager {

    /**
     * Obtains a valid token string, refreshing it if absent or expired.
     * Concurrent calls while a token is being refreshed share the same in-flight task.
     *
     * @return the token string, never {@code null}
     */
    String getToken();

    /**
     * Invalidates any currently cached token and forces a fresh acquisition on the next call.
     */
    void invalidate();

    /**
     * Creates a new builder for configuring a {@link TokenManager}.
     *
     * @return a fresh builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a {@link TokenManager} with the given {@link Token} supplier.
     *
     * @param supplier token supplier, must not be {@code null}
     * @return a new token manager
     * @throws NullPointerException if {@code supplier} is {@code null}
     */
    static TokenManager of(Supplier<Token> supplier) {
        return builder().supplier(supplier).build();
    }

    /**
     * Creates a simple {@link TokenManager} for a string token supplier with fixed TTL.
     *
     * @param stringSupplier token string supplier, must not be {@code null}
     * @param ttl time-to-live duration for each acquired token, must not be {@code null}
     * @return a new token manager
     * @throws NullPointerException if {@code stringSupplier} or {@code ttl} is {@code null}
     */
    static TokenManager of(Supplier<String> stringSupplier, Duration ttl) {
        Objects.requireNonNull(stringSupplier, "stringSupplier");
        Objects.requireNonNull(ttl, "ttl");
        return builder().supplier(() -> Token.of(stringSupplier.get(), ttl)).build();
    }

    /**
     * Creates a perpetual {@link TokenManager} for a static or perpetually valid token supplier.
     *
     * @param stringSupplier token string supplier, must not be {@code null}
     * @return a new token manager
     * @throws NullPointerException if {@code stringSupplier} is {@code null}
     */
    static TokenManager ofPerpetual(Supplier<String> stringSupplier) {
        Objects.requireNonNull(stringSupplier, "stringSupplier");
        return builder().supplier(() -> Token.of(stringSupplier.get())).build();
    }

    /**
     * Builder for configuring a {@link TokenManager}.
     */
    class Builder {
        private Supplier<Token> supplier;
        private Duration refreshBuffer = Duration.ZERO;

        /**
         * Constructs a new {@link Builder}.
         */
        public Builder() {
        }

        /**
         * Sets the token supplier supplying a {@link Token} with expiration metadata.
         *
         * @param supplier the token supplier, must not be {@code null}
         * @return this builder, for chaining
         */
        public Builder supplier(Supplier<Token> supplier) {
            this.supplier = Objects.requireNonNull(supplier, "supplier");
            return this;
        }

        /**
         * Sets a string token supplier with a fixed time-to-live duration.
         *
         * @param stringSupplier the string supplier, must not be {@code null}
         * @param ttl time-to-live duration, must not be {@code null}
         * @return this builder, for chaining
         */
        public Builder stringSupplier(Supplier<String> stringSupplier, Duration ttl) {
            Objects.requireNonNull(stringSupplier, "stringSupplier");
            Objects.requireNonNull(ttl, "ttl");
            this.supplier = () -> Token.of(stringSupplier.get(), ttl);
            return this;
        }

        /**
         * Sets the safety window to account for network latency or clock skew when checking expiration.
         *
         * @param refreshBuffer the refresh buffer duration, must not be {@code null}
         * @return this builder, for chaining
         */
        public Builder refreshBuffer(Duration refreshBuffer) {
            this.refreshBuffer = Objects.requireNonNull(refreshBuffer, "refreshBuffer");
            return this;
        }

        /**
         * Builds a thread-safe single-flight {@link TokenManager}.
         *
         * @return the constructed token manager
         * @throws IllegalStateException if {@code supplier} was not configured
         */
        public TokenManager build() {
            if (supplier == null) {
                throw new IllegalStateException("Supplier must be provided to build TokenManager");
            }
            return new SingleFlightTokenManager(supplier, refreshBuffer);
        }
    }
}
