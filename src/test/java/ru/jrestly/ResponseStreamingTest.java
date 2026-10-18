package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code InputStream} streaming responses (ADR-0015): byte-exact download (sync and
 * async via {@code CompletableFuture<InputStream>}), plus error paths — an unexpected
 * status drains the stream into {@code UnexpectedStatusException} and {@code @OnError}
 * decodes it into {@code HandledException}. WireMock e2e.
 */
class ResponseStreamingTest extends BaseWireMockTest {

    interface StreamErrorApi {
        @Get("/api/stream-error")
        @OnError(statuses = {400}, errorObject = TestErrorDto.class)
        InputStream streamWithError();
    }

    @Test
    @DisplayName("sync: byte-exact streaming download of 1 MiB body")
    void syncByteExactDownload() throws IOException {
        byte[] payload = new byte[1024 * 1024];
        new Random(42).nextBytes(payload);

        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(payload)));

        try (InputStream stream = controller.getStream()) {
            assertNotNull(stream);
            byte[] downloaded = stream.readAllBytes();
            assertArrayEquals(payload, downloaded);
        }
    }

    @Test
    @DisplayName("async: byte-exact streaming download via CompletableFuture<InputStream>")
    void asyncByteExactDownload() throws IOException {
        byte[] payload = new byte[1024 * 1024];
        new Random(42).nextBytes(payload);

        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(payload)));

        CompletableFuture<InputStream> future = controller.getStreamAsync();
        try (InputStream stream = future.join()) {
            assertNotNull(stream);
            byte[] downloaded = stream.readAllBytes();
            assertArrayEquals(payload, downloaded);
        }
    }

    @Test
    @DisplayName("unexpected status consumes the stream and throws UnexpectedStatusException with raw payload")
    void unexpectedStatusThrowsException() {
        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Internal Server Error: storage failed")));

        UnexpectedStatusException exception = assertThrows(UnexpectedStatusException.class,
                () -> controller.getStream());

        assertEquals(500, exception.getStatusCode());
        assertEquals("Internal Server Error: storage failed", exception.getRawBody());
    }

    @Test
    @DisplayName("@OnError status consumes the stream and throws HandledException with decoded object")
    void onErrorConsumesStreamAndDecodesObject() {
        stubFor(get(urlEqualTo("/api/stream-error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"STREAM_ERROR\",\"message\":\"Stream unavailable\"}")));

        StreamErrorApi api = client.get(StreamErrorApi.class);
        HandledException exception = assertThrows(HandledException.class, api::streamWithError);

        assertEquals(400, exception.getStatusCode());
        TestErrorDto error = exception.getDetails();
        assertNotNull(error);
        assertEquals("STREAM_ERROR", error.code());
        assertEquals("Stream unavailable", error.message());
    }

    @Test
    @DisplayName("server fault results in exception (read failure or dispatch failure)")
    void serverFaultFailure() throws IOException {
        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Depending on whether the reset happens before headers or during body streaming:
        // Before headers -> dispatch RuntimeException wrapping IOException.
        // After headers -> stream returned, IOException on readAllBytes().
        try {
            try (InputStream stream = controller.getStream()) {
                assertThrows(IOException.class, stream::readAllBytes);
            }
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof IOException, "Expected IOException in cause, got: " + e.getCause());
        }
    }
}
