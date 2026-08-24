package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RedirectMethodController;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossOriginRedirectTest extends BaseWireMockTest {

    private WireMockServer otherOriginServer;

    @AfterEach
    void tearDownOtherOrigin() {
        if (otherOriginServer != null) {
            otherOriginServer.stop();
        }
    }

    @Test
    @DisplayName("cross-origin redirect drops the ambient auth header")
    void crossOriginRedirectDropsAuthHeader() {
        otherOriginServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        otherOriginServer.start();

        otherOriginServer.stubFor(get(urlEqualTo("/api/target"))
                .willReturn(aResponse().withStatus(200).withBody("cross-origin-result")));

        // authorized client: every request carries the ambient Authorization header
        moduleInfo.getTestAuthProvider().updateAuthHeader("Authorization", "Bearer secret-token");
        moduleInfo.getTestAuthProvider().setAuthorized(true);

        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "http://localhost:" + otherOriginServer.port() + "/api/target")));

        String result = controller.getWithRedirect();

        assertEquals("cross-origin-result", result);
        otherOriginServer.verify(getRequestedFor(urlEqualTo("/api/target"))
                .withHeader("Authorization", absent()));
    }

    @Test
    @DisplayName("same-origin redirect keeps the ambient auth header")
    void sameOriginRedirectKeepsAuthHeader() {
        moduleInfo.getTestAuthProvider().updateAuthHeader("Authorization", "Bearer secret-token");
        moduleInfo.getTestAuthProvider().setAuthorized(true);

        stubFor(get(urlEqualTo("/api/same-origin"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/same-origin-target")));
        stubFor(get(urlEqualTo("/api/same-origin-target"))
                .willReturn(aResponse().withStatus(200).withBody("same-origin-result")));

        RedirectMethodController redirectController = client.get(RedirectMethodController.class);
        String result = redirectController.getSameOriginRedirect();

        assertEquals("same-origin-result", result);
        verify(getRequestedFor(urlEqualTo("/api/same-origin-target"))
                .withHeader("Authorization", equalTo("Bearer secret-token")));
    }
}
