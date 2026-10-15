package ru.jrestly.cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.jrestly.BaseWireMockTest;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.ResponseHeaders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0039: Persistent Cookie Store WireMock Integration Tests")
class PersistentCookieWireMockTest extends BaseWireMockTest {

    interface AuthApi {
        @Post(path = "/api/login")
        String login(ResponseHeaders headers);

        @Get(path = "/api/profile")
        String getProfile();

        @Post(path = "/api/logout")
        String logout();

        @Post(path = "/api/multi-cookie-login")
        String multiCookieLogin();

        @Get(path = "/api/declarative")
        @Cookie(name = "x_client_type", value = "cli_bot")
        String getDeclarative();

        @Post(path = "/api/async-login")
        CompletableFuture<String> asyncLogin();
    }

    @Test
    @DisplayName("Process restart simulation: cookies saved by first client are loaded by second client")
    void processRestartSimulation(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("session.cookies");

        stubFor(post(urlEqualTo("/api/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "session_id=sess_live_999; Path=/; HttpOnly")
                        .withBody("Logged In")));

        stubFor(get(urlEqualTo("/api/profile"))
                .withHeader("Cookie", containing("session_id=sess_live_999"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Welcome Alice")));

        // Client 1: Logs in and acquires cookie
        JRestlyClient client1 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api1 = client1.get(AuthApi.class);
            ResponseHeaders headers = new ResponseHeaders();
            String loginResp = api1.login(headers);

            assertEquals("Logged In", loginResp);
            assertEquals("sess_live_999", headers.cookieValue("session_id").orElse(null));
            assertTrue(Files.exists(cookieFile));
        } finally {
            client1.close();
        }

        // Client 2: Fresh instance pointing to the same cookie file
        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api2 = client2.get(AuthApi.class);
            String profileResp = api2.getProfile();

            assertEquals("Welcome Alice", profileResp);
            verify(getRequestedFor(urlEqualTo("/api/profile"))
                    .withHeader("Cookie", containing("session_id=sess_live_999")));
        } finally {
            client2.close();
        }
    }

    @Test
    @DisplayName("Logout expires cookie and cleans up persistent storage")
    void logoutExpiresCookie(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("session_logout.cookies");

        stubFor(post(urlEqualTo("/api/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "auth_token=tok_42; Path=/; Max-Age=3600")
                        .withBody("OK")));

        stubFor(post(urlEqualTo("/api/logout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "auth_token=; Path=/; Max-Age=0")
                        .withBody("Logged Out")));

        stubFor(get(urlEqualTo("/api/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Profile OK")));

        JRestlyClient testClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api = testClient.get(AuthApi.class);
            api.login(new ResponseHeaders());
            api.getProfile();

            verify(getRequestedFor(urlEqualTo("/api/profile"))
                    .withHeader("Cookie", containing("auth_token"))
                    .withHeader("Cookie", containing("tok_42")));

            // Logout
            api.logout();

            // Next request should NOT send auth_token
            wireMockServer.resetRequests();
            api.getProfile();

            verify(getRequestedFor(urlEqualTo("/api/profile"))
                    .withoutHeader("Cookie"));
        } finally {
            testClient.close();
        }

        // Verify a fresh client loads 0 cookies
        JRestlyClient freshClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            assertEquals(0, freshClient.getCookieStore().orElseThrow().getCookies().size());
        } finally {
            freshClient.close();
        }
    }

    @Test
    @DisplayName("Multiple cookies are persisted and combined with declarative @Cookie annotations")
    void multipleCookiesCombinedWithDeclarative(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("multi.cookies");

        stubFor(post(urlEqualTo("/api/multi-cookie-login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "uid=user_101; Path=/")
                        .withHeader("Set-Cookie", "pref=dark_mode; Path=/")
                        .withBody("Multi OK")));

        stubFor(get(urlEqualTo("/api/declarative"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Declarative OK")));

        JRestlyClient client1 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api1 = client1.get(AuthApi.class);
            api1.multiCookieLogin();
        } finally {
            client1.close();
        }

        // Fresh client
        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api2 = client2.get(AuthApi.class);
            String resp = api2.getDeclarative();
            assertEquals("Declarative OK", resp);

            // Check WireMock received both persisted cookies and the declarative @Cookie
            verify(getRequestedFor(urlEqualTo("/api/declarative"))
                    .withHeader("Cookie", containing("uid=user_101"))
                    .withHeader("Cookie", containing("pref=dark_mode"))
                    .withHeader("Cookie", containing("x_client_type=cli_bot")));
        } finally {
            client2.close();
        }
    }

    @Test
    @DisplayName("Async CompletableFuture request persists cookies on completion")
    void asyncRequestPersistsCookies(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("async.cookies");

        stubFor(post(urlEqualTo("/api/async-login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "async_token=async_secret; Path=/")
                        .withBody("Async OK")));

        stubFor(get(urlEqualTo("/api/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Async Profile OK")));

        JRestlyClient client1 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api1 = client1.get(AuthApi.class);
            String result = api1.asyncLogin().join();
            assertEquals("Async OK", result);
        } finally {
            client1.close();
        }

        // Fresh client
        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .persistentCookieStore(cookieFile)
                .build();

        try {
            AuthApi api2 = client2.get(AuthApi.class);
            String profile = api2.getProfile();
            assertEquals("Async Profile OK", profile);

            verify(getRequestedFor(urlEqualTo("/api/profile"))
                    .withHeader("Cookie", containing("async_token=async_secret")));
        } finally {
            client2.close();
        }
    }

    @Test
    @DisplayName("PersistentCookieStore in MANUAL mode defers file write until explicit sync")
    void manualModeDefersWriteUntilSync(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("manual.cookies");
        PersistentCookieStore manualStore = PersistentCookieStore.ofFile(cookieFile, PersistentCookieStore.PersistenceMode.MANUAL);

        stubFor(post(urlEqualTo("/api/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "manual_token=m123; Path=/")
                        .withBody("Manual OK")));

        JRestlyClient testClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .cookieStore(manualStore)
                .build();

        try {
            AuthApi api = testClient.get(AuthApi.class);
            api.login(new ResponseHeaders());

            // File should not exist yet
            assertFalse(Files.exists(cookieFile));

            // Explicit sync
            manualStore.sync();
            assertTrue(Files.exists(cookieFile));
        } finally {
            testClient.close();
        }
    }
}
