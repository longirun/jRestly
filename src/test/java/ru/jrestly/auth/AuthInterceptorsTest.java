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

/**
 * Unit tests for the built-in auth {@link ru.jrestly.interceptor.RequestInterceptor}s
 * (ADR-0046): {@code BearerAuth} / {@code BasicAuth} header injection,
 * {@code ApiKeyAuth} header and query placement (including RFC 3986 query
 * encoding), the {@code @Authorization} bypass, the declared
 * {@code @Order(-1000)} priority and null-argument rejection. Pure unit tests,
 * no HTTP.
 */
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
    @DisplayName("BearerAuth and BasicAuth tolerate a null context or a null method like ApiKeyAuth does")
    void bearerAndBasicAuthTolerateNullContexts() throws Exception {
        BearerAuth bearer = BearerAuth.of("token");
        BasicAuth basic = BasicAuth.of("user", "pass");
        Method regularMethod = TestLoginApi.class.getMethod("regularCall");
        RequestContext nullMethodContext = mockContext(null);

        WireRequest req = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api"))
                .build();

        // No annotation check is possible — the interceptors attach credentials as usual.
        WireRequest bearerNullContext = bearer.intercept(req, null);
        assertEquals("Bearer token", bearerNullContext.headers().getFirst().value());
        WireRequest bearerNullMethod = bearer.intercept(req, nullMethodContext);
        assertEquals("Bearer token", bearerNullMethod.headers().getFirst().value());

        WireRequest basicNullContext = basic.intercept(req, null);
        assertEquals("Basic dXNlcjpwYXNz", basicNullContext.headers().getFirst().value());
        WireRequest basicNullMethod = basic.intercept(req, nullMethodContext);
        assertEquals("Basic dXNlcjpwYXNz", basicNullMethod.headers().getFirst().value());
    }

    @Test
    @DisplayName("BasicAuth bypasses calls to methods annotated with @Authorization")
    void basicAuthBypassesAuthorization() throws Exception {
        BasicAuth auth = BasicAuth.of("user", "pass");
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

        // Query location with both existing query AND URI fragment
        WireRequest req5 = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api?tab=all#section2"))
                .build();
        WireRequest intercepted5 = queryAuth.intercept(req5, context);
        assertEquals("https://example.com/api?tab=all&key=secret123#section2", intercepted5.uri().toString());

        // Bypasses methods annotated with @Authorization
        Method loginMethod = TestLoginApi.class.getMethod("login");
        RequestContext authContext = mockContext(loginMethod);
        WireRequest interceptedBypass = queryAuth.intercept(req1, authContext);
        assertEquals(req1, interceptedBypass);

        // Safe with null context or null context.method()
        WireRequest interceptedNullContext = queryAuth.intercept(req1, null);
        assertEquals("https://example.com/api?key=secret123", interceptedNullContext.uri().toString());

        RequestContext nullMethodContext = mockContext(null);
        WireRequest interceptedNullMethod = queryAuth.intercept(req1, nullMethodContext);
        assertEquals("https://example.com/api?key=secret123", interceptedNullMethod.uri().toString());

        // Dynamic key suppliers
        ApiKeyAuth dynamicQuery = ApiKeyAuth.query("token", () -> "dyn123");
        assertEquals("https://example.com/api?token=dyn123", dynamicQuery.intercept(req1, context).uri().toString());
        ApiKeyAuth dynamicHeader = ApiKeyAuth.header("X-Dynamic", () -> "dynHdr");
        assertEquals("dynHdr", dynamicHeader.intercept(req1, context).headers().getFirst().value());
    }

    @Test
    @DisplayName("ApiKeyAuth query placement uses RFC 3986 URI encoding (space is %20, not +)")
    void apiKeyAuthQueryEncodesUriComponents() throws Exception {
        Method regularMethod = TestLoginApi.class.getMethod("regularCall");
        RequestContext context = mockContext(regularMethod);

        // A space must become %20 (RFC 3986 query component), matching the
        // rest of the request surface — not the form-urlencoded '+' that
        // URLEncoder emits by default.
        ApiKeyAuth queryAuth = ApiKeyAuth.query("api key", "my api/key+value");
        WireRequest req = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://example.com/api"))
                .build();

        WireRequest intercepted = queryAuth.intercept(req, context);

        assertEquals("https://example.com/api?api%20key=my%20api%2Fkey%2Bvalue",
                intercepted.uri().toString());
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
