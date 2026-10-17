package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.Header;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.json.JsonCodec;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage of cookie configuration and response parsing (ADR-0032/ADR-0039):
 * {@code ModuleInfo}/{@code SimpleModuleInfo} defaults and custom cookie stores, plus
 * cookie extraction from {@code ResponseHeaders}.
 */
class CookieConfigAndResponseTest {

    @Test
    @DisplayName("ModuleInfo default getCookieHandler returns null")
    void moduleInfoDefaultCookieHandlerReturnsNull() {
        ModuleInfo info = new ModuleInfo() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public <T> Environment<T> getEnvironment() {
                return null;
            }

            @Override
            public AuthProvider getAuthProvider() {
                return null;
            }

            @Override
            public JsonCodec getJsonCodec() {
                return null;
            }
        };
        assertNull(info.getCookieHandler());
    }

    @Test
    @DisplayName("SimpleModuleInfo default builder has null cookieHandler")
    void simpleModuleInfoDefaultCookieHandlerIsNull() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNull(info.getCookieHandler());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder accepts custom CookieHandler")
    void simpleModuleInfoAcceptsCustomCookieHandler() {
        CookieHandler custom = new CookieManager();
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .cookieHandler(custom)
                .build();

        assertEquals(custom, info.getCookieHandler());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder fail-fast on null CookieHandler")
    void simpleModuleInfoFailFastOnNullCookieHandler() {
        assertThrows(NullPointerException.class, () ->
                SimpleModuleInfo.builder().cookieHandler(null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder cookieManager() enables CookieManager")
    void simpleModuleInfoCookieManagerShortcut() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .cookieManager()
                .build();

        assertNotNull(info.getCookieHandler());
        assertTrue(info.getCookieHandler() instanceof CookieManager);
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder cookiePolicy() configures policy")
    void simpleModuleInfoCookiePolicyShortcut() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .cookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build();

        assertNotNull(info.getCookieHandler());
        assertTrue(info.getCookieHandler() instanceof CookieManager);
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder fail-fast on null CookiePolicy")
    void simpleModuleInfoFailFastOnNullCookiePolicy() {
        assertThrows(NullPointerException.class, () ->
                SimpleModuleInfo.builder().cookiePolicy(null));
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards cookie settings to AbstractHttpClient")
    void jrestlyClientBuilderForwardsCookieSettings() {
        JRestlyClient defaultClient = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();
        assertNull(defaultClient.getCookieHandler());
        assertTrue(defaultClient.getCookieStore().isEmpty());
        defaultClient.close();

        JRestlyClient cookieClient = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .cookieManager()
                .build();
        assertNotNull(cookieClient.getCookieHandler());
        assertTrue(cookieClient.getCookieStore().isPresent());
        cookieClient.close();
    }

    @Test
    @DisplayName("ResponseHeaders: unpopulated returns empty cookies")
    void responseHeadersUnpopulatedEmptyCookies() {
        ResponseHeaders headers = new ResponseHeaders();
        assertTrue(headers.cookies().isEmpty());
        assertTrue(headers.cookie("session").isEmpty());
        assertTrue(headers.cookieValue("session").isEmpty());
        assertTrue(headers.cookieMap().isEmpty());
    }

    @Test
    @DisplayName("ResponseHeaders: parse single Set-Cookie header")
    void responseHeadersParseSingleSetCookie() {
        ResponseHeaders headers = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie", "sessionId=abc123xyz; Path=/; HttpOnly; Max-Age=3600")
        ), Duration.ofMillis(50));

        List<HttpCookie> cookies = headers.cookies();
        assertEquals(1, cookies.size());

        HttpCookie cookie = cookies.get(0);
        assertEquals("sessionId", cookie.getName());
        assertEquals("abc123xyz", cookie.getValue());
        assertEquals("/", cookie.getPath());
        assertEquals(3600, cookie.getMaxAge());
        assertTrue(cookie.isHttpOnly());

        Optional<HttpCookie> found = headers.cookie("sessionId");
        assertTrue(found.isPresent());
        assertEquals("abc123xyz", found.get().getValue());

        assertEquals(Optional.of("abc123xyz"), headers.cookieValue("sessionId"));
        assertEquals(Optional.of("abc123xyz"), headers.cookieValue("SESSIONID")); // case-insensitive lookup
    }

    @Test
    @DisplayName("ResponseHeaders: parse multiple Set-Cookie headers and cookieMap")
    void responseHeadersParseMultipleSetCookies() {
        ResponseHeaders headers = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie", "token=secret1; Path=/app"),
                new Header("Set-Cookie", "theme=dark; Path=/"),
                new Header("Set-Cookie2", "flag=beta; Path=/")
        ), Duration.ofMillis(10));

        List<HttpCookie> cookies = headers.cookies();
        assertEquals(3, cookies.size());

        Map<String, String> map = headers.cookieMap();
        assertEquals(3, map.size());
        assertEquals("secret1", map.get("token"));
        assertEquals("dark", map.get("theme"));
        assertEquals("beta", map.get("flag"));
        // Case-insensitive key lookup in map
        assertEquals("dark", map.get("THEME"));
    }

    @Test
    @DisplayName("ResponseHeaders: ignore malformed Set-Cookie without failing")
    void responseHeadersIgnoreMalformedSetCookie() {
        ResponseHeaders headers = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie", "valid=123; Path=/"),
                new Header("Set-Cookie", ";;;invalid"),
                new Header("Set-Cookie", "")
        ), Duration.ofMillis(10));

        List<HttpCookie> cookies = headers.cookies();
        assertEquals(1, cookies.size());
        assertEquals("valid", cookies.get(0).getName());
        assertEquals("123", cookies.get(0).getValue());
    }

    @Test
    @DisplayName("ResponseHeaders: null check on cookie lookup")
    void responseHeadersNullCheckOnCookieLookup() {
        ResponseHeaders headers = new ResponseHeaders();
        assertThrows(NullPointerException.class, () -> headers.cookie(null));
        assertThrows(NullPointerException.class, () -> headers.cookieValue(null));
    }
}
