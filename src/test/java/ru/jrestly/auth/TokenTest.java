package ru.jrestly.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTest {

    @Test
    @DisplayName("Token.of(value) creates perpetual non-expiring token")
    void perpetualToken() {
        Token token = Token.of("perpetual-secret");
        assertEquals("perpetual-secret", token.value());
        assertNull(token.expiresAt());
        assertFalse(token.isExpired());
        assertFalse(token.isExpired(Duration.ofDays(365)));
    }

    @Test
    @DisplayName("Token.of(value, instant) honors absolute expiration")
    void absoluteExpiration() {
        Instant future = Instant.now().plusSeconds(60);
        Token valid = Token.of("valid-token", future);
        assertEquals("valid-token", valid.value());
        assertEquals(future, valid.expiresAt());
        assertFalse(valid.isExpired());

        Instant past = Instant.now().minusSeconds(10);
        Token expired = Token.of("expired-token", past);
        assertTrue(expired.isExpired());
    }

    @Test
    @DisplayName("Token.of(value, ttl) creates token relative to now")
    void relativeTtl() {
        Token token = Token.of("ttl-token", Duration.ofSeconds(30));
        assertNotNull(token.expiresAt());
        assertFalse(token.isExpired());
    }

    @Test
    @DisplayName("Token.isExpired(refreshBuffer) triggers expiration before actual deadline")
    void refreshBufferWindow() {
        Instant expiresAt = Instant.now().plusSeconds(5);
        Token token = Token.of("buffered-token", expiresAt);

        // With no buffer or small buffer, it's not expired yet
        assertFalse(token.isExpired(Duration.ofSeconds(1)));

        // With 10s buffer, 5s < 10s -> considered expired for proactive refresh
        assertTrue(token.isExpired(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("Token constructor and factories reject null values")
    void nullValidation() {
        assertThrows(NullPointerException.class, () -> Token.of(null));
        assertThrows(NullPointerException.class, () -> Token.of(null, Instant.now()));
        assertThrows(NullPointerException.class, () -> Token.of("val", (Duration) null));
        assertThrows(NullPointerException.class, () -> new Token(null, Instant.now()));
    }
}
