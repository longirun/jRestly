package ru.jrestly.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.UnexpectedStatusException;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the interceptor {@link RequestContext} and its response
 * counterpart (ADR-0029): method metadata and arguments, null-argument
 * normalization, attribute lifecycle, null validation, the operator adapter
 * and response-context status/success delegation. Pure unit tests, no HTTP.
 */
class RequestContextTest {

    interface SampleApi {
        String testMethod(String arg1, int arg2);
        void emptyMethod();
    }

    private static Method sampleMethod() {
        try {
            return SampleApi.class.getMethod("testMethod", String.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Method emptyMethod() {
        try {
            return SampleApi.class.getMethod("emptyMethod");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("SimpleRequestContext correctly stores and returns metadata and arguments")
    void contextMetadataAndArgs() {
        Method method = sampleMethod();
        Object[] args = new Object[]{"hello", 42};
        SimpleRequestContext ctx = new SimpleRequestContext("sample-module", SampleApi.class, method, args);

        assertEquals("sample-module", ctx.moduleName());
        assertSame(SampleApi.class, ctx.controllerClass());
        assertSame(method, ctx.method());
        assertEquals(2, ctx.args().length);
        assertEquals("hello", ctx.args()[0]);
        assertEquals(42, ctx.args()[1]);

        // Defensive copy on args()
        Object[] returnedArgs = ctx.args();
        returnedArgs[0] = "mutated";
        assertEquals("hello", ctx.args()[0], "Internal args array must not be mutated");
    }

    @Test
    @DisplayName("SimpleRequestContext normalizes null args to empty array")
    void contextNullArgsNormalization() {
        Method method = emptyMethod();
        SimpleRequestContext ctx = new SimpleRequestContext("default", SampleApi.class, method, null);

        assertNotNull(ctx.args());
        assertEquals(0, ctx.args().length);
    }

    @Test
    @DisplayName("SimpleRequestContext attributes lifecycle and typed retrieval")
    void contextAttributesLifecycle() {
        SimpleRequestContext ctx = new SimpleRequestContext("mod", SampleApi.class, emptyMethod(), null);

        assertNull(ctx.getAttribute("non-existent"));
        assertEquals(Optional.empty(), ctx.getAttribute("non-existent", String.class));
        assertTrue(ctx.attributes().isEmpty());

        ctx.setAttribute("traceId", "trace-xyz-123");
        ctx.setAttribute("startTime", 1000L);

        assertEquals("trace-xyz-123", ctx.getAttribute("traceId"));
        assertEquals(Optional.of("trace-xyz-123"), ctx.getAttribute("traceId", String.class));
        assertEquals(Optional.empty(), ctx.getAttribute("traceId", Integer.class), "Mismatched type returns empty Optional");
        assertEquals(Optional.of(1000L), ctx.getAttribute("startTime", Long.class));
        assertEquals(2, ctx.attributes().size());

        // Removing attribute via null value
        ctx.setAttribute("traceId", null);
        assertNull(ctx.getAttribute("traceId"));
        assertEquals(Optional.empty(), ctx.getAttribute("traceId", String.class));
        assertEquals(1, ctx.attributes().size());

        // Attributes snapshot is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> ctx.attributes().put("forbidden", "val"));
    }

    @Test
    @DisplayName("SimpleRequestContext enforces non-null metadata")
    void contextNullValidations() {
        Method m = emptyMethod();
        assertThrows(NullPointerException.class, () -> new SimpleRequestContext(null, SampleApi.class, m, null));
        assertThrows(NullPointerException.class, () -> new SimpleRequestContext("mod", null, m, null));
        assertThrows(NullPointerException.class, () -> new SimpleRequestContext("mod", SampleApi.class, null, null));

        SimpleRequestContext ctx = new SimpleRequestContext("mod", SampleApi.class, m, null);
        assertThrows(NullPointerException.class, () -> ctx.setAttribute(null, "val"));
        assertThrows(NullPointerException.class, () -> ctx.getAttribute(null));
        assertThrows(NullPointerException.class, () -> ctx.getAttribute("key", null));
    }

    @Test
    @DisplayName("RequestInterceptor.of wraps UnaryOperator concisely")
    void requestInterceptorOfOperator() {
        WireRequest original = new WireRequest(HttpMethod.GET, URI.create("http://example.com"), List.of(), null, null, null, null);
        RequestInterceptor interceptor = RequestInterceptor.of(req -> req.withHeader("X-Trace", "123"));

        RequestContext ctx = new SimpleRequestContext("mod", SampleApi.class, emptyMethod(), null);
        WireRequest modified = interceptor.intercept(original, ctx);

        assertEquals(1, modified.headers().size());
        assertEquals(new Header("X-Trace", "123"), modified.headers().get(0));

        assertThrows(NullPointerException.class, () -> RequestInterceptor.of(null));
    }

    @Test
    @DisplayName("SimpleResponseContext delegates to RequestContext and exposes response data")
    void simpleResponseContextDelegationAndData() {
        RequestContext reqCtx = new SimpleRequestContext("test-module", SampleApi.class, emptyMethod(), null);
        reqCtx.setAttribute("correlationId", "corr-999");

        WireRequest wireReq = new WireRequest(HttpMethod.GET, URI.create("http://example.com"), List.of(), null, null, null, null);
        WireResponse wireResp = new WireResponse(200, List.of(new Header("Content-Type", "text/plain")), "OK".getBytes());
        Duration duration = Duration.ofMillis(120);

        SimpleResponseContext respCtx = new SimpleResponseContext(reqCtx, wireReq, wireResp, null, duration);

        // Delegated metadata and attributes
        assertEquals("test-module", respCtx.moduleName());
        assertSame(SampleApi.class, respCtx.controllerClass());
        assertEquals("corr-999", respCtx.getAttribute("correlationId"));

        // Direct response properties
        assertSame(wireReq, respCtx.request());
        assertTrue(respCtx.response().isPresent());
        assertSame(wireResp, respCtx.response().get());
        assertTrue(respCtx.failure().isEmpty());
        assertEquals(duration, respCtx.duration());
        assertEquals(200, respCtx.statusCode());
        assertTrue(respCtx.isSuccess());
    }

    @Test
    @DisplayName("ResponseContext statusCode and isSuccess resolution logic")
    void responseContextStatusAndSuccess() {
        RequestContext reqCtx = new SimpleRequestContext("mod", SampleApi.class, emptyMethod(), null);
        WireRequest wireReq = new WireRequest(HttpMethod.GET, URI.create("http://example.com"), List.of(), null, null, null, null);

        // 1. Successful 204 No Content
        ResponseContext okResp = new SimpleResponseContext(reqCtx, wireReq, new WireResponse(204, List.of(), null), null, Duration.ofMillis(50));
        assertEquals(204, okResp.statusCode());
        assertTrue(okResp.isSuccess());

        // 2. 404 response without exception
        ResponseContext notFound = new SimpleResponseContext(reqCtx, wireReq, new WireResponse(404, List.of(), null), null, Duration.ofMillis(50));
        assertEquals(404, notFound.statusCode());
        assertFalse(notFound.isSuccess());

        // 3. HandledException (e.g. @OnError status 422)
        HandledException handled = new HandledException("validation failed", 422, List.of());
        ResponseContext handledCtx = new SimpleResponseContext(reqCtx, wireReq, null, handled, Duration.ofMillis(80));
        assertEquals(422, handledCtx.statusCode());
        assertFalse(handledCtx.isSuccess());

        // 4. UnexpectedStatusException (status 502) wrapped in CompletionException
        UnexpectedStatusException unexpected = new UnexpectedStatusException(502, "bad gateway", List.of());
        CompletionException wrapped = new CompletionException(unexpected);
        ResponseContext wrappedCtx = new SimpleResponseContext(reqCtx, wireReq, null, wrapped, Duration.ofMillis(90));
        assertEquals(502, wrappedCtx.statusCode());
        assertFalse(wrappedCtx.isSuccess());

        // 5. Network transport failure (IOException) - status is -1
        IOException ioErr = new IOException("Connection refused");
        ResponseContext netErrCtx = new SimpleResponseContext(reqCtx, wireReq, null, ioErr, Duration.ofMillis(10));
        assertEquals(-1, netErrCtx.statusCode());
        assertFalse(netErrCtx.isSuccess());

        // 6. Both response and failure absent - status is -1 and isSuccess is false
        ResponseContext emptyCtx = new SimpleResponseContext(reqCtx, wireReq, null, null, Duration.ofMillis(5));
        assertEquals(-1, emptyCtx.statusCode());
        assertFalse(emptyCtx.isSuccess());

        // 7. Non-2xx response without failure (302 redirect)
        ResponseContext redirectCtx = new SimpleResponseContext(reqCtx, wireReq, new WireResponse(302, List.of(), null), null, Duration.ofMillis(10));
        assertEquals(302, redirectCtx.statusCode());
        assertFalse(redirectCtx.isSuccess());
    }

    @Test
    @DisplayName("RequestContext default methods provide safe fallback contracts")
    void requestContextDefaultMethods() {
        RequestContext dummy = new RequestContext() {
            @Override public String moduleName() { return "mod"; }
            @Override public Class<?> controllerClass() { return SampleApi.class; }
            @Override public Method method() { return emptyMethod(); }
            @Override public Object[] args() { return new Object[0]; }
            @Override public void setAttribute(String name, Object value) {}
            @Override public Object getAttribute(String name) { return null; }
            @Override public java.util.Map<String, Object> attributes() { return java.util.Map.of(); }
        };

        assertThrows(UnsupportedOperationException.class, () -> dummy.shortCircuit(new WireResponse(200, List.of(), null)));
        assertTrue(dummy.shortCircuitResponse().isEmpty());
        assertFalse(dummy.isShortCircuited());
    }

    @Test
    @DisplayName("SimpleResponseContext delegates setAttribute, attributes, method, and args")
    void simpleResponseContextFullDelegation() {
        RequestContext reqCtx = new SimpleRequestContext("mod", SampleApi.class, sampleMethod(), new Object[]{"val", 123});
        WireRequest wireReq = new WireRequest(HttpMethod.GET, URI.create("http://example.com"), List.of(), null, null, null, null);
        SimpleResponseContext respCtx = new SimpleResponseContext(reqCtx, wireReq, null, null, Duration.ofMillis(15));

        assertEquals(sampleMethod(), respCtx.method());
        assertEquals(2, respCtx.args().length);
        assertEquals("val", respCtx.args()[0]);

        respCtx.setAttribute("customAttr", "attrVal");
        assertEquals("attrVal", respCtx.getAttribute("customAttr"));
        assertEquals(Optional.of("attrVal"), respCtx.getAttribute("customAttr", String.class));
        assertEquals("attrVal", respCtx.attributes().get("customAttr"));
    }
}
