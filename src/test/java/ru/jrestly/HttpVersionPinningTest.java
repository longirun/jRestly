package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.HttpVersionController;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP version pinning smoke tests over plain http. On cleartext the JDK client
 * always speaks 1.1 on the wire, so these verify that pinning (client-level and
 * per-method) does not break the request path, redirect hops included — not the
 * wire protocol itself.
 */
class HttpVersionPinningTest extends BaseWireMockTest {

    @Test
    @DisplayName("client pinned to HTTP/1.1 sends requests and parses responses")
    void clientPinnedToHttp11RequestSucceeds() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Pinned\",\"description\":\"d\"}")));

        JRestlyClient pinned = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            TestController pinnedController = pinned.get(TestController.class);

            TestDto result = pinnedController.getItem(42L);

            assertNotNull(result);
            assertEquals(42L, result.id());
            assertEquals("Pinned", result.name());
        } finally {
            pinned.close();
        }
    }

    @Test
    @DisplayName("@HttpVersion(HTTP_1_1) method override does not break the request path")
    void annotationOverrideRequestSucceeds() {
        stubFor(get(urlEqualTo("/api/version-pinned"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("pinned-ok")));

        HttpVersionController versionController = client.get(HttpVersionController.class);

        assertEquals("pinned-ok", versionController.getPinnedToHttp11());
        verify(1, getRequestedFor(urlEqualTo("/api/version-pinned")));
    }

    @Test
    @DisplayName("a method without @HttpVersion keeps working (client default inherited)")
    void methodWithoutAnnotationInheritsClientDefault() {
        stubFor(get(urlEqualTo("/api/version-unpinned"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("unpinned-ok")));

        HttpVersionController versionController = client.get(HttpVersionController.class);

        assertEquals("unpinned-ok", versionController.getUnpinned());
    }

    @Test
    @DisplayName("@HttpVersion(HTTP_1_1) + @FollowRedirects(count=1): both hops complete, final body returned")
    void annotationPinnedRedirectBothHopsSucceed() {
        stubFor(get(urlEqualTo("/api/version-redirect"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/version-redirect-final")));

        stubFor(get(urlEqualTo("/api/version-redirect-final"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("redirect-final-ok")));

        HttpVersionController versionController = client.get(HttpVersionController.class);

        assertEquals("redirect-final-ok", versionController.getRedirectPinnedToHttp11());
        verify(1, getRequestedFor(urlEqualTo("/api/version-redirect")));
        verify(1, getRequestedFor(urlEqualTo("/api/version-redirect-final")));
    }

    @Test
    @DisplayName("client pinned to HTTP/1.1 follows a redirect chain (version applies to every hop)")
    void clientPinnedToHttp11FollowsRedirect() {
        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/redirected")));

        stubFor(get(urlEqualTo("/api/redirected"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("client-pinned-redirect-ok")));

        JRestlyClient pinned = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            TestController pinnedController = pinned.get(TestController.class);

            assertEquals("client-pinned-redirect-ok", pinnedController.getWithRedirect());
            verify(1, getRequestedFor(urlEqualTo("/api/redirect")));
            verify(1, getRequestedFor(urlEqualTo("/api/redirected")));
        } finally {
            pinned.close();
        }
    }
}
