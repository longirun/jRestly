package ru.jrestly.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.Order;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorsTest {

    interface TestLoginApi {
        @Authorization
        void login();

        void regularCall();
    }

    private RequestContext mockContext(Method method) {
        return new RequestContext() {
            @Override
            public String moduleName() {
                return "test";
            }

            @Override
            public Class<?> controllerClass() {
                return TestLoginApi.class;
            }

            @Override
            public Method method() {
                return method;
            }

            @Override
            public Object[] args() {
                return new Object[0];
            }

            @Override
            public void setAttribute(String name, Object value) {
            }

            @Override
            public Object getAttribute(String name) {
                return null;
            }

            @Override
            public Map<String, Object> attributes() {
                return Map.of();
            }
        };
    }

    @Test
    @DisplayName("BearerAuth attaches Authorization: Bearer <token> and replaces existing Authorization")
    void bearerAuthAttachesHeader() throws Exception {
        BearerAuth auth = BearerAuth.of("secret-jwt-xyz");
        Method regularMethod = TestLoginApi.class.getMethod("regularCall");
        RequestContext context = mockContext(regularMethod);

        WireRequest req = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api"))
                .header("Authorization", "OldToken")
                .build();

        WireRequest intercepted = auth.intercept(req, context);
        List<Header> authHeaders = intercepted.headers().stream()
                .filter(h -> "Authorization".equalsIgnoreCase(h.name()))
                .toList();

        assertEquals(1, authHeaders.size());
        assertEquals("Bearer secret-jwt-xyz", authHeaders.getFirst().value());
        assertNotNull(auth.tokenManager());
    }

    @Test
    @DisplayName("BearerAuth bypasses calls to methods annotated with @Authorization")
    void bearerAuthBypassesAuthorization() throws Exception {
        BearerAuth auth = BearerAuth.of("token");
        Method loginMethod = TestLoginApi.class.getMethod("login");
        RequestContext context = mockContext(loginMethod);

        WireRequest req = WireRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("https://example.com/login"))
                .build();

        WireRequest intercepted = auth.intercept(req, context);
        assertNull(intercepted.headers());
    }

    @Test
    @DisplayName("BasicAuth encodes credentials according to RFC 7617")
    void basicAuthRfc7617() throws Exception {
        // Standard RFC 7617 test: Aladdin:open sesame -> QWxhZGRpbjpvcGVuIHNlc2FtZQ==
        BasicAuth auth = BasicAuth.of("Aladdin", "open sesame");
        Method regularMethod = TestLoginApi.class.getMethod("regularCall");
        RequestContext context = mockContext(regularMethod);

        WireRequest req = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api"))
                .build();

        WireRequest intercepted = auth.intercept(req, context);
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", intercepted.headers().getFirst().value());

        // Dynamic suppliers
        BasicAuth dynamicAuth = BasicAuth.of(() -> "user", () -> "pass");
        WireRequest dynamicIntercepted = dynamicAuth.intercept(req, context);
        assertEquals("Basic dXNlcjpwYXNz", dynamicIntercepted.headers().getFirst().value());
    }

    @Test
    @DisplayName("ApiKeyAuth supports both Header and Query parameter locations")
    void apiKeyAuthLocations() throws Exception {
        Method regularMethod = TestLoginApi.class.getMethod("regularCall");
        RequestContext context = mockContext(regularMethod);

        // Header location
        ApiKeyAuth headerAuth = ApiKeyAuth.header("X-API-Key", "my-api-key");
        WireRequest req1 = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api"))
                .build();
        WireRequest intercepted1 = headerAuth.intercept(req1, context);
        assertEquals("my-api-key", intercepted1.headers().getFirst().value());
        assertEquals("X-API-Key", intercepted1.headers().getFirst().name());

        // Query location without existing query
        ApiKeyAuth queryAuth = ApiKeyAuth.query("key", "secret123");
        WireRequest intercepted2 = queryAuth.intercept(req1, context);
        assertEquals("https://example.com/api?key=secret123", intercepted2.uri().toString());

        // Query location with existing query
        WireRequest req3 = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api?filter=active"))
                .build();
        WireRequest intercepted3 = queryAuth.intercept(req3, context);
        assertEquals("https://example.com/api?filter=active&key=secret123", intercepted3.uri().toString());

        // Query location with URI fragment
        WireRequest req4 = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api#section2"))
                .build();
        WireRequest intercepted4 = queryAuth.intercept(req4, context);
        assertEquals("https://example.com/api?key=secret123#section2", intercepted4.uri().toString());
    }

    @Test
    @DisplayName("Auth interceptors declare @Order(-1000) for highest priority execution")
    void orderAnnotation() {
        Order bearerOrder = BearerAuth.class.getAnnotation(Order.class);
        assertNotNull(bearerOrder);
        assertEquals(-1000, bearerOrder.value());

        Order basicOrder = BasicAuth.class.getAnnotation(Order.class);
        assertNotNull(basicOrder);
        assertEquals(-1000, basicOrder.value());

        Order apiKeyOrder = ApiKeyAuth.class.getAnnotation(Order.class);
        assertNotNull(apiKeyOrder);
        assertEquals(-1000, apiKeyOrder.value());
    }

    @Test
    @DisplayName("Auth interceptors reject null inputs in constructors and factories")
    void nullSafety() {
        assertThrows(NullPointerException.class, () -> BearerAuth.of((String) null));
        assertThrows(NullPointerException.class, () -> BearerAuth.of((TokenManager) null));
        assertThrows(NullPointerException.class, () -> BasicAuth.of(null, "pass"));
        assertThrows(NullPointerException.class, () -> BasicAuth.of("user", null));
        assertThrows(NullPointerException.class, () -> ApiKeyAuth.header(null, "key"));
        assertThrows(NullPointerException.class, () -> ApiKeyAuth.header("header", (String) null));
        assertThrows(NullPointerException.class, () -> ApiKeyAuth.query(null, "key"));
        assertThrows(NullPointerException.class, () -> ApiKeyAuth.query("param", (String) null));
    }
}
