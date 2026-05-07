package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest extends BaseWireMockTest {

    @Test
    @DisplayName("@RequestHeader добавляет заголовок в запрос")
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
    @DisplayName("Auth header подставляется когда провайдер авторизован")
    void authHeaderIsSentWhenAuthorized() {
        moduleInfo.getTestAuthProvider().setAuthorized(true);
        moduleInfo.getTestAuthProvider().setHeaderName("Authorization");
        moduleInfo.getTestAuthProvider().setHeaders(
                java.util.List.of(org.apache.commons.lang3.tuple.Pair.of("Authorization", "Bearer test-token"))
        );

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
