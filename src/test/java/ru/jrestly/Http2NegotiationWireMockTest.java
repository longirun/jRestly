package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.http.CapturedResponse;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.UnexpectedStatusException;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0038: WireMock integration suite verifying HTTP version negotiation,
 * ResponseHeaders version and declared trailer inspection, telemetry metrics,
 * GZIP decompression, and async CompletableFuture pipelines.
 */
class Http2NegotiationWireMockTest extends BaseWireMockTest {

    private final List<AbstractHttpClient> createdClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDownCreatedClients() {
        for (AbstractHttpClient clientToClose : createdClients) {
            clientToClose.close();
        }
        createdClients.clear();
    }

    private JRestlyClient createClient() {
        JRestlyClient newClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .build();
        createdClients.add(newClient);
        return newClient;
    }

    private JRestlyClient createClientWithMetrics(ClientMetricsListener listener) {
        JRestlyClient newClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .metricsListener(listener)
                .build();
        createdClients.add(newClient);
        return newClient;
    }

    public interface NegotiationController {

        @Get("/api/protocol")
        String getProtocol(ResponseHeaders headers);

        @Get("/api/pinned-http1")
        @HttpVersion(HttpClient.Version.HTTP_1_1)
        String getPinnedHttp1(ResponseHeaders headers);

        @Get("/api/trailers")
        String getWithTrailers(ResponseHeaders headers);

        @Get("/api/capture")
        String getForCapture();

        @Get("/api/gzip")
        String getGzip(ResponseHeaders headers);

        @Get("/api/async")
        CompletableFuture<String> getAsync(ResponseHeaders headers);

        @Get("/api/fail")
        String getFailing();
    }

    @Test
    @DisplayName("Method parameter ResponseHeaders receives negotiated HTTP/2 and pinned HTTP/1.1 from WireMock server")
    void negotiatedHttpVersionInMethodParameter() {
        stubFor(get(urlEqualTo("/api/protocol"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("protocol-ok")));
        stubFor(get(urlEqualTo("/api/pinned-http1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("http1-ok")));

        NegotiationController controller = createClient().get(NegotiationController.class);

        ResponseHeaders headers = new ResponseHeaders();
        String result = controller.getProtocol(headers);
        assertEquals("protocol-ok", result);
        assertTrue(headers.isPopulated());
        assertEquals(HttpClient.Version.HTTP_2, headers.version());
        assertTrue(headers.isHttp2());
        assertFalse(headers.isHttp1());

        ResponseHeaders headers11 = new ResponseHeaders();
        String result11 = controller.getPinnedHttp1(headers11);
        assertEquals("http1-ok", result11);
        assertTrue(headers11.isPopulated());
        assertEquals(HttpClient.Version.HTTP_1_1, headers11.version());
        assertTrue(headers11.isHttp1());
        assertFalse(headers11.isHttp2());
    }

    @Test
    @DisplayName("Server response with Trailer header parses declared trailer field names")
    void declaredTrailersFromHttpServer() {
        stubFor(get(urlEqualTo("/api/trailers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Trailer", "Checksum, Server-Timing, X-Audit-Id")
                        .withBody("trailers-body")));

        NegotiationController controller = createClient().get(NegotiationController.class);
        ResponseHeaders headers = new ResponseHeaders();
        String result = controller.getWithTrailers(headers);

        assertEquals("trailers-body", result);
        List<String> declared = headers.declaredTrailers();
        assertEquals(3, declared.size());
        assertEquals("Checksum", declared.get(0));
        assertEquals("Server-Timing", declared.get(1));
        assertEquals("X-Audit-Id", declared.get(2));
    }

    @Test
    @DisplayName("ResponseHeaders.capture pattern captures negotiated protocol version")
    void capturePatternIncludesVersion() {
        stubFor(get(urlEqualTo("/api/capture"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("capture-body")));

        NegotiationController controller = createClient().get(NegotiationController.class);
        CapturedResponse<String> captured = ResponseHeaders.capture(controller::getForCapture);

        assertEquals("capture-body", captured.result());
        assertTrue(captured.headers().isPopulated());
        assertEquals(HttpClient.Version.HTTP_2, captured.headers().version());
        assertTrue(captured.headers().isHttp2());
    }

    @Test
    @DisplayName("ClientMetricsListener receives negotiated HTTP protocol version in CallMetrics")
    void clientMetricsObservabilityCapturesNegotiatedVersion() {
        stubFor(get(urlEqualTo("/api/protocol"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("metrics-body")));

        AtomicReference<CallMetrics> metricsRef = new AtomicReference<>();
        ClientMetricsListener listener = metricsRef::set;

        NegotiationController controller = createClientWithMetrics(listener).get(NegotiationController.class);
        ResponseHeaders headers = new ResponseHeaders();
        controller.getProtocol(headers);

        CallMetrics metrics = metricsRef.get();
        assertNotNull(metrics);
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.isSuccess());
        assertEquals(HttpClient.Version.HTTP_2, metrics.version());
    }

    @Test
    @DisplayName("GZIP decompressed response preserves negotiated HTTP protocol version")
    void gzipDecompressionPreservesNegotiatedVersion() throws IOException {
        byte[] gzipped = gzip("decompressed-content");
        stubFor(get(urlEqualTo("/api/gzip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withHeader("Content-Type", "text/plain")
                        .withBody(gzipped)));

        NegotiationController controller = createClient().get(NegotiationController.class);
        ResponseHeaders headers = new ResponseHeaders();
        String result = controller.getGzip(headers);

        assertEquals("decompressed-content", result);
        assertEquals(HttpClient.Version.HTTP_2, headers.version());
        assertTrue(headers.isHttp2());
    }

    @Test
    @DisplayName("Async CompletableFuture call populates negotiated HTTP protocol version")
    void asyncCompletableFutureCallCarriesVersion() {
        stubFor(get(urlEqualTo("/api/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("async-result")));

        NegotiationController controller = createClient().get(NegotiationController.class);
        ResponseHeaders headers = new ResponseHeaders();
        CompletableFuture<String> future = controller.getAsync(headers);

        assertEquals("async-result", future.join());
        assertTrue(headers.isPopulated());
        assertEquals(HttpClient.Version.HTTP_2, headers.version());
        assertTrue(headers.isHttp2());
    }

    @Test
    @DisplayName("Failed 500 response still populates negotiated protocol version in captured metadata")
    void errorResponseStillCarriesNegotiatedVersion() {
        stubFor(get(urlEqualTo("/api/fail"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("server-error")));

        NegotiationController controller = createClient().get(NegotiationController.class);

        assertThrows(UnexpectedStatusException.class, () -> {
            ResponseHeaders.capture(controller::getFailing);
        });
    }

    private static byte[] gzip(String input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(input.getBytes());
        }
        return baos.toByteArray();
    }
}
