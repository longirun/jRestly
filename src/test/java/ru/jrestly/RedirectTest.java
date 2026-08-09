package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RedirectTest extends BaseWireMockTest {

    @Test
    @DisplayName("@FollowRedirects follows a 302 redirect")
    void followsRedirect() {
        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/redirected")));

        stubFor(get(urlEqualTo("/api/redirected"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("redirected-result")));

        String result = controller.getWithRedirect();

        assertNull(result);
    }
}
