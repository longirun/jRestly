package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RedirectMethodController;
import ru.jrestly.fixtures.TestDto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RedirectMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("307 after PUT preserves the method, the body and Content-Type")
    void redirect307PreservesPutBody() {
        stubFor(put(urlEqualTo("/api/redirect-put"))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", baseUrl() + "/api/redirect-put-target")));

        stubFor(put(urlEqualTo("/api/redirect-put-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":9,\"name\":\"put-replayed\",\"description\":null}")));

        TestDto result = client.get(RedirectMethodController.class)
                .putWithRedirect(new TestDto(5L, "put-original", null));

        assertNotNull(result);
        assertEquals(9L, result.id());
        assertEquals("put-replayed", result.name());
        verify(putRequestedFor(urlEqualTo("/api/redirect-put-target"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("put-original")));
        verify(0, getRequestedFor(urlEqualTo("/api/redirect-put-target")));
    }

    @Test
    @DisplayName("308 after PATCH preserves the method, the body and Content-Type")
    void redirect308PreservesPatchBody() {
        stubFor(patch(urlEqualTo("/api/redirect-patch"))
                .willReturn(aResponse()
                        .withStatus(308)
                        .withHeader("Location", baseUrl() + "/api/redirect-patch-target")));

        stubFor(patch(urlEqualTo("/api/redirect-patch-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":11,\"name\":\"patch-replayed\",\"description\":null}")));

        TestDto result = client.get(RedirectMethodController.class)
                .patchWithRedirect(new TestDto(6L, "patch-original", null));

        assertNotNull(result);
        assertEquals(11L, result.id());
        assertEquals("patch-replayed", result.name());
        verify(patchRequestedFor(urlEqualTo("/api/redirect-patch-target"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("patch-original")));
        verify(0, getRequestedFor(urlEqualTo("/api/redirect-patch-target")));
    }

    @Test
    @DisplayName("A relative Location header is resolved against the current request URI")
    void followsRedirectWithRelativeLocation() {
        stubFor(get(urlEqualTo("/api/redirect-relative"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/api/relative-target")));

        stubFor(get(urlEqualTo("/api/relative-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("relative-ok")));

        String result = client.get(RedirectMethodController.class).getWithRelativeRedirectLocation();

        assertEquals("relative-ok", result);
        // 302 downgrades to GET and drops the body, so the follow-up carries no Content-Type
        verify(1, getRequestedFor(urlEqualTo("/api/relative-target"))
                .withoutHeader("Content-Type"));
    }
}
