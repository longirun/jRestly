package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.SimpleCallMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0038: Verification of negotiated HTTP version, response trailers,
 * declared trailer parsing, WireResponse modeling, and CallMetrics integration.
 */
class ResponseHeadersHttp2Test {

    @Test
    @DisplayName("Unpopulated ResponseHeaders defaults to HTTP_1_1 and empty trailers")
    void unpopulatedDefaults() {
        ResponseHeaders headers = new ResponseHeaders();

        assertFalse(headers.isPopulated());
        assertEquals(HttpClient.Version.HTTP_1_1, headers.version());
        assertTrue(headers.isHttp1());
        assertFalse(headers.isHttp2());
        assertTrue(headers.trailers().isEmpty());
        assertTrue(headers.firstTrailer("X-Checksum").isEmpty());
        assertTrue(headers.trailerValues("X-Checksum").isEmpty());
        assertTrue(headers.declaredTrailers().isEmpty());
    }

    @Test
    @DisplayName("Populated ResponseHeaders with HTTP_2 reflects protocol flags")
    void populatedWithHttp2() {
        ResponseHeaders headers = new ResponseHeaders(200, List.of(new Header("Content-Type", "application/json")), Duration.ofMillis(25), HttpClient.Version.HTTP_2);

        assertTrue(headers.isPopulated());
        assertEquals(HttpClient.Version.HTTP_2, headers.version());
        assertTrue(headers.isHttp2());
        assertFalse(headers.isHttp1());
    }

    @Test
    @DisplayName("Populated ResponseHeaders with trailers allows case-insensitive lookups")
    void populatedWithTrailers() {
        List<Header> trailers = List.of(
                new Header("X-Checksum-Sha256", "abc123def456"),
                new Header("Server-Timing", "app;dur=12.4"),
                new Header("Server-Timing", "db;dur=5.1")
        );
        ResponseHeaders headers = new ResponseHeaders(200, List.of(), Duration.ofMillis(50), HttpClient.Version.HTTP_2, trailers);

        assertEquals(3, headers.trailers().size());
        assertEquals(Optional.of("abc123def456"), headers.firstTrailer("x-checksum-sha256"));
        assertEquals(Optional.of("abc123def456"), headers.firstTrailer("X-CHECKSUM-SHA256"));

        List<String> timing = headers.trailerValues("server-timing");
        assertEquals(2, timing.size());
        assertEquals("app;dur=12.4", timing.get(0));
        assertEquals("db;dur=5.1", timing.get(1));

        assertTrue(headers.firstTrailer("non-existent").isEmpty());
        assertTrue(headers.trailerValues("non-existent").isEmpty());
        assertTrue(headers.firstTrailer(null).isEmpty());
        assertTrue(headers.trailerValues(null).isEmpty());
    }

    @Test
    @DisplayName("RFC 7230 / RFC 9110 declaredTrailers parses comma-separated Trailer header names")
    void declaredTrailersParsing() {
        List<Header> headersList = List.of(
                new Header("Content-Type", "application/json"),
                new Header("Trailer", "Expires, X-Checksum-Md5"),
                new Header("Trailer", "Server-Timing")
        );
        ResponseHeaders headers = new ResponseHeaders(200, headersList, Duration.ofMillis(10));

        List<String> declared = headers.declaredTrailers();
        assertEquals(3, declared.size());
        assertEquals("Expires", declared.get(0));
        assertEquals("X-Checksum-Md5", declared.get(1));
        assertEquals("Server-Timing", declared.get(2));
    }

    @Test
    @DisplayName("declaredTrailers returns empty list when Trailer header is missing or empty")
    void declaredTrailersEmpty() {
        ResponseHeaders noTrailer = new ResponseHeaders(200, List.of(new Header("Content-Type", "text/plain")), Duration.ZERO);
        assertTrue(noTrailer.declaredTrailers().isEmpty());

        ResponseHeaders emptyTrailer = new ResponseHeaders(200, List.of(new Header("Trailer", "  ,   ")), Duration.ZERO);
        assertTrue(emptyTrailer.declaredTrailers().isEmpty());
    }

    @Test
    @DisplayName("ResponseHeaders.capture forwards version and trailers through CURRENT_CAPTOR")
    void captureForwardsVersionAndTrailers() {
        CapturedResponse<String> captured = ResponseHeaders.capture(() -> {
            Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
            assertNotNull(captor);
            captor.accept(new ResponseHeaders(200, List.of(), Duration.ofMillis(15), HttpClient.Version.HTTP_2, List.of(new Header("X-Trailer", "pass"))));
            return "ok";
        });

        assertEquals("ok", captured.result());
        ResponseHeaders headers = captured.headers();
        assertEquals(HttpClient.Version.HTTP_2, headers.version());
        assertTrue(headers.isHttp2());
        assertEquals(Optional.of("pass"), headers.firstTrailer("x-trailer"));
    }

    @Test
    @DisplayName("WireResponse backward compatible constructors and defensive normalization")
    void wireResponseConstructorsAndImmutability() {
        WireResponse wr3 = new WireResponse(200, null, new byte[0]);
        assertEquals(HttpClient.Version.HTTP_1_1, wr3.version());
        assertTrue(wr3.headers().isEmpty());
        assertTrue(wr3.trailers().isEmpty());

        WireResponse wr5 = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_2);
        assertEquals(HttpClient.Version.HTTP_2, wr5.version());
        assertTrue(wr5.trailers().isEmpty());

        WireResponse wr6 = new WireResponse(200, List.of(), null, null, null, null);
        assertEquals(HttpClient.Version.HTTP_1_1, wr6.version());
        assertTrue(wr6.trailers().isEmpty());
    }

    @Test
    @DisplayName("CallMetrics.version() returns response version or defaults to HTTP_1_1")
    void callMetricsVersionInspection() {
        WireRequest wireReq = WireRequest.builder()
                .uri(URI.create("http://localhost"))
                .method(HttpMethod.GET)
                .build();
        RequestContext dummyContext = new SimpleRequestContext("test-module", Object.class, Object.class.getMethods()[0], new Object[0]);

        WireResponse wireResp = new WireResponse(200, List.of(), new byte[0], null, HttpClient.Version.HTTP_2, List.of());
        CallMetrics metricsWithResp = new SimpleCallMetrics(dummyContext, wireReq, Optional.of(wireResp), Optional.empty(), Duration.ofMillis(5), 1, 0, 0);
        assertEquals(HttpClient.Version.HTTP_2, metricsWithResp.version());

        CallMetrics metricsNoResp = new SimpleCallMetrics(dummyContext, wireReq, Optional.empty(), Optional.of(new RuntimeException("failed")), Duration.ofMillis(5), 1, 0, 0);
        assertEquals(HttpClient.Version.HTTP_1_1, metricsNoResp.version());
    }
}
