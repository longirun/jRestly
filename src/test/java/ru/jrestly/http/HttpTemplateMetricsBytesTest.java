package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit gate for the ADR-0036 metrics byte accounting seams
 * ({@link HttpTemplate#computeRequestBytes(WireRequest)} /
 * {@link HttpTemplate#computeResponseBytes(WireResponse)}):
 * buffered bodies are measured, supplier-rendered (streaming) bodies are
 * reported as unknown without invoking the user's producer
 * (ADR-0036 Amendment #193), and response bytes fall back to
 * {@code Content-Length} when the body is not buffered.
 */
@DisplayName("HttpTemplateMetricsBytesTest")
class HttpTemplateMetricsBytesTest {

    private static WireRequest request(byte[] body, Supplier<? extends HttpRequest.BodyPublisher> supplier) {
        return new WireRequest(
                HttpMethod.POST,
                URI.create("https://example.com/upload"),
                List.of(),
                "application/octet-stream",
                body,
                null,
                null,
                supplier
        );
    }

    @Test
    @DisplayName("Supplier-rendered body is unknown (-1) and the supplier is not invoked")
    void supplierRenderedBodyIsProbedWithoutInvokingTheSupplier() {
        AtomicInteger invocations = new AtomicInteger();
        Supplier<HttpRequest.BodyPublisher> counting = () -> {
            invocations.incrementAndGet();
            return HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
        };

        assertEquals(-1, HttpTemplate.computeRequestBytes(request(null, counting)));
        assertEquals(0, invocations.get(), "metrics probe must not materialize the streaming body");
    }

    @Test
    @DisplayName("Buffered byte[] body is measured from the bytes, supplier untouched")
    void bufferedBodyIsMeasured() {
        byte[] body = "1234567890".getBytes(StandardCharsets.UTF_8);
        AtomicInteger invocations = new AtomicInteger();

        assertEquals(body.length, HttpTemplate.computeRequestBytes(request(body, () -> {
            invocations.incrementAndGet();
            return HttpRequest.BodyPublishers.noBody();
        })));
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("Body-less and null requests are unknown (-1)")
    void bodylessRequestIsUnknown() {
        assertEquals(-1, HttpTemplate.computeRequestBytes(request(null, null)));
        assertEquals(-1, HttpTemplate.computeRequestBytes(null));
    }

    @Test
    @DisplayName("Response bytes come from the buffered body when present")
    void responseBytesFromBufferedBody() {
        byte[] body = "response-body".getBytes(StandardCharsets.UTF_8);
        WireResponse response = new WireResponse(
                200,
                List.of(new Header("Content-Length", "999")),
                body,
                null,
                java.net.http.HttpClient.Version.HTTP_1_1,
                List.of()
        );
        assertEquals(body.length, HttpTemplate.computeResponseBytes(response));
    }

    @Test
    @DisplayName("Response bytes fall back to Content-Length for unbuffered bodies")
    void responseBytesFallBackToContentLength() {
        WireResponse response = new WireResponse(
                200,
                List.of(new Header("Content-Length", "4242")),
                null,
                null,
                java.net.http.HttpClient.Version.HTTP_1_1,
                List.of()
        );
        assertEquals(4242L, HttpTemplate.computeResponseBytes(response));
    }

    @Test
    @DisplayName("Null response and unparsable Content-Length are unknown (-1)")
    void responseBytesUnknown() {
        assertEquals(-1, HttpTemplate.computeResponseBytes(null));
        WireResponse chunked = new WireResponse(
                200,
                List.of(new Header("Content-Length", "not-a-number")),
                null,
                null,
                java.net.http.HttpClient.Version.HTTP_1_1,
                List.of()
        );
        assertEquals(-1, HttpTemplate.computeResponseBytes(chunked));
    }
}
