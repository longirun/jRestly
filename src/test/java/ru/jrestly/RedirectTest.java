package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.UnexpectedStatusException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RedirectTest extends BaseWireMockTest {

    @Test
    @DisplayName("@FollowRedirects follows a 302 redirect and returns the final body to the caller")
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

        assertEquals("redirected-result", result);
        verify(1, getRequestedFor(urlEqualTo("/api/redirect")));
        verify(1, getRequestedFor(urlEqualTo("/api/redirected")));
    }

    @Test
    @DisplayName("301 redirect is followed via GET")
    void follows301ViaGet() {
        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", baseUrl() + "/api/redirected")));

        stubFor(get(urlEqualTo("/api/redirected"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("moved-result")));

        String result = controller.getWithRedirect();

        assertEquals("moved-result", result);
    }

    @Test
    @DisplayName("303 after POST downgrades to GET and drops the body")
    void redirect303DowngradesPostToGet() {
        stubFor(post(urlEqualTo("/api/redirect-post"))
                .willReturn(aResponse()
                        .withStatus(303)
                        .withHeader("Location", baseUrl() + "/api/redirected")));

        stubFor(get(urlEqualTo("/api/redirected"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("downgraded-result")));

        String result = controller.postWithRedirect(new TestDto(1L, "redirected", null));

        assertEquals("downgraded-result", result);
        verify(1, getRequestedFor(urlEqualTo("/api/redirected")));
        verify(0, postRequestedFor(urlEqualTo("/api/redirected")));
    }

    @Test
    @DisplayName("307 after POST preserves the method and the body")
    void redirect307PreservesPostBody() {
        stubFor(post(urlEqualTo("/api/redirect-post-preserve"))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", baseUrl() + "/api/redirected-target")));

        stubFor(post(urlEqualTo("/api/redirected-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"name\":\"replayed\",\"description\":null}")));

        TestDto result = controller.postWithRedirectPreservingBody(new TestDto(1L, "original", null));

        assertNotNull(result);
        assertEquals(7L, result.id());
        assertEquals("replayed", result.name());
        verify(postRequestedFor(urlEqualTo("/api/redirected-target"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("original")));
    }

    @Test
    @DisplayName("hop counter limits the redirect chain and the last redirect response is surfaced to the caller")
    void tooManyRedirectsSurfacesLastResponse() {
        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/hop1")));
        stubFor(get(urlEqualTo("/api/hop1"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/hop2")));
        stubFor(get(urlEqualTo("/api/hop2"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/hop3")));
        stubFor(get(urlEqualTo("/api/hop3"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", baseUrl() + "/api/hop4")));
        stubFor(get(urlEqualTo("/api/hop4"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("never-reached")));

        // count = 3: three redirects followed (hop1..hop3), the fourth is not
        UnexpectedStatusException exception = assertThrows(UnexpectedStatusException.class,
                () -> controller.getWithRedirect());

        assertEquals(302, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/hop1")));
        verify(1, getRequestedFor(urlEqualTo("/api/hop2")));
        verify(1, getRequestedFor(urlEqualTo("/api/hop3")));
        verify(0, getRequestedFor(urlEqualTo("/api/hop4")));
    }
}
