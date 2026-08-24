package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.Header;

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

        List<Header> authHeaders = moduleInfo.getTestAuthProvider().getAuthHeaders();
        assertEquals(1, authHeaders.size());
        assertEquals("access-token", authHeaders.get(0).name());
        assertEquals("captured-token-123", authHeaders.get(0).value());
    }

    @Test
    @DisplayName("@Authorization method triggers login when not authorized")
    void authorizationMethodTriggersLogin() {
        moduleInfo.getTestAuthProvider().setAuthorized(true);
        moduleInfo.getTestAuthProvider().updateAuthHeader("access-token", "initial-token");

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

            List<Header> authHeaders = restlyClient.getModuleInfo().getAuthProvider().getAuthHeaders();
            assertEquals(1, authHeaders.size());
            assertEquals("Authorization", authHeaders.get(0).name());
            assertEquals("token-manual", authHeaders.get(0).value());

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
            assertEquals("token-fresh", authProvider.getAuthHeaders().get(0).value());

            restlyClient.updateAuthHeader("Authorization", "token-manual");
            assertEquals("token-manual", authProvider.getAuthHeaders().get(0).value());

            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("logged-in-without-token")));

            restlyController.login("creds");
            assertEquals("token-manual", authProvider.getAuthHeaders().get(0).value());

            stubFor(post(urlEqualTo("/api/login"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("access-token", "token-new")
                            .withBody("logged-in-again")));

            restlyController.login("creds");
            assertEquals("token-new", authProvider.getAuthHeaders().get(0).value());
        } finally {
            restlyClient.close();
        }
    }
}
