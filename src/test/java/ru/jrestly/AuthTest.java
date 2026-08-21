package ru.jrestly;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest extends BaseWireMockTest {

    @Test
    @DisplayName("@Anonymous method does not require authorization")
    void anonymousMethodNoAuthRequired() {
        stubFor(get(urlEqualTo("/api/public"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"public\",\"description\":\"anon\"}")));

        TestDto result = controller.getAnonymous();

        assertNotNull(result);
        assertEquals("public", result.name());
    }

    @Test
    @DisplayName("@SetAuthDetails captures header from response")
    void setAuthDetailsCapturesHeader() {
        stubFor(post(urlEqualTo("/api/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("access-token", "captured-token-123")
                        .withBody("logged-in")));

        String result = controller.login("credentials");

        assertEquals("logged-in", result);

        Pair<String, String> authHeader = moduleInfo.getTestAuthProvider().getAuthHeader();
        assertNotNull(authHeader);
        assertEquals("access-token", authHeader.getKey());
        assertEquals("captured-token-123", authHeader.getValue());
    }

    @Test
    @DisplayName("@Authorization method triggers login when not authorized")
    void authorizationMethodTriggersLogin() {
        moduleInfo.getTestAuthProvider().setAuthorized(true);
        moduleInfo.getTestAuthProvider().setHeaderName("access-token");
        moduleInfo.getTestAuthProvider().setHeaders(
                List.of(Pair.of("access-token", "initial-token"))
        );

        stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("access-token", "refreshed-token")
                        .withBody("auth-ok")));

        String result = controller.authLogin("creds");

        assertEquals("auth-ok", result);
    }

    @Test
    @DisplayName("updateAuthHeader() sends the new header with the next request")
    void updateAuthHeaderSendsNewHeader() {
        client.updateAuthHeader("Authorization", "token-manual");

        stubFor(get(urlEqualTo("/api/auth-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"item\",\"description\":\"ok\"}")));

        controller.getAuthorized();

        verify(getRequestedFor(urlEqualTo("/api/auth-check"))
                .withHeader("Authorization", equalTo("token-manual")));
    }

    @Test
    @DisplayName("manual updateAuthHeader() overrides a captured token")
    void manualUpdateOverridesCapturedToken() {
        JRestlyClient restlyClient = JRestlyClient.builder().baseUrl(baseUrl()).build();
        TestController restlyController = restlyClient.get(TestController.class);

        try {
            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("access-token", "token-captured")
                            .withBody("logged-in")));

            restlyController.login("creds");

            restlyClient.updateAuthHeader("Authorization", "token-manual");

            Pair<String, String> authHeader = restlyClient.getModuleInfo().getAuthProvider().getAuthHeader();
            assertNotNull(authHeader);
            assertEquals("Authorization", authHeader.getKey());
            assertEquals("token-manual", authHeader.getValue());

            stubFor(get(urlEqualTo("/api/auth-check"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":1,\"name\":\"item\",\"description\":\"ok\"}")));

            restlyController.getAuthorized();

            verify(getRequestedFor(urlEqualTo("/api/auth-check"))
                    .withHeader("Authorization", equalTo("token-manual")));
        } finally {
            restlyClient.close();
        }
    }

    @Test
    @DisplayName("fresh capture overrides manual update, failed capture keeps the active token")
    void captureAfterManualUpdate() {
        JRestlyClient restlyClient = JRestlyClient.builder().baseUrl(baseUrl()).build();
        TestController restlyController = restlyClient.get(TestController.class);
        AuthProvider authProvider = restlyClient.getModuleInfo().getAuthProvider();

        try {
            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("access-token", "token-fresh")
                            .withBody("logged-in")));

            restlyController.login("creds");
            assertEquals("token-fresh", authProvider.getAuthHeader().getValue());

            restlyClient.updateAuthHeader("Authorization", "token-manual");
            assertEquals("token-manual", authProvider.getAuthHeader().getValue());

            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("logged-in-without-token")));

            restlyController.login("creds");
            assertEquals("token-manual", authProvider.getAuthHeader().getValue());

            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("access-token", "token-new")
                            .withBody("logged-in-again")));

            restlyController.login("creds");
            assertEquals("token-new", authProvider.getAuthHeader().getValue());
        } finally {
            restlyClient.close();
        }
    }
}
