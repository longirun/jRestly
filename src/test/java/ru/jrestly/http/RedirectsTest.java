package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Redirects} — the ADR-0002 redirect policy extracted
 * from {@code HttpTemplate} (ADR-0066): the next-hop derivation (GET downgrade
 * vs. method/body preservation, the missing-Location and no-budget stops), the
 * {@code Location} lookup, and the cross-origin ambient-auth drop.
 *
 * <p>No network — the collaborator is package-private and exercised directly.
 */
class RedirectsTest {

    private static final System.Logger LOGGER = System.getLogger("RedirectsTest");

    private static WireRequest get(String uri) {
        return new WireRequest(
                HttpMethod.GET,
                URI.create(uri),
                List.of(new Header("Authorization", "Bearer ambient")),
                null,
                null);
    }

    private static WireRequest post(String uri) {
        return new WireRequest(
                HttpMethod.POST,
                URI.create(uri),
                List.of(),
                "application/json",
                new byte[] {1, 2});
    }

    @Test
    @DisplayName("no hop budget: nextHop stops the chain")
    void noBudgetStopsTheChain() {
        Redirects redirects = new Redirects(null, List.of(), LOGGER);
        assertNull(redirects.nextHop(get("https://example.com/a"),
                WireResponse.of(302, List.of(new Header("Location", "/b")), (byte[]) null)));
    }

    @Test
    @DisplayName("a non-redirect status or a missing Location stops the chain")
    void nonRedirectOrMissingLocationStopsTheChain() {
        Redirects redirects = new Redirects(3, List.of(), LOGGER);
        assertNull(redirects.nextHop(get("https://example.com/a"), WireResponse.of(200)));
        assertNull(redirects.nextHop(get("https://example.com/a"), WireResponse.of(302)));
    }

    @Test
    @DisplayName("301/302/303 downgrade to GET and drop the body")
    void downgradeDropsTheBody() {
        Redirects redirects = new Redirects(3, List.of(), LOGGER);
        WireRequest next = redirects.nextHop(post("https://example.com/a"),
                WireResponse.of(302, List.of(new Header("Location", "/b")), (byte[]) null));

        assertNotNull(next);
        assertEquals(HttpMethod.GET, next.method());
        assertNull(next.body());
        assertNull(next.contentType());
        assertEquals(URI.create("https://example.com/b"), next.uri());
    }

    @Test
    @DisplayName("307/308 preserve the method and re-send the body")
    void preserveMethodReSendsTheBody() {
        Redirects redirects = new Redirects(3, List.of(), LOGGER);
        WireRequest next = redirects.nextHop(post("https://example.com/a"),
                WireResponse.of(307, List.of(new Header("Location", "/b")), (byte[]) null));

        assertNotNull(next);
        assertEquals(HttpMethod.POST, next.method());
        assertArrayEquals(new byte[] {1, 2}, next.body());
        assertEquals("application/json", next.contentType());
    }

    @Test
    @DisplayName("a cross-origin hop drops the ambient auth header")
    void crossOriginDropsTheAmbientAuthHeader() {
        Redirects redirects = new Redirects(3, List.of("Authorization"), LOGGER);
        WireRequest next = redirects.nextHop(get("https://example.com/a"),
                WireResponse.of(302, List.of(new Header("Location", "https://other.example.com/b")), (byte[]) null));

        assertNotNull(next);
        assertTrue(next.headers().isEmpty());
    }

    @Test
    @DisplayName("no ambient auth names: the headers pass through untouched")
    void noAmbientNamesKeepsTheHeaders() {
        Redirects redirects = new Redirects(3, null, LOGGER);
        WireRequest next = redirects.nextHop(get("https://example.com/a"),
                WireResponse.of(302, List.of(new Header("Location", "https://other.example.com/b")), (byte[]) null));

        assertNotNull(next);
        assertEquals(1, next.headers().size());
    }

    @Test
    @DisplayName("location: a null-named header is skipped, a non-redirect status yields null")
    void locationSkipsNullNamesAndNonRedirectStatuses() {
        assertEquals("/b", Redirects.location(WireResponse.of(302,
                List.of(new Header(null, "x"), new Header("Location", "/b")), (byte[]) null)));
        assertNull(Redirects.location(WireResponse.of(200,
                List.of(new Header("Location", "/b")), (byte[]) null)));
    }

    @Test
    @DisplayName("logExhausted: the budget and Location combinations all no-op or log cleanly")
    void logExhaustedCombinations() {
        WireResponse redirect = WireResponse.of(302, List.of(new Header("Location", "/b")), (byte[]) null);
        assertDoesNotThrow(() -> new Redirects(null, List.of(), LOGGER).logExhausted(redirect));
        assertDoesNotThrow(() -> new Redirects(0, List.of(), LOGGER).logExhausted(redirect));
        assertDoesNotThrow(() -> new Redirects(0, List.of(), LOGGER).logExhausted(WireResponse.of(200)));
    }
}
