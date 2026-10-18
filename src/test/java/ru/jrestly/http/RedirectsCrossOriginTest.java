package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code Redirects.isCrossOrigin} / {@code effectivePort} —
 * the ADR-0002 credential-leakage guard on redirects: a hop is cross-origin
 * when the scheme, host, or effective port differs (case-insensitively), a
 * host-less URI (opaque or relative) is always cross-origin, and an absent
 * port resolves to the scheme default (443 for {@code https}, 80 otherwise).
 *
 * <p>No network — the seams are package-private on purpose, so the branch
 * matrix is pinned directly.
 */
class RedirectsCrossOriginTest {

    @Test
    @DisplayName("identical origins: same origin")
    void identicalOriginsAreSameOrigin() {
        assertFalse(Redirects.isCrossOrigin(
                URI.create("https://example.com/a"),
                URI.create("https://example.com/b")));
    }

    @Test
    @DisplayName("scheme and host compare case-insensitively")
    void schemeAndHostCompareCaseInsensitively() {
        assertFalse(Redirects.isCrossOrigin(
                URI.create("HTTPS://EXAMPLE.COM/a"),
                URI.create("https://example.com/b")));
    }

    @Test
    @DisplayName("different scheme: cross-origin")
    void differentSchemeIsCrossOrigin() {
        assertTrue(Redirects.isCrossOrigin(
                URI.create("http://example.com/a"),
                URI.create("https://example.com/a")));
    }

    @Test
    @DisplayName("null scheme on either side: cross-origin")
    void nullSchemeIsCrossOrigin() {
        assertTrue(Redirects.isCrossOrigin(
                URI.create("/relative"),
                URI.create("http://example.com/a")));
        assertTrue(Redirects.isCrossOrigin(
                URI.create("http://example.com/a"),
                URI.create("/relative")));
    }

    @Test
    @DisplayName("null host on either side (opaque URI): cross-origin")
    void nullHostIsCrossOrigin() {
        assertTrue(Redirects.isCrossOrigin(
                URI.create("foo:/opaque"),
                URI.create("foo://example.com/a")));
        assertTrue(Redirects.isCrossOrigin(
                URI.create("foo://example.com/a"),
                URI.create("foo:/opaque")));
    }

    @Test
    @DisplayName("different host: cross-origin")
    void differentHostIsCrossOrigin() {
        assertTrue(Redirects.isCrossOrigin(
                URI.create("http://example.com/a"),
                URI.create("http://other.example.com/a")));
    }

    @Test
    @DisplayName("explicit default port equals an absent port")
    void explicitDefaultPortEqualsAbsentPort() {
        assertFalse(Redirects.isCrossOrigin(
                URI.create("http://example.com/a"),
                URI.create("http://example.com:80/a")));
        assertFalse(Redirects.isCrossOrigin(
                URI.create("https://example.com/a"),
                URI.create("https://example.com:443/a")));
    }

    @Test
    @DisplayName("different effective ports: cross-origin")
    void differentPortsAreCrossOrigin() {
        assertTrue(Redirects.isCrossOrigin(
                URI.create("http://example.com:80/a"),
                URI.create("http://example.com:8080/a")));
        assertTrue(Redirects.isCrossOrigin(
                URI.create("https://example.com:443/a"),
                URI.create("https://example.com:8443/a")));
    }

    @Test
    @DisplayName("effectivePort: an explicit port wins")
    void explicitPortWins() {
        assertEquals(8080, Redirects.effectivePort(URI.create("http://example.com:8080/a")));
        assertEquals(80, Redirects.effectivePort(URI.create("http://example.com:80/a")));
    }

    @Test
    @DisplayName("effectivePort: an absent port resolves to the scheme default")
    void absentPortResolvesToSchemeDefault() {
        assertEquals(443, Redirects.effectivePort(URI.create("https://example.com/a")));
        assertEquals(443, Redirects.effectivePort(URI.create("HTTPS://example.com/a")));
        assertEquals(80, Redirects.effectivePort(URI.create("http://example.com/a")));
        assertEquals(80, Redirects.effectivePort(URI.create("ftp://example.com/a")));
    }
}
