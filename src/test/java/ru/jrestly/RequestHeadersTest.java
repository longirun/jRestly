package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest extends BaseWireMockTest {

    @Test
    @DisplayName("@RequestHeader adds header to request")
    void customHeaderIsSent() {
        stubFor(get(urlEqualTo("/api/headers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.getWithHeaders("my-custom-value");

        assertEquals("ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers"))
                .withHeader("X-Custom", equalTo("my-custom-value")));
    }

    @Test
    @DisplayName("Auth header is added when provider is authorized")
    void authHeaderIsSentWhenAuthorized() {
        moduleInfo.getTestAuthProvider().setAuthorized(true);
        moduleInfo.getTestAuthProvider().updateAuthHeader("Authorization", "Bearer test-token");

        stubFor(get(urlEqualTo("/api/auth-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"auth\",\"description\":\"ok\"}")));

        controller.getAuthorized();

        verify(getRequestedFor(urlEqualTo("/api/auth-check"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }
}
