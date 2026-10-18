package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.Retry;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.UploadProgressListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Upload progress end-to-end (ADR-0030): multipart files (in-memory and disk
 * {@code Path}), multiple parts, raw bytes and serialized JSON DTOs all drive an
 * {@code UploadProgressListener}; unknown-length streams report {@code total = -1}
 * and async is non-blocking. WireMock e2e.
 */
@DisplayName("UploadProgressWireMockTest")
class UploadProgressWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient httpClient;

    public record UploadResult(String status, int size) {}
    public record ItemDto(long id, String name) {}

    public record ProgressEvent(long bytesSent, long totalBytes) {}

    public interface UploadApi {

        @Post(value = "/files/single",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadSingle(@MultipartFormFile(partName = "file") FileContent file,
                            UploadProgressListener progress);

        @HttpVersion(HttpClient.Version.HTTP_1_1)
        @Post(value = "/files/stream",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadStreamChunked(@MultipartFormFile(partName = "file") FileContent file,
                                   UploadProgressListener progress);

        @Post(value = "/files/disk",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadDiskFile(@MultipartFormFile(partName = "file") Path file,
                              UploadProgressListener progress);

        @Post(value = "/files/multiple",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadMultiple(@MultipartFormFile(partName = "files") List<FileContent> files,
                              UploadProgressListener progress);

        @Post("/raw/bytes")
        String uploadRaw(@RequestBody byte[] body,
                         UploadProgressListener progress);

        @Post("/json/dto")
        String uploadJson(@RequestBody ItemDto item,
                          UploadProgressListener progress);

        @Post(value = "/files/async",  requestType = RequestType.MULTIPART_FORM_DATA)
        CompletableFuture<String> uploadAsync(@MultipartFormFile(partName = "file") FileContent file,
                                              UploadProgressListener progress);

        @Retry(times = 2, nonIdempotent = true, initialBackoffMs = 10, maxBackoffMs = 50)
        @Post(value = "/files/retryable",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithRetry(@MultipartFormFile(partName = "file") FileContent file,
                               UploadProgressListener progress);

        @Post(value = "/files/plain",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithoutProgressListener(@MultipartFormFile(partName = "file") FileContent file);
    }

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        httpClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
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
    @DisplayName("Single multipart file upload tracks progress to 100% completion")
    void singleMultipartUploadTracksProgress() {
        wireMock.stubFor(post(urlEqualTo("/files/single"))
                .willReturn(ok("uploaded:single")));

        byte[] payload = "Hello, multipart world!".getBytes(StandardCharsets.UTF_8);
        FileContent file = FileContent.of("hello.txt", payload);

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadSingle(file, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:single", result);
        assertFalse(events.isEmpty(), "Expected at least one progress event");

        ProgressEvent last = events.get(events.size() - 1);
        assertTrue(last.totalBytes() > payload.length, "Total bytes must include multipart framing");
        assertEquals(last.totalBytes(), last.bytesSent(), "Last event must report 100% bytesSent == totalBytes");

        // Monotonic increase
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).bytesSent() >= events.get(i - 1).bytesSent());
            assertEquals(events.get(0).totalBytes(), events.get(i).totalBytes());
        }
    }

    @Test
    @DisplayName("Disk Path file upload tracks progress")
    void diskPathUploadTracksProgress(@TempDir Path tempDir) throws IOException {
        wireMock.stubFor(post(urlEqualTo("/files/disk"))
                .willReturn(ok("uploaded:disk")));

        Path testFile = tempDir.resolve("sample.bin");
        byte[] data = new byte[16384];
        Arrays.fill(data, (byte) 0x42);
        Files.write(testFile, data);

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadDiskFile(testFile, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:disk", result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertTrue(last.totalBytes() >= data.length);
        assertEquals(last.totalBytes(), last.bytesSent());
    }

    @Test
    @DisplayName("Multiple files multipart upload tracks aggregated request size")
    void multipleFilesUploadTracksProgress() {
        wireMock.stubFor(post(urlEqualTo("/files/multiple"))
                .willReturn(ok("uploaded:multiple")));

        List<FileContent> files = List.of(
                FileContent.of("part1.txt", "Part 1 content".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("part2.txt", "Part 2 content is longer".getBytes(StandardCharsets.UTF_8))
        );

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadMultiple(files, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:multiple", result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(last.totalBytes(), last.bytesSent());
        assertTrue(last.totalBytes() > 50);
    }

    @Test
    @DisplayName("Stream file content of unknown length reports totalBytes = -1")
    void streamContentOfUnknownLengthReportsMinusOne() {
        wireMock.stubFor(post(urlEqualTo("/files/stream"))
                .willReturn(ok("uploaded:stream")));

        byte[] payload = "Chunked streaming file body".getBytes(StandardCharsets.UTF_8);
        FileContent streamFile = FileContent.of("stream.txt", () -> new ByteArrayInputStream(payload));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadStreamChunked(streamFile, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:stream", result);
        assertFalse(events.isEmpty());

        for (ProgressEvent event : events) {
            assertEquals(-1L, event.totalBytes(), "Stream content of unknown length must have totalBytes == -1");
            assertTrue(event.bytesSent() > 0, "bytesSent must be strictly positive");
        }
    }

    @Test
    @DisplayName("Raw bytes upload tracks body length accurately")
    void rawBytesUploadTracksProgress() {
        wireMock.stubFor(post(urlEqualTo("/raw/bytes"))
                .willReturn(ok("uploaded:raw")));

        byte[] rawBytes = "Direct binary payload of 32 bytes!".getBytes(StandardCharsets.UTF_8);
        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadRaw(rawBytes, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:raw", result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(last.totalBytes(), last.bytesSent());
        assertTrue(last.totalBytes() > 0);
    }

    @Test
    @DisplayName("JSON DTO upload tracks serialized body progress")
    void jsonDtoUploadTracksProgress() {
        wireMock.stubFor(post(urlEqualTo("/json/dto"))
                .willReturn(ok("uploaded:json")));

        ItemDto item = new ItemDto(1001L, "Widget Deluxe");
        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadJson(item, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:json", result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertTrue(last.totalBytes() > 0);
        assertEquals(last.totalBytes(), last.bytesSent());
    }

    @Test
    @DisplayName("Asynchronous CompletableFuture upload tracks progress non-blockingly")
    void asyncUploadTracksProgress() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/files/async"))
                .willReturn(ok("uploaded:async")));

        byte[] payload = "Async multipart payload".getBytes(StandardCharsets.UTF_8);
        FileContent file = FileContent.of("async.txt", payload);

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        CompletableFuture<String> future = api.uploadAsync(file, (sent, total) -> events.add(new ProgressEvent(sent, total)));
        String result = future.get(5, TimeUnit.SECONDS);

        assertEquals("uploaded:async", result);
        assertFalse(events.isEmpty());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(last.totalBytes(), last.bytesSent());
    }

    @Test
    @DisplayName("Passing null as UploadProgressListener is safe and performs upload normally")
    void nullProgressListenerIsSafe() {
        wireMock.stubFor(post(urlEqualTo("/files/single"))
                .willReturn(ok("uploaded:null-progress")));

        FileContent file = FileContent.of("test.txt", "some data".getBytes(StandardCharsets.UTF_8));
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadSingle(file, null);

        assertEquals("uploaded:null-progress", result);
    }

    @Test
    @DisplayName("Method without UploadProgressListener continues working without regressions")
    void methodWithoutProgressListenerWorks() {
        wireMock.stubFor(post(urlEqualTo("/files/plain"))
                .willReturn(ok("uploaded:plain")));

        FileContent file = FileContent.of("plain.txt", "data".getBytes(StandardCharsets.UTF_8));
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadWithoutProgressListener(file);

        assertEquals("uploaded:plain", result);
    }

    @Test
    @DisplayName("Retry attempts each start progress tracking afresh")
    void retryAttemptsRestartProgressTracking() {
        // First attempt fails with 503, second succeeds with 200
        wireMock.stubFor(post(urlEqualTo("/files/retryable"))
                .inScenario("RetryUpload")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(serverError().withBody("Service Unavailable"))
                .willSetStateTo("Succeeded"));

        wireMock.stubFor(post(urlEqualTo("/files/retryable"))
                .inScenario("RetryUpload")
                .whenScenarioStateIs("Succeeded")
                .willReturn(ok("uploaded:retry-ok")));

        byte[] payload = "Retryable file data".getBytes(StandardCharsets.UTF_8);
        FileContent file = FileContent.of("retry.txt", payload);

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadWithRetry(file, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:retry-ok", result);
        // Expect at least two attempts recorded
        assertTrue(events.size() >= 2);

        // Find the points where bytesSent == totalBytes
        List<ProgressEvent> completedEvents = events.stream()
                .filter(e -> e.bytesSent() == e.totalBytes())
                .toList();

        assertEquals(2, completedEvents.size(), "Both attempt 1 and attempt 2 should have completed transmission");
    }

    @Test
    @DisplayName("Large multipart payload (> 128 KiB) generates multiple chunk progress events")
    void largeMultipartPayloadGeneratesMultipleProgressEvents(@TempDir Path tempDir) throws IOException {
        wireMock.stubFor(post(urlEqualTo("/files/disk"))
                .willReturn(ok("uploaded:large")));

        Path largeFile = tempDir.resolve("large.bin");
        byte[] chunk = new byte[16384];
        Arrays.fill(chunk, (byte) 'A');
        // Write 128 KiB
        for (int i = 0; i < 8; i++) {
            Files.write(largeFile, chunk, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadDiskFile(largeFile, (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("uploaded:large", result);
        assertTrue(events.size() >= 2, "Expected multiple chunk events for 128 KiB file, but got: " + events.size());

        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(last.totalBytes(), last.bytesSent());
        assertTrue(last.totalBytes() >= 131072);
    }

    @Test
    @DisplayName("Empty upload body invokes UploadProgressListener exactly once with (0, 0)")
    void emptyUploadBodyInvokesProgressListenerWithZero() {
        wireMock.stubFor(post(urlEqualTo("/raw/bytes"))
                .willReturn(ok("empty-ok")));

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        UploadApi api = httpClient.get(UploadApi.class);

        String result = api.uploadRaw(new byte[0], (sent, total) -> events.add(new ProgressEvent(sent, total)));

        assertEquals("empty-ok", result);
        assertEquals(1, events.size(), "Should invoke progress listener once for empty upload");
        assertEquals(new ProgressEvent(0, 0), events.getFirst());
    }
}
