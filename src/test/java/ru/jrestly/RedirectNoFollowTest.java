package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RedirectMethodController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.UnexpectedStatusException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RedirectNoFollowTest extends BaseWireMockTest {

    @Test
    @DisplayName("GET without @FollowRedirects does not follow a 302 and surfaces it as UnexpectedStatusException")
    void getDoesNotFollowRedirect() {
        stubFor(get(urlEqualTo("/api/redirect-nofollow"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/redirected-target")
                        .withBody("redirect-body")));

        UnexpectedStatusException exception = assertThrows(UnexpectedStatusException.class,
                () -> client.get(RedirectMethodController.class).getWithoutFollowRedirects());

        assertEquals(302, exception.getStatusCode());
        assertEquals("redirect-body", exception.getRawBody());
        assertTrue(exception.getResponseHeaders().stream()
                .anyMatch(h -> h.name().equalsIgnoreCase("Location")),
                "the surfaced response must carry the Location header");
        verify(1, getRequestedFor(urlEqualTo("/api/redirect-nofollow")));
        verify(0, getRequestedFor(urlEqualTo("/api/redirected-target")));
    }

    @Test
    @DisplayName("POST answered with 302 without @FollowRedirects is not followed")
    void postDoesNotFollowRedirect() {
        stubFor(post(urlEqualTo("/api/redirect-post-nofollow"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/redirected-target")));

        UnexpectedStatusException exception = assertThrows(UnexpectedStatusException.class,
                () -> client.get(RedirectMethodController.class)
                        .postWithoutFollowRedirects(new TestDto(1L, "no-follow", null)));

        assertEquals(302, exception.getStatusCode());
        verify(1, postRequestedFor(urlEqualTo("/api/redirect-post-nofollow")));
        verify(0, getRequestedFor(urlEqualTo("/api/redirected-target")));
        verify(0, postRequestedFor(urlEqualTo("/api/redirected-target")));
    }
}
