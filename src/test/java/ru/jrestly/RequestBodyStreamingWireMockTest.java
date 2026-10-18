package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.UploadProgressListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for streaming request bodies (ADR-0035): {@code Flow.Publisher},
 * {@code InputStream} and supplier bodies, custom content types and replay across retries
 * and redirects.
 */
@DisplayName("RequestBodyStreamingWireMockTest")
class RequestBodyStreamingWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient client;
    private StreamingClient api;

    @HttpVersion(java.net.http.HttpClient.Version.HTTP_1_1)
    public interface StreamingClient {

        @Post("/upload/publisher")
        String uploadPublisher(@RequestBody Flow.Publisher<ByteBuffer> publisher);

        @Post("/upload/strings")
        String uploadStrings(@RequestBody Flow.Publisher<String> publisher);

        @Post("/upload/ndjson")
        @ContentType("application/x-ndjson")
        String uploadNdjson(@RequestBody Flow.Publisher<String> publisher);

        @Post("/upload/stream")
        String uploadStream(@RequestBody InputStream stream);

        @Post("/upload/supplier-retry")
        @Retry(times = 2, nonIdempotent = true, initialBackoffMs = 10, maxBackoffMs = 50)
        String uploadSupplierRetry(@RequestBody Supplier<InputStream> streamSupplier);

        @Post("/upload/supplier-once")
        String uploadSupplierOnce(@RequestBody Supplier<InputStream> streamSupplier);

        @Post("/upload/supplier-flow-pub")
        String uploadSupplierFlowPublisher(@RequestBody Supplier<Flow.Publisher<ByteBuffer>> publisherSupplier);

        @Post("/upload/supplier-body-pub")
        @Retry(times = 2, nonIdempotent = true, initialBackoffMs = 10, maxBackoffMs = 50)
        String uploadSupplierBodyPublisher(@RequestBody Supplier<HttpRequest.BodyPublisher> publisherSupplier);

        @Post("/upload/redirect-307")
        @FollowRedirects(count = 2)
        String uploadRedirect307(@RequestBody Supplier<InputStream> streamSupplier);

        @Put("/upload/file")
        String uploadFile(@RequestBody java.io.File file);

        @Put("/upload/path")
        String uploadPath(@RequestBody Path path);

        @Post("/upload/body-pub")
        String uploadBodyPublisher(@RequestBody HttpRequest.BodyPublisher bodyPublisher);

        @Post("/upload/progress")
        String uploadWithProgress(@RequestBody Flow.Publisher<ByteBuffer> publisher, UploadProgressListener listener);

        @Post("/upload/async")
        CompletableFuture<String> uploadAsync(@RequestBody Flow.Publisher<ByteBuffer> publisher);

        @Post("/upload/nullable")
        String uploadNullable(@RequestBody Flow.Publisher<ByteBuffer> publisher);
    }

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        client = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .httpVersion(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        api = client.get(StreamingClient.class);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private static Flow.Publisher<ByteBuffer> createByteBufferPublisher(List<byte[]> chunks) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int index = 0;
            private boolean cancelled = false;

            @Override
            public synchronized void request(long n) {
                for (long i = 0; i < n && !cancelled; i++) {
                    if (index < chunks.size()) {
                        subscriber.onNext(ByteBuffer.wrap(chunks.get(index++)));
                    } else {
                        subscriber.onComplete();
                        return;
                    }
                }
            }

            @Override
            public synchronized void cancel() {
                cancelled = true;
            }
        });
    }

    private static Flow.Publisher<String> createStringPublisher(List<String> items) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int index = 0;
            private boolean cancelled = false;

            @Override
            public synchronized void request(long n) {
                for (long i = 0; i < n && !cancelled; i++) {
                    if (index < items.size()) {
                        subscriber.onNext(items.get(index++));
                    } else {
                        subscriber.onComplete();
                        return;
                    }
                }
            }

            @Override
            public synchronized void cancel() {
                cancelled = true;
            }
        });
    }

    @Test
    @DisplayName("Upload Flow.Publisher<ByteBuffer> streams chunks and defaults to application/octet-stream")
    void testUploadByteBufferPublisher() {
        wireMock.stubFor(post(urlEqualTo("/upload/publisher"))
                .withHeader("Content-Type", containing("application/octet-stream"))
                .withRequestBody(equalTo("chunk-1chunk-2chunk-3"))
                .willReturn(ok("OK_BUFFER")));

        List<byte[]> chunks = List.of(
                "chunk-1".getBytes(StandardCharsets.UTF_8),
                "chunk-2".getBytes(StandardCharsets.UTF_8),
                "chunk-3".getBytes(StandardCharsets.UTF_8)
        );

        String result = api.uploadPublisher(createByteBufferPublisher(chunks));
        assertEquals("OK_BUFFER", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/publisher")));
    }

    @Test
    @DisplayName("Upload Flow.Publisher<String> automatically adapts to UTF-8 ByteBuffers")
    void testUploadStringPublisher() {
        wireMock.stubFor(post(urlEqualTo("/upload/strings"))
                .withHeader("Content-Type", containing("application/octet-stream"))
                .withRequestBody(equalTo("hello world!"))
                .willReturn(ok("OK_STRINGS")));

        List<String> items = List.of("hello ", "world", "!");
        String result = api.uploadStrings(createStringPublisher(items));
        assertEquals("OK_STRINGS", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/strings")));
    }

    @Test
    @DisplayName("Upload with explicit @ContentType preserves specified media type")
    void testUploadExplicitContentType() {
        wireMock.stubFor(post(urlEqualTo("/upload/ndjson"))
                .withHeader("Content-Type", containing("application/x-ndjson"))
                .withRequestBody(equalTo("{\"id\":1}\n{\"id\":2}\n"))
                .willReturn(ok("OK_NDJSON")));

        List<String> lines = List.of("{\"id\":1}\n", "{\"id\":2}\n");
        String result = api.uploadNdjson(createStringPublisher(lines));
        assertEquals("OK_NDJSON", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/ndjson")));
    }

    @Test
    @DisplayName("Upload InputStream streams bytes correctly")
    void testUploadInputStream() {
        wireMock.stubFor(post(urlEqualTo("/upload/stream"))
                .withHeader("Content-Type", containing("application/octet-stream"))
                .withRequestBody(equalTo("stream-payload-content"))
                .willReturn(ok("OK_STREAM")));

        InputStream stream = new ByteArrayInputStream("stream-payload-content".getBytes(StandardCharsets.UTF_8));
        String result = api.uploadStream(stream);
        assertEquals("OK_STREAM", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/stream")));
    }

    @Test
    @DisplayName("Upload Supplier<InputStream> succeeds across retry attempts by supplying fresh streams")
    void testUploadSupplierAcrossRetry() {
        wireMock.stubFor(post(urlEqualTo("/upload/supplier-retry"))
                .inScenario("SupplierRetry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("Retried"));

        wireMock.stubFor(post(urlEqualTo("/upload/supplier-retry"))
                .inScenario("SupplierRetry")
                .whenScenarioStateIs("Retried")
                .withRequestBody(equalTo("reusable-stream-payload"))
                .willReturn(ok("OK_SUPPLIER")));

        AtomicInteger supplyCount = new AtomicInteger();
        Supplier<InputStream> supplier = () -> {
            supplyCount.incrementAndGet();
            return new ByteArrayInputStream("reusable-stream-payload".getBytes(StandardCharsets.UTF_8));
        };

        String result = api.uploadSupplierRetry(supplier);
        assertEquals("OK_SUPPLIER", result);
        assertEquals(2, supplyCount.get(), "Supplier should have been called twice (1 initial + 1 retry)");
        wireMock.verify(2, postRequestedFor(urlEqualTo("/upload/supplier-retry")));
    }

    @Test
    @DisplayName("Upload Supplier<HttpRequest.BodyPublisher> is re-supplied across retry attempts (ADR-0035 §1)")
    void testUploadSupplierBodyPublisherAcrossRetry() {
        wireMock.stubFor(post(urlEqualTo("/upload/supplier-body-pub"))
                .inScenario("SupplierBodyPublisherRetry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("Retried"));

        wireMock.stubFor(post(urlEqualTo("/upload/supplier-body-pub"))
                .inScenario("SupplierBodyPublisherRetry")
                .whenScenarioStateIs("Retried")
                .withRequestBody(equalTo("reusable-body-publisher"))
                .willReturn(ok("OK_SUPPLIER_BODY_PUB")));

        AtomicInteger supplyCount = new AtomicInteger();
        Supplier<HttpRequest.BodyPublisher> supplier = () -> {
            supplyCount.incrementAndGet();
            return HttpRequest.BodyPublishers.ofString("reusable-body-publisher");
        };

        String result = api.uploadSupplierBodyPublisher(supplier);
        assertEquals("OK_SUPPLIER_BODY_PUB", result);
        assertEquals(2, supplyCount.get(), "Publisher supplier should be invoked once per transmission");
        wireMock.verify(2, postRequestedFor(urlEqualTo("/upload/supplier-body-pub")));
    }

    @Test
    @DisplayName("Upload Supplier<Flow.Publisher<ByteBuffer>> supplies a fresh publisher per transmission (ADR-0035 §1)")
    void testUploadSupplierFlowPublisher() {
        wireMock.stubFor(post(urlEqualTo("/upload/supplier-flow-pub"))
                .withRequestBody(equalTo("flow-pub-payload"))
                .willReturn(ok("OK_SUPPLIER_FLOW")));

        AtomicInteger supplyCount = new AtomicInteger();
        Supplier<Flow.Publisher<ByteBuffer>> supplier = () -> {
            supplyCount.incrementAndGet();
            return createByteBufferPublisher(List.of(
                    "flow-".getBytes(StandardCharsets.UTF_8),
                    "pub-".getBytes(StandardCharsets.UTF_8),
                    "payload".getBytes(StandardCharsets.UTF_8)));
        };

        String result = api.uploadSupplierFlowPublisher(supplier);
        assertEquals("OK_SUPPLIER_FLOW", result);
        assertEquals(1, supplyCount.get());
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/supplier-flow-pub")));
    }

    @Test
    @DisplayName("307 redirect replays a streaming Supplier body on a fresh connection (ADR-0035 §3)")
    void testRedirect307ReplaysStreamingSupplierBody() {
        wireMock.stubFor(post(urlEqualTo("/upload/redirect-307"))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", "/upload/redirect-307-target")));

        wireMock.stubFor(post(urlEqualTo("/upload/redirect-307-target"))
                .withRequestBody(equalTo("redirect-stream-payload"))
                .willReturn(ok("OK_REDIRECT_STREAM")));

        AtomicInteger supplyCount = new AtomicInteger();
        Supplier<InputStream> supplier = () -> {
            supplyCount.incrementAndGet();
            return new ByteArrayInputStream("redirect-stream-payload".getBytes(StandardCharsets.UTF_8));
        };

        String result = api.uploadRedirect307(supplier);
        assertEquals("OK_REDIRECT_STREAM", result);
        assertEquals(2, supplyCount.get(), "Supplier should be invoked once per redirect hop");
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/redirect-307-target"))
                .withRequestBody(equalTo("redirect-stream-payload")));
    }

    @Test
    @DisplayName("Upload File streams directly with known Content-Length and defaults to application/octet-stream (ADR-0035 §1/§2)")
    void testUploadFileDefaultsToOctetStream() throws IOException {
        Path tempFile = Files.createTempFile("jrestly-upload-file-test-", ".bin");
        try {
            byte[] fileBytes = "file-body-content-98765".getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile, fileBytes);

            wireMock.stubFor(put(urlEqualTo("/upload/file"))
                    .withHeader("Content-Type", containing("application/octet-stream"))
                    .withHeader("Content-Length", equalTo(String.valueOf(fileBytes.length)))
                    .withRequestBody(equalTo("file-body-content-98765"))
                    .willReturn(ok("OK_FILE")));

            String result = api.uploadFile(tempFile.toFile());
            assertEquals("OK_FILE", result);
            wireMock.verify(putRequestedFor(urlEqualTo("/upload/file")));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Metrics listener does not materialize the Supplier body a second time (ADR-0036 Amendment #193)")
    void testUploadSupplierWithMetricsListener() {
        wireMock.stubFor(post(urlEqualTo("/upload/supplier-once"))
                .withRequestBody(equalTo("supplier-once-payload"))
                .willReturn(aResponse().withStatus(200).withBody("OK_SUPPLIER_ONCE")));

        // a metrics listener activates the byte-accounting path of the call
        JRestlyClient metricsClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .httpVersion(java.net.http.HttpClient.Version.HTTP_1_1)
                .metricsListener(metrics -> { })
                .build();
        try {
            StreamingClient metricsApi = metricsClient.get(StreamingClient.class);
            AtomicInteger supplyCount = new AtomicInteger();
            Supplier<InputStream> supplier = () -> {
                supplyCount.incrementAndGet();
                return new ByteArrayInputStream("supplier-once-payload".getBytes(StandardCharsets.UTF_8));
            };

            String result = metricsApi.uploadSupplierOnce(supplier);
            assertEquals("OK_SUPPLIER_ONCE", result);
            assertEquals(1, supplyCount.get(), "Supplier should be invoked once per transmission — the metrics probe must not add a second call");
            wireMock.verify(postRequestedFor(urlEqualTo("/upload/supplier-once")));
        } finally {
            metricsClient.close();
        }
    }

    @Test
    @DisplayName("Upload Path streams file directly with known Content-Length")
    void testUploadPathFile() throws IOException {
        Path tempFile = Files.createTempFile("jrestly-upload-test-", ".txt");
        try {
            byte[] fileBytes = "content-of-temp-file-12345".getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile, fileBytes);

            wireMock.stubFor(put(urlEqualTo("/upload/path"))
                    .withHeader("Content-Length", equalTo(String.valueOf(fileBytes.length)))
                    .withRequestBody(equalTo("content-of-temp-file-12345"))
                    .willReturn(ok("OK_PATH")));

            String result = api.uploadPath(tempFile);
            assertEquals("OK_PATH", result);
            wireMock.verify(putRequestedFor(urlEqualTo("/upload/path")));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Upload HttpRequest.BodyPublisher delegates directly to standard body publisher")
    void testUploadBodyPublisherDirect() {
        wireMock.stubFor(post(urlEqualTo("/upload/body-pub"))
                .withRequestBody(equalTo("native-publisher-data"))
                .willReturn(ok("OK_BODY_PUB")));

        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString("native-publisher-data");
        String result = api.uploadBodyPublisher(publisher);
        assertEquals("OK_BODY_PUB", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/upload/body-pub")));
    }

    @Test
    @DisplayName("Upload Flow.Publisher with UploadProgressListener tracks chunk progress")
    void testUploadProgressTracking() {
        wireMock.stubFor(post(urlEqualTo("/upload/progress"))
                .withRequestBody(equalTo("part1part2part3"))
                .willReturn(ok("OK_PROGRESS")));

        List<byte[]> chunks = List.of(
                "part1".getBytes(StandardCharsets.UTF_8),
                "part2".getBytes(StandardCharsets.UTF_8),
                "part3".getBytes(StandardCharsets.UTF_8)
        );

        List<Long> progressEvents = new CopyOnWriteArrayList<>();
        String result = api.uploadWithProgress(createByteBufferPublisher(chunks), (bytesSent, totalBytes) -> {
            progressEvents.add(bytesSent);
        });

        assertEquals("OK_PROGRESS", result);
        assertFalse(progressEvents.isEmpty(), "Expected progress updates during streaming");
        long finalBytes = progressEvents.get(progressEvents.size() - 1);
        assertEquals(15, finalBytes, "Total bytes streamed should equal sum of all chunk lengths");
    }

    @Test
    @DisplayName("Async upload with CompletableFuture works seamlessly with reactive streaming")
    void testAsyncUpload() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/upload/async"))
                .withRequestBody(equalTo("async-stream-data"))
                .willReturn(ok("OK_ASYNC")));

        List<byte[]> chunks = List.of(
                "async-".getBytes(StandardCharsets.UTF_8),
                "stream-".getBytes(StandardCharsets.UTF_8),
                "data".getBytes(StandardCharsets.UTF_8)
        );

        CompletableFuture<String> future = api.uploadAsync(createByteBufferPublisher(chunks));
        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("OK_ASYNC", result);
    }

    @Test
    @DisplayName("Null Flow.Publisher argument results in empty body")
    void testNullPublisher() {
        wireMock.stubFor(post(urlEqualTo("/upload/nullable"))
                .willReturn(ok("OK_NULL")));

        String result = api.uploadNullable(null);
        assertEquals("OK_NULL", result);
    }

    @Test
    @DisplayName("Upload Flow.Publisher propagates upstream onError to request subscriber")
    void testUploadPublisherPropagatesError() {
        wireMock.stubFor(post(urlEqualTo("/upload/strings"))
                .willReturn(ok("OK_STRINGS")));

        Flow.Publisher<String> failingPublisher = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onError(new IOException("simulated stream failure"));
                }

                @Override
                public void cancel() {
                }
            });
        };

        assertThrows(RuntimeException.class, () -> api.uploadStrings(failingPublisher));
    }
}
