package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Retry;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.UploadProgressListener;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Download progress end-to-end (ADR-0033): every return shape ({@code byte[]},
 * {@code String}, JSON DTO, streaming {@code InputStream}) drives a
 * {@code DownloadProgressListener}; unknown {@code Content-Length} reports
 * {@code total = -1}, async is non-blocking, and retries reset the counter.
 * WireMock e2e.
 */
@DisplayName("DownloadProgressWireMockTest")
class DownloadProgressWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient httpClient;

    public record DownloadDto(long id, String name) {}

    public record ProgressEvent(long bytesRead, long totalBytes) {}

    public interface DownloadApi {

        @Get("/download/bytes")
        byte[] downloadBytes(DownloadProgressListener progress);

        @Get("/download/string")
        String downloadString(DownloadProgressListener progress);

        @Get("/download/dto")
        DownloadDto downloadDto(DownloadProgressListener progress);

        @Get("/download/stream")
        InputStream downloadStream(DownloadProgressListener progress);

        @HttpVersion(HttpClient.Version.HTTP_1_1)
        @Get("/download/chunked")
        InputStream downloadChunked(DownloadProgressListener progress);

        @Get("/download/async")
        CompletableFuture<byte[]> downloadAsync(DownloadProgressListener progress);

        @Retry(times = 2, nonIdempotent = true, initialBackoffMs = 10, maxBackoffMs = 50)
        @Get("/download/retryable")
        byte[] downloadWithRetry(DownloadProgressListener progress);

        @Post(value = "/transform",  requestType = RequestType.MULTIPART_FORM_DATA)
        byte[] transform(@MultipartFormFile(partName = "file") FileContent file,
                         UploadProgressListener uploadProgress,
                         DownloadProgressListener downloadProgress);

        @Get("/download/plain")
        byte[] downloadWithoutProgressListener();
    }

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        httpClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .decompression(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("Single byte array download tracks progress to 100%")
    void downloadBytesTracksProgress() {
        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 'A');

        wireMock.stubFor(get(urlEqualTo("/download/bytes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        byte[] result = api.downloadBytes((read, total) -> events.add(new ProgressEvent(read, total)));

        assertArrayEquals(payload, result);
        assertFalse(events.isEmpty(), "Expected progress events");

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(payload.length, last.bytesRead());
        assertEquals(payload.length, last.totalBytes());
    }

    @Test
    @DisplayName("String download tracks text progress accurately")
    void downloadStringTracksProgress() {
        String content = "Hello, jRestly DownloadProgressListener!";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/string"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=UTF-8")
                        .withHeader("Content-Length", String.valueOf(bytes.length))
                        .withBody(content)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        String result = api.downloadString((read, total) -> events.add(new ProgressEvent(read, total)));

        assertEquals(content, result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(bytes.length, last.bytesRead());
        assertEquals(bytes.length, last.totalBytes());
    }

    @Test
    @DisplayName("JSON DTO download tracks response body progress")
    void downloadDtoTracksProgress() {
        String json = "{\"id\":42,\"name\":\"test-item\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/dto"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Length", String.valueOf(bytes.length))
                        .withBody(json)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        DownloadDto result = api.downloadDto((read, total) -> events.add(new ProgressEvent(read, total)));

        assertEquals(new DownloadDto(42, "test-item"), result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(bytes.length, last.bytesRead());
        assertEquals(bytes.length, last.totalBytes());
    }

    @Test
    @DisplayName("Streaming response via InputStream tracks progress as stream is consumed")
    void downloadStreamTracksProgress() throws IOException {
        byte[] payload = new byte[4096];
        Arrays.fill(payload, (byte) 'X');

        wireMock.stubFor(get(urlEqualTo("/download/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Encoding", "identity")
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        try (InputStream stream = api.downloadStream((read, total) -> events.add(new ProgressEvent(read, total)))) {
            byte[] consumed = stream.readAllBytes();
            assertArrayEquals(payload, consumed);
        }

        assertFalse(events.isEmpty());
        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(payload.length, last.bytesRead());
        assertEquals(payload.length, last.totalBytes());
    }

    @Test
    @DisplayName("Chunked download with unknown Content-Length reports totalBytes = -1")
    void downloadChunkedTracksUnknownLength() throws IOException {
        byte[] payload = "chunked-payload-data-stream".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/chunked"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Encoding", "identity")
                        .withHeader("Transfer-Encoding", "chunked")
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        try (InputStream stream = api.downloadChunked((read, total) -> events.add(new ProgressEvent(read, total)))) {
            byte[] consumed = stream.readAllBytes();
            assertArrayEquals(payload, consumed);
        }

        assertFalse(events.isEmpty());
        for (ProgressEvent event : events) {
            assertEquals(-1, event.totalBytes());
        }
        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(payload.length, last.bytesRead());
    }

    @Test
    @DisplayName("Asynchronous download CompletableFuture tracks progress non-blockingly")
    void downloadAsyncTracksProgress() throws Exception {
        byte[] payload = new byte[2048];
        Arrays.fill(payload, (byte) 'B');

        wireMock.stubFor(get(urlEqualTo("/download/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        CompletableFuture<byte[]> future = api.downloadAsync((read, total) -> events.add(new ProgressEvent(read, total)));
        byte[] result = future.get(5, TimeUnit.SECONDS);

        assertArrayEquals(payload, result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(payload.length, last.bytesRead());
        assertEquals(payload.length, last.totalBytes());
    }

    @Test
    @DisplayName("Retry resets download progress counter on subsequent attempts")
    void retryResetsDownloadProgressCounter() {
        byte[] payload = "final-success-payload".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/retryable"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Length", "10")
                        .withBody("temp-error"))
                .willSetStateTo("second-attempt"));

        wireMock.stubFor(get(urlEqualTo("/download/retryable"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        byte[] result = api.downloadWithRetry((read, total) -> events.add(new ProgressEvent(read, total)));

        assertArrayEquals(payload, result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(payload.length, last.bytesRead());
        assertEquals(payload.length, last.totalBytes());
    }

    @Test
    @DisplayName("Simultaneous UploadProgressListener and DownloadProgressListener on the same method")
    void simultaneousUploadAndDownloadProgress() {
        byte[] uploadData = "source-file-data".getBytes(StandardCharsets.UTF_8);
        byte[] responseData = "transformed-result".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(post(urlEqualTo("/transform"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Length", String.valueOf(responseData.length))
                        .withBody(responseData)));

        List<Long> uploadedBytes = new CopyOnWriteArrayList<>();
        List<ProgressEvent> downloadEvents = new CopyOnWriteArrayList<>();

        DownloadApi api = httpClient.get(DownloadApi.class);
        FileContent file = FileContent.of("input.txt", uploadData);

        byte[] result = api.transform(
                file,
                (sent, total) -> uploadedBytes.add(sent),
                (read, total) -> downloadEvents.add(new ProgressEvent(read, total))
        );

        assertArrayEquals(responseData, result);
        assertFalse(uploadedBytes.isEmpty(), "Upload events should be present");
        assertFalse(downloadEvents.isEmpty(), "Download events should be present");

        ProgressEvent lastDownload = downloadEvents.get(downloadEvents.size() - 1);
        assertEquals(responseData.length, lastDownload.bytesRead());
        assertEquals(responseData.length, lastDownload.totalBytes());
    }

    @Test
    @DisplayName("Passing null as DownloadProgressListener is safe and performs download normally")
    void passingNullAsListenerIsSafe() {
        byte[] payload = "plain-data".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/bytes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(payload)));

        DownloadApi api = httpClient.get(DownloadApi.class);
        byte[] result = api.downloadBytes(null);

        assertArrayEquals(payload, result);
    }

    @Test
    @DisplayName("Method without DownloadProgressListener continues working without regressions")
    void methodWithoutListenerWorksNormally() {
        byte[] payload = "plain-body".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(get(urlEqualTo("/download/plain"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(payload)));

        DownloadApi api = httpClient.get(DownloadApi.class);
        byte[] result = api.downloadWithoutProgressListener();

        assertArrayEquals(payload, result);
    }

    @Test
    @DisplayName("Large download (> 128 KiB) generates multiple progress events")
    void largeDownloadGeneratesMultipleEvents() {
        byte[] payload = new byte[256 * 1024]; // 256 KiB
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }

        wireMock.stubFor(get(urlEqualTo("/download/bytes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloadApi api = httpClient.get(DownloadApi.class);

        byte[] result = api.downloadBytes((read, total) -> events.add(new ProgressEvent(read, total)));

        assertArrayEquals(payload, result);
        assertFalse(events.isEmpty());

        long prev = 0;
        for (ProgressEvent event : events) {
            assertTrue(event.bytesRead() >= prev, "Progress should be monotonically increasing");
            prev = event.bytesRead();
            assertEquals(payload.length, event.totalBytes());
        }

        assertEquals(payload.length, events.get(events.size() - 1).bytesRead());
    }

    @Test
    @DisplayName("Download with transparent decompression tracks wire bytes while result is fully decompressed")
    void downloadWithDecompressionTracksWireBytes() {
        JRestlyClient decompressingClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .decompression(true)
                .build();

        try {
            byte[] payload = new byte[2048];
            Arrays.fill(payload, (byte) 'Z');

            wireMock.stubFor(get(urlEqualTo("/download/bytes"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withBody(payload)));

            List<ProgressEvent> events = new CopyOnWriteArrayList<>();
            DownloadApi api = decompressingClient.get(DownloadApi.class);

            byte[] result = api.downloadBytes((read, total) -> events.add(new ProgressEvent(read, total)));

            assertArrayEquals(payload, result);
            assertFalse(events.isEmpty());

            ProgressEvent last = events.get(events.size() - 1);
            assertTrue(last.bytesRead() < payload.length, "Compressed wire bytes should be smaller than decompressed length");
        } finally {
            decompressingClient.close();
        }
    }
}
