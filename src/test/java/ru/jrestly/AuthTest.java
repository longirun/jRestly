package ru.jrestly;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest extends BaseWireMockTest {

    @Test
    @DisplayName("@Anonymous метод не требует авторизации")
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
    @DisplayName("@SetAuthDetails захватывает заголовок из ответа")
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
    @DisplayName("@Authorization метод вызывает login когда не авторизован")
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
}
