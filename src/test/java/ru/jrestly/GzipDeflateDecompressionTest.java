package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for transparent response decompression (ADR-0021)
 * with WireMock server: GZIP, x-gzip, Deflate (RFC 1950 zlib and RFC 1951 raw),
 * streaming, error bodies (@OnError and UnexpectedStatusException), async, and opt-out.
 */
@DisplayName("Transparent GZIP / Deflate Decompression Integration Tests (ADR-0021)")
class GzipDeflateDecompressionTest extends BaseWireMockTest {

    interface DecompressionApi {
        @Get("/api/items/${id}")
        TestDto getItem(@PathVariable(name = "id") Long id);

        @Get("/api/items/${id}/async")
        CompletableFuture<TestDto> getItemAsync(@PathVariable(name = "id") Long id);

        @Get("/api/text")
        String getText();

        @Get("/api/stream")
        InputStream getStream();

        @Get("/api/lines")
        Stream<String> getLines();

        @Get("/api/error-parsed")
        @OnError(statuses = {400}, errorObject = TestErrorDto.class)
        TestDto getWithError();

        @Get("/api/unexpected")
        @ExpectStatus(statuses = {200})
        TestDto getUnexpected();

        @Get("/api/custom-encoding")
        String getWithCustomAcceptEncoding(@RequestHeader(name = "Accept-Encoding") String encoding);
    }

    @Test
    @DisplayName("GZIP response: client automatically requests and transparently decompresses JSON to DTO")
    void gzipJsonResponseDecompressedToDto() throws IOException {
        String json = "{\"id\":42,\"name\":\"GzipItem\",\"description\":\"test\"}";
        byte[] gzipped = gzip(json.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        TestDto dto = api.getItem(42L);

        assertNotNull(dto);
        assertEquals(42L, dto.id());
        assertEquals("GzipItem", dto.name());

        verify(getRequestedFor(urlEqualTo("/api/items/42"))
                .withHeader("Accept-Encoding", equalTo("gzip, deflate")));
    }

    @Test
    @DisplayName("x-gzip response: transparently decompresses to DTO")
    void xGzipJsonResponseDecompressedToDto() throws IOException {
        String json = "{\"id\":100,\"name\":\"XGzipItem\",\"description\":\"test\"}";
        byte[] gzipped = gzip(json.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/items/100"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "x-gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        TestDto dto = api.getItem(100L);

        assertNotNull(dto);
        assertEquals(100L, dto.id());
        assertEquals("XGzipItem", dto.name());
    }

    @Test
    @DisplayName("Deflate (zlib RFC 1950) response: transparently decompresses to DTO")
    void deflateZlibJsonResponseDecompressedToDto() throws IOException {
        String json = "{\"id\":77,\"name\":\"DeflateItem\",\"description\":\"test\"}";
        byte[] deflated = deflateZlib(json.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/items/77"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "deflate")
                        .withBody(deflated)));

        DecompressionApi api = client.get(DecompressionApi.class);
        TestDto dto = api.getItem(77L);

        assertNotNull(dto);
        assertEquals(77L, dto.id());
        assertEquals("DeflateItem", dto.name());
    }

    @Test
    @DisplayName("Deflate (raw RFC 1951) response: fallback transparently decompresses to DTO")
    void deflateRawJsonResponseDecompressedToDto() throws IOException {
        String json = "{\"id\":88,\"name\":\"RawDeflateItem\",\"description\":\"test\"}";
        byte[] rawDeflated = deflateRaw(json.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/items/88"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "deflate")
                        .withBody(rawDeflated)));

        DecompressionApi api = client.get(DecompressionApi.class);
        TestDto dto = api.getItem(88L);

        assertNotNull(dto);
        assertEquals(88L, dto.id());
        assertEquals("RawDeflateItem", dto.name());
    }

    @Test
    @DisplayName("GZIP text response: transparently decompresses to String")
    void gzipTextResponseDecompressedToString() throws IOException {
        String expected = "Hello GZIP compressed text content!";
        byte[] gzipped = gzip(expected.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/text"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        String text = api.getText();

        assertEquals(expected, text);
    }

    @Test
    @DisplayName("GZIP streaming response: InputStream transparently yields decompressed binary bytes")
    void gzipStreamingResponseYieldsDecompressedBytes() throws IOException {
        byte[] original = "Streaming compressed binary payload 1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(original);

        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        try (InputStream stream = api.getStream()) {
            assertNotNull(stream);
            byte[] read = stream.readAllBytes();
            assertArrayEquals(original, read);
        }
    }

    @Test
    @DisplayName("GZIP line streaming: Stream<String> transparently yields decompressed lines")
    void gzipLineStreamingYieldsDecompressedLines() throws IOException {
        String linesText = "line1\nline2\nline3";
        byte[] gzipped = gzip(linesText.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/lines"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        try (Stream<String> lines = api.getLines()) {
            assertNotNull(lines);
            assertEquals(java.util.List.of("line1", "line2", "line3"), lines.toList());
        }
    }

    @Test
    @DisplayName("GZIP async response: CompletableFuture<TestDto> completes with decompressed DTO")
    void gzipAsyncCompletesWithDecompressedDto() throws Exception {
        String json = "{\"id\":999,\"name\":\"AsyncGzip\",\"description\":\"test\"}";
        byte[] gzipped = gzip(json.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/items/999/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        CompletableFuture<TestDto> future = api.getItemAsync(999L);
        TestDto dto = future.get();

        assertNotNull(dto);
        assertEquals(999L, dto.id());
        assertEquals("AsyncGzip", dto.name());
    }

    @Test
    @DisplayName("GZIP error response: @OnError parses decompressed error JSON")
    void gzipErrorResponseParsedByOnError() throws IOException {
        String errorJson = "{\"code\":\"4001\",\"message\":\"Invalid payload\"}";
        byte[] gzipped = gzip(errorJson.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/error-parsed"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        HandledException ex = assertThrows(HandledException.class, api::getWithError);

        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getDetails() instanceof TestErrorDto);
        TestErrorDto errorDto = ex.getDetails();
        assertEquals("4001", errorDto.code());
        assertEquals("Invalid payload", errorDto.message());
    }

    @Test
    @DisplayName("GZIP error response: UnexpectedStatusException contains decompressed payload")
    void gzipErrorResponseInUnexpectedStatusException() throws IOException {
        String errorPayload = "Internal Server Failure: out of memory";
        byte[] gzipped = gzip(errorPayload.getBytes(StandardCharsets.UTF_8));

        stubFor(get(urlEqualTo("/api/unexpected"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)));

        DecompressionApi api = client.get(DecompressionApi.class);
        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, api::getUnexpected);

        assertEquals(500, ex.getStatusCode());
        assertEquals(errorPayload, ex.getRawBody());
    }

    @Test
    @DisplayName("Disabled decompression: Accept-Encoding is not sent and response is not decompressed")
    void disabledDecompressionSkipsAcceptEncodingAndDecompression() {
        JRestlyClient disabledClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .decompression(false)
                .build();
        try {
            stubFor(get(urlEqualTo("/api/text"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("plain body")));

            DecompressionApi api = disabledClient.get(DecompressionApi.class);
            String text = api.getText();
            assertEquals("plain body", text);

            verify(getRequestedFor(urlEqualTo("/api/text"))
                    .withoutHeader("Accept-Encoding"));
        } finally {
            disabledClient.close();
        }
    }

    @Test
    @DisplayName("Explicit Accept-Encoding: caller's custom header is sent as-is")
    void callerExplicitAcceptEncodingSentAsIs() {
        stubFor(get(urlEqualTo("/api/custom-encoding"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("identity content")));

        DecompressionApi api = client.get(DecompressionApi.class);
        String result = api.getWithCustomAcceptEncoding("identity");
        assertEquals("identity content", result);

        verify(getRequestedFor(urlEqualTo("/api/custom-encoding"))
                .withHeader("Accept-Encoding", equalTo("identity")));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateZlib(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateRaw(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(data);
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }
}
