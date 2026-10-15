package ru.jrestly.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable representation of an authentication token and its expiration metadata.
 *
 * @param value the raw token string (e.g. JWT or opaque bearer token), never {@code null}
 * @param expiresAt the instant at which the token expires, or {@code null} if it never expires
 */
public record Token(String value, Instant expiresAt) {

    /**
     * Compact constructor validating the token value.
     *
     * @param value the token string
     * @param expiresAt the expiration instant
     */
    public Token {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a token with no expiration (never expires).
     *
     * @param value the token string, must not be {@code null}
     * @return a perpetual token
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static Token of(String value) {
        return new Token(value, null);
    }

    /**
     * Creates a token with an absolute expiration timestamp.
     *
     * @param value the token string, must not be {@code null}
     * @param expiresAt the expiration instant, or {@code null} for never
     * @return a token with the specified expiration
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static Token of(String value, Instant expiresAt) {
        return new Token(value, expiresAt);
    }

    /**
     * Creates a token with a relative time-to-live duration from now.
     *
     * @param value the token string, must not be {@code null}
     * @param ttl the duration for which the token is valid, must not be {@code null}
     * @return a token with expiration set to {@code Instant.now().plus(ttl)}
     * @throws NullPointerException if {@code value} or {@code ttl} is {@code null}
     */
    public static Token of(String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        return new Token(value, Instant.now().plus(ttl));
    }

    /**
     * Checks if the token has expired relative to {@link Instant#now()}.
     *
     * @return {@code true} if the token has an expiration instant and it is in the past
     */
    public boolean isExpired() {
        return isExpired(Duration.ZERO);
    }

    /**
     * Checks if the token is expired or will expire within the given refresh buffer duration.
     *
     * @param refreshBuffer the safety window to account for network latency or clock skew
     * @return {@code true} if the token will expire within the safety window
     */
    public boolean isExpired(Duration refreshBuffer) {
        if (expiresAt == null) {
            return false;
        }
        Duration buffer = refreshBuffer != null ? refreshBuffer : Duration.ZERO;
        return Instant.now().plus(buffer).isAfter(expiresAt);
    }
}
