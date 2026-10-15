package ru.jrestly.interceptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCircuitInterceptorTest {

    @Test
    @DisplayName("WireResponse static factory methods create properly initialized responses")
    void wireResponseFactoryMethods() {
        WireResponse empty = WireResponse.of(204);
        assertEquals(204, empty.statusCode());
        assertNull(empty.body());
        assertFalse(empty.hasBody());
        assertTrue(empty.headers().isEmpty());

        WireResponse withString = WireResponse.of(200, "{\"msg\":\"ok\"}");
        assertEquals(200, withString.statusCode());
        assertTrue(withString.hasBody());
        assertEquals("{\"msg\":\"ok\"}", new String(withString.body(), StandardCharsets.UTF_8));

        WireResponse withBytes = WireResponse.of(201, new byte[] {1, 2, 3});
        assertEquals(201, withBytes.statusCode());
        assertArrayEquals(new byte[] {1, 2, 3}, withBytes.body());

        List<Header> headers = List.of(new Header("Content-Type", "application/json"), new Header("X-Trace", "abc-123"));
        WireResponse withHeadersAndString = WireResponse.of(200, headers, "payload");
        assertEquals(2, withHeadersAndString.headers().size());
        assertEquals("application/json", withHeadersAndString.header("content-type").orElseThrow());
        assertEquals("abc-123", withHeadersAndString.header("X-TRACE").orElseThrow());
        assertTrue(withHeadersAndString.header("missing").isEmpty());

        WireResponse withHeadersAndBytes = WireResponse.of(200, headers, new byte[] {4, 5});
        assertEquals(2, withHeadersAndBytes.headers().size());
        assertArrayEquals(new byte[] {4, 5}, withHeadersAndBytes.body());
    }

    @Test
    @DisplayName("WireResponse.Builder constructs fully populated responses")
    void wireResponseBuilder() {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] {7, 8, 9});
        WireResponse response = WireResponse.builder()
                .statusCode(202)
                .header("X-Custom", "Val1")
                .header(new Header("X-Custom-2", "Val2"))
                .headers(List.of(new Header("X-Batch", "Val3")))
                .version(HttpClient.Version.HTTP_2)
                .trailer("Expires", "Wed, 21 Oct 2026 07:28:00 GMT")
                .trailer(new Header("Server-Timing", "app;dur=12.4"))
                .trailers(List.of(new Header("X-End", "true")))
                .stream(stream)
                .build();

        assertEquals(202, response.statusCode());
        assertEquals(3, response.headers().size());
        assertEquals("Val1", response.header("x-custom").orElseThrow());
        assertEquals("Val2", response.header("x-custom-2").orElseThrow());
        assertEquals("Val3", response.header("x-batch").orElseThrow());
        assertEquals(HttpClient.Version.HTTP_2, response.version());
        assertEquals(3, response.trailers().size());
        assertSame(stream, response.stream());
        assertNull(response.body());

        // Body override clears stream
        WireResponse bodyResponse = WireResponse.builder()
                .stream(stream)
                .body("Hello World", StandardCharsets.ISO_8859_1)
                .build();
        assertNull(bodyResponse.stream());
        assertEquals("Hello World", new String(bodyResponse.body(), StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("RequestContext supports short-circuit response storage and retrieval")
    void requestContextShortCircuit() throws Exception {
        var method = Object.class.getMethod("toString");
        SimpleRequestContext context = new SimpleRequestContext("testModule", Object.class, method, new Object[0]);

        assertFalse(context.isShortCircuited());
        assertTrue(context.shortCircuitResponse().isEmpty());

        WireResponse synthetic = WireResponse.of(200, "synthetic-payload");
        context.shortCircuit(synthetic);

        assertTrue(context.isShortCircuited());
        assertTrue(context.shortCircuitResponse().isPresent());
        assertSame(synthetic, context.shortCircuitResponse().get());

        assertThrows(NullPointerException.class, () -> context.shortCircuit(null));
    }

    @Test
    @DisplayName("ShortCircuitInterceptor.of() unconditionally supplies synthetic response")
    void shortCircuitInterceptorOf() throws Exception {
        var method = Object.class.getMethod("toString");
        SimpleRequestContext context = new SimpleRequestContext("testModule", Object.class, method, new Object[0]);

        WireResponse mock = WireResponse.of(200, "{\"status\":\"ok\"}");
        ShortCircuitInterceptor interceptor = ShortCircuitInterceptor.of(mock);

        WireRequest req = new WireRequest(HttpMethod.GET, URI.create("http://localhost/test"), List.of(), null, null);
        WireRequest returnedReq = interceptor.intercept(req, context);

        assertSame(req, returnedReq);
        assertTrue(context.isShortCircuited());
        assertSame(mock, context.shortCircuitResponse().orElseThrow());

        assertThrows(NullPointerException.class, () -> ShortCircuitInterceptor.of(null));
    }

    @Test
    @DisplayName("ShortCircuitInterceptor.matching() applies conditional short-circuit")
    void shortCircuitInterceptorMatching() throws Exception {
        var method = Object.class.getMethod("toString");
        SimpleRequestContext context = new SimpleRequestContext("testModule", Object.class, method, new Object[0]);

        WireResponse mock = WireResponse.of(200, "matched");
        ShortCircuitInterceptor interceptor = ShortCircuitInterceptor.matching(
                req -> req.uri().getPath().endsWith("/match"),
                req -> mock
        );

        WireRequest nonMatching = new WireRequest(HttpMethod.GET, URI.create("http://localhost/other"), List.of(), null, null);
        interceptor.intercept(nonMatching, context);
        assertFalse(context.isShortCircuited());

        WireRequest matching = new WireRequest(HttpMethod.GET, URI.create("http://localhost/match"), List.of(), null, null);
        interceptor.intercept(matching, context);
        assertTrue(context.isShortCircuited());
        assertSame(mock, context.shortCircuitResponse().orElseThrow());

        assertThrows(NullPointerException.class, () -> ShortCircuitInterceptor.matching(null, req -> mock));
        assertThrows(NullPointerException.class, () -> ShortCircuitInterceptor.matching(req -> true, null));
    }

    @Test
    @DisplayName("Builder mock fluent methods configure interceptors with null-safety")
    void builderMockMethodsNullSafety() {
        SimpleModuleInfo.Builder smb = SimpleModuleInfo.builder().baseUrl("http://localhost");

        assertThrows(NullPointerException.class, () -> smb.shortCircuit(null));
        assertThrows(NullPointerException.class, () -> smb.mock((WireResponse) null));
        assertThrows(NullPointerException.class, () -> smb.mock(null, WireResponse.of(200)));
        assertThrows(NullPointerException.class, () -> smb.mock(req -> true, (WireResponse) null));

        smb.mock(WireResponse.of(200));
        smb.mock(req -> req.uri().getPath().equals("/ping"), WireResponse.of(200, "PONG"));
        smb.mock(req -> true, req -> WireResponse.of(200, "DYNAMIC"));

        SimpleModuleInfo module = smb.build();
        assertEquals(3, module.getRequestInterceptors().size());

        JRestlyClient.Builder cb = JRestlyClient.builder().baseUrl("http://localhost");
        assertThrows(NullPointerException.class, () -> cb.shortCircuit(null));
        assertThrows(NullPointerException.class, () -> cb.mock((WireResponse) null));
        assertThrows(NullPointerException.class, () -> cb.mock(null, WireResponse.of(200)));
        assertThrows(NullPointerException.class, () -> cb.mock(req -> true, (WireResponse) null));
    }
}
