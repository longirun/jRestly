package ru.jrestly;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.ResponseHeaders;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for cookies and session management (ADR-0032): a cookie store carried across
 * calls, declarative {@code @Cookie} at interface/method level, path/map/{@code HttpCookie}
 * parameters, repeated cookies and async calls.
 */
@DisplayName("Cookie handling, session management and @Cookie WireMock integration tests (ADR-0032)")
class CookieSessionWireMockTest extends BaseWireMockTest {

    interface SessionApi {
        @Post("/api/login")
        String login(ResponseHeaders headers);

        @Get("/api/profile")
        String getProfile();
    }

    @Cookie(name = "app_theme", value = "dark")
    interface DeclarativeCookieApi {

        @Get("/api/theme")
        String getTheme();

        @Get("/api/method-cookie")
        @Cookie(name = "feature", value = "canary")
        String getWithMethodCookie();

        @Get("/api/repeatable-cookie")
        @Cookie(name = "c1", value = "v1")
        @Cookie(name = "c2", value = "v2")
        String getWithRepeatableCookies();

        @Get("/api/param-cookie")
        String getWithParamCookie(@Cookie(name = "auth_session") String token);

        @Get("/api/override-theme")
        String overrideTheme(@Cookie(name = "app_theme") String themeOverride);

        @Get("/api/map-cookies")
        String getWithMapCookies(@Cookie Map<String, Object> cookieMap);

        @Get("/api/http-cookie-param")
        String getWithHttpCookie(@Cookie HttpCookie cookie);

        @Get("/api/async-cookie")
        @Cookie(name = "async_token", value = "async_123")
        CompletableFuture<String> getAsyncWithCookie();
    }

    @Test
    @DisplayName("Session management: automatically saves Set-Cookie and sends Cookie in subsequent requests")
    void automaticSessionManagementAcrossRequests() {
        stubFor(post(urlEqualTo("/api/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "JSESSIONID=sess_abc987; Path=/; HttpOnly")
                        .withBody("Logged in")));

        stubFor(get(urlEqualTo("/api/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("User Profile")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .cookieManager()
                .build();

        try {
            SessionApi api = client.get(SessionApi.class);

            ResponseHeaders headers = new ResponseHeaders();
            String loginResp = api.login(headers);
            assertEquals("Logged in", loginResp);

            // Verify ResponseHeaders captured the cookie
            assertEquals("sess_abc987", headers.cookieValue("JSESSIONID").orElse(null));
            assertTrue(headers.cookie("JSESSIONID").isPresent());
            assertTrue(headers.cookie("JSESSIONID").get().isHttpOnly());

            // Second request to profile
            String profileResp = api.getProfile();
            assertEquals("User Profile", profileResp);

            // Verify WireMock saw the Cookie header
            List<LoggedRequest> profileRequests = wireMockServer.findAll(RequestPatternBuilder.allRequests());
            LoggedRequest profileReq = profileRequests.stream()
                    .filter(r -> r.getUrl().equals("/api/profile"))
                    .findFirst()
                    .orElseThrow();

            String cookieHeader = profileReq.getHeader("Cookie");
            assertNotNull(cookieHeader);
            assertTrue(cookieHeader.contains("JSESSIONID=sess_abc987"));

            // Clear session via getCookieStore()
            client.getCookieStore().ifPresent(store -> store.removeAll());

            api.getProfile();

            LoggedRequest secondProfileReq = wireMockServer.findAll(RequestPatternBuilder.allRequests()).stream()
                    .filter(r -> r.getUrl().equals("/api/profile"))
                    .skip(1)
                    .findFirst()
                    .orElseThrow();

            assertNull(secondProfileReq.getHeader("Cookie"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Interface-level @Cookie is included on all requests")
    void interfaceLevelCookieIncluded() {
        stubFor(get(urlEqualTo("/api/theme"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            api.getTheme();

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            assertEquals("app_theme=dark", req.getHeader("Cookie"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Method-level @Cookie combines with interface-level @Cookie")
    void methodLevelCookieCombinesWithInterface() {
        stubFor(get(urlEqualTo("/api/method-cookie"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            api.getWithMethodCookie();

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            String cookie = req.getHeader("Cookie");
            assertNotNull(cookie);
            assertTrue(cookie.contains("app_theme=dark"));
            assertTrue(cookie.contains("feature=canary"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Repeatable @Cookie declarations on method")
    void repeatableCookiesOnMethod() {
        stubFor(get(urlEqualTo("/api/repeatable-cookie"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            api.getWithRepeatableCookies();

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            String cookie = req.getHeader("Cookie");
            assertNotNull(cookie);
            assertTrue(cookie.contains("c1=v1"));
            assertTrue(cookie.contains("c2=v2"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Parameter-level @Cookie transmits dynamic value, omits on null")
    void parameterLevelCookieDynamicAndNull() {
        stubFor(get(urlEqualTo("/api/param-cookie"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);

            // With value
            api.getWithParamCookie("secret-token-42");
            LoggedRequest req1 = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            assertTrue(req1.getHeader("Cookie").contains("auth_session=secret-token-42"));

            // With null: cookie is omitted
            api.getWithParamCookie(null);
            LoggedRequest req2 = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(1);
            assertFalse(req2.getHeader("Cookie").contains("auth_session"));
            assertTrue(req2.getHeader("Cookie").contains("app_theme=dark"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Parameter-level @Cookie overrides static interface cookie; null removes it")
    void parameterLevelCookieOverridesStatic() {
        stubFor(get(urlEqualTo("/api/override-theme"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);

            // Dynamic override
            api.overrideTheme("light");
            LoggedRequest req1 = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            assertEquals("app_theme=light", req1.getHeader("Cookie"));

            // Null override removes the static cookie entirely
            api.overrideTheme(null);
            LoggedRequest req2 = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(1);
            assertNull(req2.getHeader("Cookie"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Parameter-level @Cookie with Map expands key-values")
    void parameterLevelCookieWithMap() {
        stubFor(get(urlEqualTo("/api/map-cookies"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            api.getWithMapCookies(Map.of("lang", "en", "currency", "USD"));

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            String cookie = req.getHeader("Cookie");
            assertTrue(cookie.contains("lang=en"));
            assertTrue(cookie.contains("currency=USD"));
            assertTrue(cookie.contains("app_theme=dark"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Parameter-level @Cookie with HttpCookie object")
    void parameterLevelCookieWithHttpCookie() {
        stubFor(get(urlEqualTo("/api/http-cookie-param"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            api.getWithHttpCookie(new HttpCookie("custom_cookie", "cookie_val"));

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            String cookie = req.getHeader("Cookie");
            assertTrue(cookie.contains("custom_cookie=cookie_val"));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async call via CompletableFuture transmits declarative cookies")
    void asyncCallWithCookie() {
        stubFor(get(urlEqualTo("/api/async-cookie"))
                .willReturn(aResponse().withStatus(200).withBody("ASYNC OK")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();

        try {
            DeclarativeCookieApi api = client.get(DeclarativeCookieApi.class);
            String result = api.getAsyncWithCookie().join();
            assertEquals("ASYNC OK", result);

            LoggedRequest req = wireMockServer.findAll(RequestPatternBuilder.allRequests()).get(0);
            String cookie = req.getHeader("Cookie");
            assertTrue(cookie.contains("async_token=async_123"));
            assertTrue(cookie.contains("app_theme=dark"));
        } finally {
            client.close();
        }
    }
}
