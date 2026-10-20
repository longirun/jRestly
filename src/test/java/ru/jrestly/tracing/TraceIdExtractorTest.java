package ru.jrestly.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TraceIdExtractor}.
 */
class TraceIdExtractorTest {

    @Test
    @DisplayName("private constructor cannot be instantiated normally and can be invoked via reflection")
    void privateConstructor() throws Exception {
        Constructor<TraceIdExtractor> constructor = TraceIdExtractor.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        TraceIdExtractor instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    @DisplayName("extract from null or empty headers/request/response returns null")
    void nullAndEmptyGuards() {
        assertNull(TraceIdExtractor.extract((List<Header>) null));
        assertNull(TraceIdExtractor.extract(List.of()));
        assertNull(TraceIdExtractor.extract((WireRequest) null));
        assertNull(TraceIdExtractor.extract((WireResponse) null));
        assertNull(TraceIdExtractor.extract(new WireRequest(HttpMethod.GET, URI.create("http://example.com"), null, null, null, null, null)));
    }

    @Test
    @DisplayName("extracts traceId from W3C traceparent header")
    void extractFromW3CTraceparent() {
        List<Header> headers = List.of(
                new Header("Content-Type", "application/json"),
                new Header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        );
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", TraceIdExtractor.extract(headers));
    }

    @Test
    @DisplayName("extracts traceId from case-insensitive traceparent header")
    void extractFromCaseInsensitiveTraceparent() {
        List<Header> headers = List.of(
                new Header("TraceParent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        );
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", TraceIdExtractor.extract(headers));
    }

    @Test
    @DisplayName("extracts correlation ID from standard correlation headers")
    void extractFromCorrelationHeaders() {
        assertEquals("req-123", TraceIdExtractor.extract(List.of(new Header("X-Request-ID", "req-123"))));
        assertEquals("corr-456", TraceIdExtractor.extract(List.of(new Header("x-correlation-id", "corr-456"))));
        assertEquals("trace-789", TraceIdExtractor.extract(List.of(new Header("X-Trace-Id", "trace-789"))));
        assertEquals("direct-1", TraceIdExtractor.extract(List.of(new Header("trace-id", "direct-1"))));
        assertEquals("direct-2", TraceIdExtractor.extract(List.of(new Header("request-id", "direct-2"))));
        assertEquals("direct-3", TraceIdExtractor.extract(List.of(new Header("correlation-id", "direct-3"))));
    }

    @Test
    @DisplayName("extracts from WireRequest and WireResponse")
    void extractFromWireModels() {
        WireRequest req = new WireRequest(
                HttpMethod.GET,
                URI.create("http://example.com"),
                List.of(new Header("X-Request-ID", "wire-req-001")),
                null, null, null, null
        );
        assertEquals("wire-req-001", TraceIdExtractor.extract(req));

        WireResponse resp = new WireResponse(
                200,
                List.of(new Header("X-Correlation-ID", "wire-resp-002")),
                new byte[0],
                null,
                java.net.http.HttpClient.Version.HTTP_1_1
        );
        assertEquals("wire-resp-002", TraceIdExtractor.extract(resp));
    }

    @Test
    @DisplayName("ignores invalid traceparent or blank values and falls back or returns null")
    void invalidAndBlankFallback() {
        List<Header> invalidTraceparent = List.of(
                new Header("traceparent", "invalid-traceparent"),
                new Header("X-Request-ID", "fallback-id")
        );
        assertEquals("fallback-id", TraceIdExtractor.extract(invalidTraceparent));

        List<Header> blankHeaders = List.of(
                new Header("X-Request-ID", "   "),
                new Header("traceparent", "   ")
        );
        assertNull(TraceIdExtractor.extract(blankHeaders));

        // null header elements, null names, null values
        java.util.List<Header> weirdHeaders = new java.util.ArrayList<>();
        weirdHeaders.add(null);
        weirdHeaders.add(new Header(null, "some-val"));
        weirdHeaders.add(new Header("traceparent", null));
        weirdHeaders.add(new Header("X-Request-ID", null));
        assertNull(TraceIdExtractor.extract(weirdHeaders));
    }
}
