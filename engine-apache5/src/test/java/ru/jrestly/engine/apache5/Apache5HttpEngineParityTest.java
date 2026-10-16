package ru.jrestly.engine.apache5;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.MultipartPart;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0058 phase 2 parity gate for the Apache HttpClient 5 transport: the engine is exercised
 * directly (no declarative layer) against WireMock — headers, bodies, streaming,
 * progress listeners, timeouts, trailers-free buffered mode, and close semantics
 * must match the contract of the zero-dependency engine.
 *
 * <p>Stub framing notes: WireMock 3.x frames bodies as {@code Transfer-Encoding: chunked}
 * unless the stub declares {@code Content-Length} explicitly, so the declared-length
 * progress cases set it (as the core's {@code DownloadProgressWireMockTest} does). The
 * transport stays passive on content coding (ADR-0021): it never injects
 * {@code Accept-Encoding} and never transparently decompresses — both pinned below.
 */
class Apache5HttpEngineParityTest {

    private WireMockServer wireMock;
    private Apache5HttpEngine engine;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
        baseUrl = "http://localhost:" + wireMock.port();
        engine = new Apache5HttpEngine();
    }

    @AfterEach
    void tearDown() throws Exception {
        wireMock.stop();
        engine.close();
    }

    private HttpEngineOptions options() {
        // HTTP/1.1 pinning exercises the derived-client path
        return HttpEngineOptions.builder().timeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
    }

    // named getWireRequest (not get): a bare get(path) member would shadow the statically
    // imported WireMock.get(UrlPattern) in the stub/verify expressions below
    private WireRequest getWireRequest(String path) {
        return new WireRequest(HttpMethod.GET, URI.create(baseUrl + path), List.of(), null, null);
    }

    @Test
    @DisplayName("Buffered response carries status, multi-value headers and exact body")
    void bufferedResponseCarriesStatusHeadersAndBody() throws Exception {
        stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("X-One", "first")
                        .withHeader("X-One", "second")
                        .withHeader("X-Two", "only")
                        .withBody("hello parity")));

        WireResponse response = engine.send(getWireRequest("/api/data"), options());

        assertEquals(201, response.statusCode());
        assertTrue(response.stream() == null);
        assertNotNull(response.body());
        assertEquals("hello parity", new String(response.body(), StandardCharsets.UTF_8));
        List<Header> headers = response.headers();
        assertEquals(2, headers.stream().filter(h -> h.name().equalsIgnoreCase("X-One")).count());
        assertTrue(headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("X-One") && h.value().equals("first")));
        assertTrue(headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("X-One") && h.value().equals("second")));
        assertTrue(headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("X-Two") && h.value().equals("only")));
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
    }

    @Test
    @DisplayName("streamResponse=true delivers a live stream, not materialized bytes")
    void streamingResponseDeliversLiveStream() throws Exception {
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse().withStatus(200).withBody(payload)));

        HttpEngineOptions streamOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .streamResponse(true)
                .build();
        WireResponse response = engine.send(getWireRequest("/api/stream"), streamOptions);

        assertNull(response.body());
        assertNotNull(response.stream());
        byte[] read = response.stream().readAllBytes();
        assertEquals(payload.length, read.length);
        for (int i = 0; i < read.length; i++) {
            if (read[i] != payload[i]) {
                throw new AssertionError("stream content mismatch at offset " + i);
            }
        }
        response.stream().close();
    }

    @Test
    @DisplayName("Async streaming response keeps the body readable after completion (prototype bug)")
    void streamingAsyncResponseStreamReadableAfterCompletion() throws Exception {
        stubFor(get(urlEqualTo("/api/async-stream"))
                .willReturn(aResponse().withStatus(200).withBody("async-stream-body")));

        HttpEngineOptions streamOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .streamResponse(true)
                .build();
        CompletableFuture<WireResponse> future = engine.sendAsync(getWireRequest("/api/async-stream"), streamOptions);
        WireResponse response = future.get(10, TimeUnit.SECONDS);

        assertNotNull(response.stream());
        assertEquals("async-stream-body", new String(response.stream().readAllBytes(), StandardCharsets.UTF_8));
        response.stream().close();
    }

    @Test
    @DisplayName("Download progress reports monotonic events ending at (total, total) for Content-Length bodies")
    void downloadProgressReportsChunksAndFinalTotal() throws Exception {
        byte[] payload = new byte[100_000];
        stubFor(get(urlEqualTo("/api/download"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .downloadProgressListener((bytesRead, total) -> events.add(new long[]{bytesRead, total}))
                .build();
        WireResponse response = engine.send(getWireRequest("/api/download"), progressOptions);

        assertEquals(payload.length, response.body().length);
        assertFalse(events.isEmpty());
        long previous = -1;
        for (long[] event : events) {
            assertEquals((long) payload.length, event[1], "total must be the declared Content-Length");
            assertTrue(event[0] >= previous, "progress must be monotonic");
            previous = event[0];
        }
        assertEquals((long) payload.length, events.get(events.size() - 1)[0], "final event must reach the total");
    }

    @Test
    @DisplayName("Chunked bodies report unknown total (-1) and the actual bytes read")
    void downloadProgressChunkedUnknownTotal() throws Exception {
        byte[] payload = new byte[50_000];
        stubFor(get(urlEqualTo("/api/download-chunked"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(payload)
                        .withChunkedDribbleDelay(5, 50)));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .downloadProgressListener((bytesRead, total) -> events.add(new long[]{bytesRead, total}))
                .build();
        WireResponse response = engine.send(getWireRequest("/api/download-chunked"), progressOptions);

        assertEquals(payload.length, response.body().length);
        assertFalse(events.isEmpty());
        for (long[] event : events) {
            assertEquals(-1L, event[1], "chunked bodies have no Content-Length");
        }
        assertEquals((long) payload.length, events.get(events.size() - 1)[0]);
    }

    @Test
    @DisplayName("Zero-length download fires exactly one (0, 0) event (ADR-0033)")
    void downloadProgressZeroLengthBodyFiresSingleEvent() throws Exception {
        stubFor(get(urlEqualTo("/api/empty"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Length", "0")
                        .withBody("")));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .downloadProgressListener((bytesRead, total) -> events.add(new long[]{bytesRead, total}))
                .build();
        WireResponse response = engine.send(getWireRequest("/api/empty"), progressOptions);

        assertFalse(response.hasBody());
        assertEquals(1, events.size());
        assertEquals(0L, events.get(0)[0]);
        assertEquals(0L, events.get(0)[1]);
    }

    @Test
    @DisplayName("Streaming download progress ends at (total, total) too")
    void streamingDownloadProgressReportsTotal() throws Exception {
        byte[] payload = new byte[30_000];
        stubFor(get(urlEqualTo("/api/stream-progress"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Length", String.valueOf(payload.length))
                        .withBody(payload)));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .streamResponse(true)
                .downloadProgressListener((bytesRead, total) -> events.add(new long[]{bytesRead, total}))
                .build();
        WireResponse response = engine.send(getWireRequest("/api/stream-progress"), progressOptions);
        assertEquals(payload.length, response.stream().readAllBytes().length);

        assertFalse(events.isEmpty());
        assertEquals((long) payload.length, events.get(events.size() - 1)[0]);
        assertEquals((long) payload.length, events.get(events.size() - 1)[1]);
    }

    @Test
    @DisplayName("Upload progress for a raw byte[] body ends at (total, total) and the server gets the exact body")
    void uploadProgressForByteArrayBody() throws Exception {
        byte[] payload = new byte[100_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 199);
        }
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .uploadProgressListener((bytesSent, total) -> events.add(new long[]{bytesSent, total}))
                .build();
        WireRequest request = new WireRequest(HttpMethod.POST, URI.create(baseUrl + "/api/upload"),
                List.of(), "application/octet-stream", payload);

        WireResponse response = engine.send(request, progressOptions);
        assertEquals(200, response.statusCode());

        assertFalse(events.isEmpty());
        long previous = -1;
        for (long[] event : events) {
            assertEquals((long) payload.length, event[1]);
            assertTrue(event[0] >= previous, "upload progress must be monotonic");
            previous = event[0];
        }
        assertEquals((long) payload.length, events.get(events.size() - 1)[0]);

        List<LoggedRequest> served = findAll(postRequestedFor(urlEqualTo("/api/upload")));
        assertEquals(1, served.size());
        assertArrayEquals(payload, served.get(0).getBody());
    }

    @Test
    @DisplayName("Zero-length upload fires exactly one (0, 0) event (ADR-0030)")
    void uploadProgressZeroLengthFiresOnce() throws Exception {
        stubFor(post(urlEqualTo("/api/upload-empty"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        List<long[]> events = new ArrayList<>();
        HttpEngineOptions progressOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .uploadProgressListener((bytesSent, total) -> events.add(new long[]{bytesSent, total}))
                .build();
        WireRequest request = new WireRequest(HttpMethod.POST, URI.create(baseUrl + "/api/upload-empty"),
                List.of(), "application/octet-stream", new byte[0]);

        engine.send(request, progressOptions);

        assertEquals(1, events.size());
        assertEquals(0L, events.get(0)[0]);
        assertEquals(0L, events.get(0)[1]);
    }

    @Test
    @DisplayName("Streaming request bodies (JDK BodyPublisher) are drained to the wire intact")
    void streamingBodyPublisherIsDrained() throws Exception {
        byte[] payload = new byte[50_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7 % 256);
        }
        stubFor(post(urlEqualTo("/api/streaming-body"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        WireRequest request = new WireRequest(HttpMethod.POST, URI.create(baseUrl + "/api/streaming-body"),
                List.of(), "application/octet-stream", null, null, null,
                () -> HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)));

        WireResponse response = engine.send(request, options());
        assertEquals(200, response.statusCode());

        List<LoggedRequest> served = findAll(postRequestedFor(urlEqualTo("/api/streaming-body")));
        assertEquals(1, served.size());
        assertArrayEquals(payload, served.get(0).getBody());
    }

    @Test
    @DisplayName("Multipart bodies are rendered per RFC 7578 with the declared boundary")
    void multipartBodyIsRendered() throws Exception {
        String boundary = "jrestly-test-boundary";
        stubFor(post(urlEqualTo("/api/multipart"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        byte[] fileBytes = "file-contents-12345".getBytes(StandardCharsets.UTF_8);
        List<MultipartPart> parts = List.of(
                MultipartPart.text("field", "value"),
                MultipartPart.part("doc", "a.txt", "text/plain", fileBytes));
        WireRequest request = new WireRequest(HttpMethod.POST, URI.create(baseUrl + "/api/multipart"),
                List.of(), "multipart/form-data; boundary=" + boundary, null, parts, boundary, null);

        WireResponse response = engine.send(request, options());
        assertEquals(200, response.statusCode());

        List<LoggedRequest> served = findAll(postRequestedFor(urlEqualTo("/api/multipart")));
        assertEquals(1, served.size());
        String body = served.get(0).getBodyAsString();
        assertTrue(body.contains("--" + boundary + "\r\n"), "opening boundary missing");
        assertTrue(body.contains("Content-Disposition: form-data; name=\"field\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nvalue\r\n"), "text part missing");
        assertTrue(body.contains("filename=\"a.txt\""), "file name missing");
        assertTrue(body.contains(new String(fileBytes, StandardCharsets.UTF_8)), "file content missing");
        assertTrue(body.contains("--" + boundary + "--"), "closing boundary missing");

        long contentTypeLines = served.get(0).getHeaders().all().stream()
                .filter(h -> h.keyEquals("Content-Type"))
                .mapToLong(h -> h.values().size())
                .sum();
        assertEquals(1L, contentTypeLines, "exactly one Content-Type header on the wire");
        assertEquals("multipart/form-data; boundary=" + boundary,
                served.get(0).header("Content-Type").firstValue());
    }

    @Test
    @DisplayName("The transport is passive: no Accept-Encoding is injected against the wire (ADR-0058 parity)")
    void noAcceptEncodingInjectedByTransport() throws Exception {
        stubFor(get(urlEqualTo("/api/passive"))
                .willReturn(aResponse().withStatus(200).withBody("plain")));

        engine.send(getWireRequest("/api/passive"), options());

        List<LoggedRequest> served = findAll(getRequestedFor(urlEqualTo("/api/passive")));
        assertEquals(1, served.size());
        assertFalse(served.get(0).containsHeader("Accept-Encoding"),
                "content-coding negotiation is the declarative layer's job (ADR-0021), "
                        + "and HttpClient 5's transparent decompression would also hide Content-Length/Content-Encoding");
    }

    @Test
    @DisplayName("An explicit Accept-Encoding passes through and the compressed body reaches the caller as-is (ADR-0021)")
    void explicitAcceptEncodingIsPassedThroughCompressed() throws Exception {
        byte[] payload = new byte[100_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        stubFor(get(urlEqualTo("/api/passive-gzip"))
                .willReturn(aResponse().withStatus(200).withBody(payload)));

        WireRequest request = new WireRequest(HttpMethod.GET, URI.create(baseUrl + "/api/passive-gzip"),
                List.of(new Header("Accept-Encoding", "gzip, deflate")), null, null);
        WireResponse response = engine.send(request, options());

        assertEquals("gzip, deflate",
                findAll(getRequestedFor(urlEqualTo("/api/passive-gzip"))).get(0).header("Accept-Encoding").firstValue());
        assertTrue(response.headers().stream()
                        .anyMatch(h -> h.name().equalsIgnoreCase("Content-Encoding") && h.value().equalsIgnoreCase("gzip")),
                "Content-Encoding must survive to the caller instead of being transparently consumed");
        byte[] decoded;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            decoded = gzip.readAllBytes();
        }
        assertArrayEquals(payload, decoded);
    }

    @Test
    @DisplayName("callTimeout bounds the whole exchange (connect + read)")
    void callTimeoutFailsFastOnSlowServer() throws Exception {
        stubFor(get(urlEqualTo("/api/slow"))
                .willReturn(aResponse().withStatus(200).withBody("slow").withFixedDelay(1500)));

        HttpEngineOptions timeoutOptions = HttpEngineOptions.builder()
                .timeout(Duration.ofMillis(300))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        long start = System.nanoTime();
        IOException failure = assertThrows(IOException.class,
                () -> engine.send(getWireRequest("/api/slow"), timeoutOptions));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 1200, "timeout fired after the server finished: " + elapsedMs + " ms");
        assertNotNull(failure);
    }

    @Test
    @DisplayName("sendAsync completes with the buffered body")
    void sendAsyncCompletesBufferedBody() throws Exception {
        stubFor(get(urlEqualTo("/api/async"))
                .willReturn(aResponse().withStatus(200).withBody("async-body")));

        CompletableFuture<WireResponse> future = engine.sendAsync(getWireRequest("/api/async"), options());
        WireResponse response = future.get(10, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertEquals("async-body", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("An HTTP/2-pinned client falls back to HTTP/1.1 against an HTTP/1.1-only server (best-effort pinning)")
    void http2PinningFallsBackToHttp11() throws Exception {
        stubFor(get(urlEqualTo("/api/negotiate"))
                .willReturn(aResponse().withStatus(200).withBody("negotiated")));

        HttpEngineOptions h2Options = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .build();
        WireResponse response = engine.send(getWireRequest("/api/negotiate"), h2Options);

        assertEquals(200, response.statusCode());
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
    }

    @Test
    @DisplayName("close() does not release a client the engine does not own")
    void closeDoesNotCloseExternalClient() throws Exception {
        org.apache.hc.client5.http.impl.classic.CloseableHttpClient shared =
                org.apache.hc.client5.http.impl.classic.HttpClients.custom().build();
        try {
            Apache5HttpEngine external = new Apache5HttpEngine(shared);
            external.close();

            stubFor(get(urlEqualTo("/api/external"))
                    .willReturn(aResponse().withStatus(200).withBody("still alive")));
            WireResponse response = external.send(getWireRequest("/api/external"), options());
            assertEquals("still alive", new String(response.body(), StandardCharsets.UTF_8));
        } finally {
            shared.close();
        }
    }

    @Test
    @DisplayName("close() releases an owned client: subsequent sends fail and close is idempotent")
    void closeReleasesOwnedClient() throws Exception {
        Apache5HttpEngine owned = new Apache5HttpEngine();
        owned.close();
        owned.close();

        assertThrows(IllegalStateException.class, () -> owned.send(getWireRequest("/api/after-close"), options()));
    }

    @Test
    @DisplayName("contentType replaces a duplicated Content-Type carried in the header list")
    void contentTypeReplacement() throws Exception {
        stubFor(post(urlEqualTo("/api/content-type"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        List<Header> headers = new ArrayList<>();
        headers.add(new Header("Content-Type", "application/json; charset=utf-8"));
        headers.add(new Header("X-Api", "1"));
        WireRequest request = new WireRequest(HttpMethod.POST, URI.create(baseUrl + "/api/content-type"),
                headers, "application/json", new byte[]{'x'});

        engine.send(request, options());

        verify(postRequestedFor(urlEqualTo("/api/content-type")).withHeader("Content-Type", equalTo("application/json")));
        List<LoggedRequest> served = findAll(postRequestedFor(urlEqualTo("/api/content-type")));
        long count = served.get(0).getHeaders().all().stream()
                .filter(h -> h.keyEquals("Content-Type"))
                .mapToLong(h -> h.values().size())
                .sum();
        assertEquals(1L, count, "exactly one Content-Type header on the wire");
    }

    @Test
    @DisplayName("Streaming response populates trailersSupplier once body is read to completion")
    void streamingResponsePopulatesTrailersSupplier() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            Thread.ofVirtual().start(() -> {
                try (Socket socket = serverSocket.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    byte[] buf = new byte[1024];
                    in.read(buf);
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Trailer: Server-Timing\r\n"
                            + "Connection: close\r\n\r\n"
                            + "5\r\nhello\r\n"
                            + "0\r\n"
                            + "Server-Timing: app;dur=30.0\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException ignored) {}
            });

            WireRequest request = new WireRequest(HttpMethod.GET, URI.create("http://127.0.0.1:" + port + "/stream"), List.of(), null, null);
            HttpEngineOptions streamOptions = new HttpEngineOptions(null, null, true, null, null);
            WireResponse response = engine.send(request, streamOptions);

            assertNotNull(response.stream());
            assertTrue(response.trailersSupplier().get().isEmpty());

            byte[] bytes = response.stream().readAllBytes();
            assertEquals("hello", new String(bytes, StandardCharsets.UTF_8));

            List<Header> trailers = response.trailersSupplier().get();
            assertEquals(1, trailers.size());
            assertEquals("Server-Timing", trailers.get(0).name());
            assertEquals("app;dur=30.0", trailers.get(0).value());
        }
    }

    @Test
    @DisplayName("Closing streaming InputStream unblocks a blocked reader thread with IOException")
    void closingStreamingInputStreamUnblocksBlockedReaderThread() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            Thread.ofVirtual().start(() -> {
                try (Socket socket = serverSocket.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    byte[] buf = new byte[1024];
                    in.read(buf);
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "Connection: close\r\n\r\n"
                            + "data: hello\n\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(60_000);
                } catch (Exception ignored) {}
            });

            WireRequest request = new WireRequest(HttpMethod.GET, URI.create("http://127.0.0.1:" + port + "/stream"), List.of(), null, null);
            HttpEngineOptions streamOptions = new HttpEngineOptions(null, null, true, null, null);
            WireResponse response = engine.send(request, streamOptions);
            InputStream stream = response.stream();
            assertNotNull(stream);

            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("data: hello", reader.readLine());
            assertEquals("", reader.readLine());

            java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch readerBlocked = new java.util.concurrent.CountDownLatch(1);
            Thread readThread = Thread.ofVirtual().start(() -> {
                try {
                    readerBlocked.countDown();
                    reader.readLine();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });

            readerBlocked.await();
            Thread.sleep(200);

            stream.close();

            boolean finished = readThread.join(Duration.ofSeconds(2));
            assertTrue(finished, "reader thread must unblock when stream is closed");
            assertNotNull(thrown.get(), "reader must catch an IOException");
            assertTrue(thrown.get() instanceof IOException, "exception must be IOException, got: " + thrown.get());
        }
    }
}
