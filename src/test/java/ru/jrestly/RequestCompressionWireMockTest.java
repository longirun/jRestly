package ru.jrestly;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.compression.DeflateContentCompressor;
import ru.jrestly.compression.GzipContentCompressor;
import ru.jrestly.fixtures.TestDto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative request compression end-to-end (ADR-0041): {@code @Compress} GZIP/DEFLATE
 * sets {@code Content-Encoding}, honors {@code minSizeBytes}, is inherited from the
 * interface and overridable per method, and a client-level default covers unannotated
 * controllers. A manual {@code Content-Encoding} prevents double compression.
 * WireMock e2e.
 */
@DisplayName("Request Compression & Content-Encoding SPI Integration Tests (ADR-0041)")
class RequestCompressionWireMockTest extends BaseWireMockTest {

    public interface DeclarativeCompressApi {
        @Post("/api/items/gzip")
        @Compress(algorithm = CompressionAlgorithm.GZIP)
        TestDto createItemGzip(@RequestBody TestDto dto);

        @Put("/api/items/deflate")
        @Compress(algorithm = CompressionAlgorithm.DEFLATE)
        TestDto updateItemDeflate(@RequestBody TestDto dto);

        @Post("/api/threshold")
        @Compress(algorithm = CompressionAlgorithm.GZIP, minSizeBytes = 200)
        String sendThreshold(@RequestBody String payload);

        @Post("/api/items/async")
        @Compress(algorithm = CompressionAlgorithm.GZIP)
        CompletableFuture<TestDto> createItemAsync(@RequestBody TestDto dto);

        @Post("/api/manual-header")
        @Compress(algorithm = CompressionAlgorithm.GZIP)
        String sendManualHeader(@RequestHeader(name = "Content-Encoding") String encoding, @RequestBody String payload);
    }

    @Compress(algorithm = CompressionAlgorithm.GZIP)
    public interface InterfaceCompressApi {
        @Post("/api/interface/compressed")
        TestDto sendCompressed(@RequestBody TestDto dto);

        @Post("/api/interface/plain")
        @Compress(enabled = false)
        TestDto sendPlain(@RequestBody TestDto dto);
    }

    public interface UnannotatedApi {
        @Post("/api/client-compressed")
        TestDto sendData(@RequestBody TestDto dto);
    }

    public interface CustomSpiApi {
        @Post("/api/custom-spi")
        String echoCustom(@RequestBody String payload);

        @Post("/api/custom-spi/stream")
        InputStream echoCustomStream(@RequestBody String payload);

        @Post("/api/custom-spi/lines")
        Stream<String> echoCustomLines(@RequestBody String payload);
    }

    @Test
    @DisplayName("Declarative @Compress with GZIP compresses JSON request body and sets Content-Encoding header")
    void testDeclarativeGzipPostJson() throws IOException {
        stubFor(post(urlEqualTo("/api/items/gzip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Created Item\",\"description\":\"desc\"}")));

        DeclarativeCompressApi api = client.get(DeclarativeCompressApi.class);
        TestDto requestDto = new TestDto(100L, "Sample payload for request compression testing repeated repeated.", "test");
        TestDto responseDto = api.createItemGzip(requestDto);

        assertNotNull(responseDto);
        assertEquals(42L, responseDto.id());

        verify(postRequestedFor(urlEqualTo("/api/items/gzip"))
                .withHeader("Content-Encoding", equalTo("gzip"))
                .withHeader("Content-Type", equalTo("application/json")));

        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/items/gzip")));
        assertEquals(1, requests.size());
        String decompressedJson = requests.getFirst().getBodyAsString();

        assertTrue(decompressedJson.contains("\"id\":100"));
        assertTrue(decompressedJson.contains("Sample payload for request compression"));
    }

    @Test
    @DisplayName("Declarative @Compress with DEFLATE compresses PUT payload")
    void testDeclarativeDeflatePutJson() throws IOException {
        stubFor(put(urlEqualTo("/api/items/deflate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":100,\"name\":\"Updated\",\"description\":\"desc\"}")));

        DeclarativeCompressApi api = client.get(DeclarativeCompressApi.class);
        TestDto requestDto = new TestDto(100L, "Deflate payload body for update test.", "test");
        TestDto responseDto = api.updateItemDeflate(requestDto);

        assertNotNull(responseDto);
        assertEquals(100L, responseDto.id());

        verify(putRequestedFor(urlEqualTo("/api/items/deflate"))
                .withHeader("Content-Encoding", equalTo("deflate")));

        List<LoggedRequest> requests = findAll(putRequestedFor(urlEqualTo("/api/items/deflate")));
        assertEquals(1, requests.size());
        byte[] compressedBody = requests.getFirst().getBody();
        byte[] decompressedBytes = DeflateContentCompressor.INSTANCE.decompress(compressedBody);
        String decompressedJson = new String(decompressedBytes, StandardCharsets.UTF_8);

        assertTrue(decompressedJson.contains("Deflate payload body"));
    }

    @Test
    @DisplayName("minSizeBytes threshold sends small bodies uncompressed and large bodies compressed")
    void testMinSizeBytesThreshold() throws IOException {
        stubFor(post(urlEqualTo("/api/threshold"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        DeclarativeCompressApi api = client.get(DeclarativeCompressApi.class);

        // 1. Small body below 200 bytes -> uncompressed
        String smallBody = "Short text";
        api.sendThreshold(smallBody);

        List<LoggedRequest> requests1 = findAll(postRequestedFor(urlEqualTo("/api/threshold")));
        assertEquals(1, requests1.size());
        assertFalse(requests1.getFirst().getHeaders().getHeader("Content-Encoding").isPresent(),
                "Small body must not carry Content-Encoding");
        assertEquals(smallBody, requests1.getFirst().getBodyAsString());

        // 2. Large body above 200 bytes -> compressed with GZIP
        String largeBody = "This is a large string intended to easily exceed the 200 byte threshold for compression. "
                .repeat(5);
        api.sendThreshold(largeBody);

        List<LoggedRequest> requests2 = findAll(postRequestedFor(urlEqualTo("/api/threshold")));
        assertEquals(2, requests2.size());
        LoggedRequest secondRequest = requests2.get(1);
        assertTrue(secondRequest.getHeaders().getHeader("Content-Encoding").isPresent());
        assertEquals("gzip", secondRequest.getHeader("Content-Encoding"));
        assertEquals(largeBody, secondRequest.getBodyAsString());
    }

    @Test
    @DisplayName("Interface-level @Compress is inherited by methods, and overridden by @Compress(enabled = false)")
    void testInterfaceLevelCompressionAndMethodOptOut() {
        stubFor(post(urlEqualTo("/api/interface/compressed"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":1,\"name\":\"A\",\"description\":\"B\"}")));
        stubFor(post(urlEqualTo("/api/interface/plain"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":2,\"name\":\"A\",\"description\":\"B\"}")));

        InterfaceCompressApi api = client.get(InterfaceCompressApi.class);

        api.sendCompressed(new TestDto(1L, "Compressed by interface rule", "desc"));
        verify(postRequestedFor(urlEqualTo("/api/interface/compressed"))
                .withHeader("Content-Encoding", equalTo("gzip")));

        api.sendPlain(new TestDto(2L, "Plain by method override", "desc"));
        List<LoggedRequest> plainRequests = findAll(postRequestedFor(urlEqualTo("/api/interface/plain")));
        assertEquals(1, plainRequests.size());
        assertFalse(plainRequests.getFirst().getHeaders().getHeader("Content-Encoding").isPresent(),
                "Method with enabled = false must not be compressed");
    }

    @Test
    @DisplayName("Client-level builder requestCompression applies to unannotated controllers")
    void testClientLevelBuilderConfiguration() {
        stubFor(post(urlEqualTo("/api/client-compressed"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":5,\"name\":\"A\",\"description\":\"B\"}")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .requestCompression(CompressionAlgorithm.GZIP, 50)
                .build();
        try {
            UnannotatedApi api = customClient.get(UnannotatedApi.class);
            api.sendData(new TestDto(5L, "Payload exceeding 50 bytes threshold for client-wide compression", "desc"));

            verify(postRequestedFor(urlEqualTo("/api/client-compressed"))
                    .withHeader("Content-Encoding", equalTo("gzip")));

            List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/client-compressed")));
            assertEquals(1, requests.size());
            assertTrue(requests.getFirst().getBodyAsString().contains("\"id\":5"));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("Explicit manual Content-Encoding header prevents double compression")
    void testAvoidDoubleCompressionWhenHeaderAlreadyPresent() {
        stubFor(post(urlEqualTo("/api/manual-header"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        DeclarativeCompressApi api = client.get(DeclarativeCompressApi.class);
        String precompressedRaw = "already-compressed-content";
        api.sendManualHeader("my-custom-coding", precompressedRaw);

        verify(postRequestedFor(urlEqualTo("/api/manual-header"))
                .withHeader("Content-Encoding", equalTo("my-custom-coding")));

        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/manual-header")));
        assertEquals(1, requests.size());
        assertEquals(precompressedRaw, requests.getFirst().getBodyAsString(),
                "Body must remain unchanged when manual Content-Encoding header is present");
    }

    @Test
    @DisplayName("Async CompletableFuture method compresses request body transparently")
    void testAsyncCompletableFutureTwin() throws Exception {
        stubFor(post(urlEqualTo("/api/items/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":99,\"name\":\"Async Response\",\"description\":\"desc\"}")));

        DeclarativeCompressApi api = client.get(DeclarativeCompressApi.class);
        CompletableFuture<TestDto> future = api.createItemAsync(new TestDto(99L, "Async request body compression payload", "desc"));
        TestDto result = future.get();

        assertNotNull(result);
        assertEquals(99L, result.id());

        verify(postRequestedFor(urlEqualTo("/api/items/async"))
                .withHeader("Content-Encoding", equalTo("gzip")));
    }

    @Test
    @DisplayName("Custom ContentCompressor SPI decompresses response with custom Content-Encoding")
    void testCustomContentCompressorSpi() throws IOException {
        // Invert bytes custom compressor
        ContentCompressor inverter = new ContentCompressor() {
            @Override
            public String encoding() {
                return "inverted";
            }

            @Override
            public byte[] compress(byte[] uncompressed) {
                byte[] res = new byte[uncompressed.length];
                for (int i = 0; i < uncompressed.length; i++) {
                    res[i] = (byte) (~uncompressed[i]);
                }
                return res;
            }

            @Override
            public byte[] decompress(byte[] compressed) {
                byte[] res = new byte[compressed.length];
                for (int i = 0; i < compressed.length; i++) {
                    res[i] = (byte) (~compressed[i]);
                }
                return res;
            }
        };

        byte[] plainBytes = "Hello Inverted World!".getBytes(StandardCharsets.UTF_8);
        byte[] invertedResponse = inverter.compress(plainBytes);

        stubFor(post(urlEqualTo("/api/custom-spi"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "inverted")
                        .withBody(invertedResponse)));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .contentCompressor(inverter)
                .build();
        try {
            CustomSpiApi api = customClient.get(CustomSpiApi.class);
            String response = api.echoCustom("ping");
            assertEquals("Hello Inverted World!", response, "Custom compressor must transparently decompress response");
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("Custom ContentCompressor SPI transparently decompresses streaming response (InputStream)")
    void testCustomContentCompressorStreamingResponse() throws Exception {
        ContentCompressor inverter = new ContentCompressor() {
            @Override
            public String encoding() {
                return "inverted";
            }

            @Override
            public byte[] compress(byte[] uncompressed) {
                byte[] res = new byte[uncompressed.length];
                for (int i = 0; i < uncompressed.length; i++) {
                    res[i] = (byte) (~uncompressed[i]);
                }
                return res;
            }

            @Override
            public byte[] decompress(byte[] compressed) {
                byte[] res = new byte[compressed.length];
                for (int i = 0; i < compressed.length; i++) {
                    res[i] = (byte) (~compressed[i]);
                }
                return res;
            }
        };

        byte[] plainBytes = "Streaming Inverted Content Line 1\nStreaming Inverted Content Line 2".getBytes(StandardCharsets.UTF_8);
        byte[] invertedResponse = inverter.compress(plainBytes);

        stubFor(post(urlEqualTo("/api/custom-spi/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "inverted")
                        .withBody(invertedResponse)));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .contentCompressor(inverter)
                .build();
        try {
            CustomSpiApi api = customClient.get(CustomSpiApi.class);
            try (InputStream stream = api.echoCustomStream("ping")) {
                byte[] actual = stream.readAllBytes();
                assertEquals("Streaming Inverted Content Line 1\nStreaming Inverted Content Line 2", new String(actual, StandardCharsets.UTF_8));
            }
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("Custom ContentCompressor SPI transparently decompresses line stream (Stream<String>)")
    void testCustomContentCompressorLinesResponse() throws Exception {
        ContentCompressor inverter = new ContentCompressor() {
            @Override
            public String encoding() {
                return "inverted";
            }

            @Override
            public byte[] compress(byte[] uncompressed) {
                byte[] res = new byte[uncompressed.length];
                for (int i = 0; i < uncompressed.length; i++) {
                    res[i] = (byte) (~uncompressed[i]);
                }
                return res;
            }

            @Override
            public byte[] decompress(byte[] compressed) {
                byte[] res = new byte[compressed.length];
                for (int i = 0; i < compressed.length; i++) {
                    res[i] = (byte) (~compressed[i]);
                }
                return res;
            }
        };

        byte[] plainBytes = "Alpha\nBeta\nGamma".getBytes(StandardCharsets.UTF_8);
        byte[] invertedResponse = inverter.compress(plainBytes);

        stubFor(post(urlEqualTo("/api/custom-spi/lines"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Content-Encoding", "inverted")
                        .withBody(invertedResponse)));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .contentCompressor(inverter)
                .build();
        try {
            CustomSpiApi api = customClient.get(CustomSpiApi.class);
            try (Stream<String> lines = api.echoCustomLines("ping")) {
                List<String> list = lines.toList();
                assertEquals(List.of("Alpha", "Beta", "Gamma"), list);
            }
        } finally {
            customClient.close();
        }
    }
}
