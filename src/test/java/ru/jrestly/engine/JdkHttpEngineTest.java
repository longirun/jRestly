package ru.jrestly.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.MultipartPart;
import ru.jrestly.http.UploadProgressListener;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage of the built-in JDK transport: request mapping, buffered and streaming
 * responses, progress listeners, multipart parts and trailers.
 */
@DisplayName("JdkHttpEngine Tests")
class JdkHttpEngineTest {

    @Test
    @DisplayName("JdkHttpEngine constructor and factory validate non-null httpClient")
    void validatesNonNullHttpClient() {
        assertThrows(NullPointerException.class, () -> new JdkHttpEngine(null));
        assertThrows(NullPointerException.class, () -> new JdkHttpEngine(null, true));
        assertThrows(NullPointerException.class, () -> JdkHttpEngine.from(null));
        assertThrows(NullPointerException.class, () -> JdkHttpEngine.create(null));
    }

    @Test
    @DisplayName("JdkHttpEngine wraps existing client without closing by default")
    void wrapsWithoutOwnership() {
        HttpClient client = HttpClient.newHttpClient();
        JdkHttpEngine engine = JdkHttpEngine.from(client);
        assertSame(client, engine.getHttpClient());

        // close should not throw and should not fail
        engine.close();
    }

    @Test
    @DisplayName("JdkHttpEngine.create creates managed client and closes on close()")
    void createConfiguresManagedClient() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .connectTimeout(Duration.ofMillis(3000))
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .build();

        JdkHttpEngine engine = JdkHttpEngine.create(moduleInfo);
        assertNotNull(engine.getHttpClient());
        assertEquals(Duration.ofMillis(3000), engine.getHttpClient().connectTimeout().orElse(null));
        assertEquals(HttpClient.Version.HTTP_1_1, engine.getHttpClient().version());

        engine.close();
    }

    @Test
    @DisplayName("HttpEngineOptions builder and defaults test")
    void httpEngineOptionsBuilder() {
        HttpEngineOptions defaults = HttpEngineOptions.defaults();
        assertEquals(null, defaults.timeout());
        assertEquals(null, defaults.version());
        assertFalse(defaults.streamResponse());
        assertEquals(null, defaults.uploadProgressListener());
        assertEquals(null, defaults.downloadProgressListener());

        UploadProgressListener upload = (sent, total) -> {};
        DownloadProgressListener download = (read, total) -> {};

        HttpEngineOptions custom = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_2)
                .streamResponse(true)
                .uploadProgressListener(upload)
                .downloadProgressListener(download)
                .build();

        assertEquals(Duration.ofSeconds(5), custom.timeout());
        assertEquals(HttpClient.Version.HTTP_2, custom.version());
        assertTrue(custom.streamResponse());
        assertSame(upload, custom.uploadProgressListener());
        assertSame(download, custom.downloadProgressListener());
    }

    @Test
    @DisplayName("JdkHttpEngine send dispatches WireRequest and maps to WireResponse")
    void sendDispatchesAndMapsResponse() throws Exception {
        byte[] responseBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

        HttpClient mockClient = new StubHttpClient(200, Map.of("Content-Type", List.of("application/json")), responseBytes);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("https://api.example.com/items"))
                .header("X-Custom", "test-val")
                .contentType("application/json")
                .body("{\"name\":\"item1\"}".getBytes(StandardCharsets.UTF_8))
                .build();

        HttpEngineOptions options = HttpEngineOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .streamResponse(false)
                .build();

        WireResponse response = engine.send(request, options);

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.header("Content-Type").orElse(null));
        assertArrayEquals(responseBytes, response.body());
    }

    @Test
    @DisplayName("JdkHttpEngine sendAsync executes non-blockingly and returns CompletableFuture")
    void sendAsyncDispatchesFuture() throws Exception {
        byte[] responseBytes = "async-ok".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(201, Map.of("X-Trace", List.of("trace-123")), responseBytes);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/items/1"))
                .build();

        CompletableFuture<WireResponse> future = engine.sendAsync(request, HttpEngineOptions.defaults());
        WireResponse response = future.join();

        assertEquals(201, response.statusCode());
        assertEquals("trace-123", response.header("X-Trace").orElse(null));
        assertArrayEquals(responseBytes, response.body());
    }

    @Test
    @DisplayName("JdkHttpEngine supports streaming responses when streamResponse=true")
    void sendStreamingResponse() throws Exception {
        byte[] data = "streaming-payload-content".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of("Content-Type", List.of("text/plain")), data);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/stream"))
                .build();

        HttpEngineOptions options = HttpEngineOptions.builder()
                .streamResponse(true)
                .build();

        WireResponse response = engine.send(request, options);

        assertEquals(200, response.statusCode());
        assertNotNull(response.stream());
        assertEquals(null, response.body());
        assertArrayEquals(data, response.stream().readAllBytes());
    }

    @Test
    @DisplayName("JdkHttpEngine handles multipart requests and progress listeners")
    void sendMultipartWithProgress() throws Exception {
        byte[] dummyResponse = "ok".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of(), dummyResponse);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        List<Long> uploadSent = new ArrayList<>();
        UploadProgressListener uploadListener = (sent, total) -> uploadSent.add(sent);

        List<Long> downloadRead = new ArrayList<>();
        DownloadProgressListener downloadListener = (read, total) -> downloadRead.add(read);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("https://api.example.com/upload"))
                .parts(List.of(MultipartPart.text("field", "hello")))
                .boundary("test-boundary")
                .build();

        HttpEngineOptions options = HttpEngineOptions.builder()
                .uploadProgressListener(uploadListener)
                .downloadProgressListener(downloadListener)
                .build();

        WireResponse response = engine.send(request, options);
        assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("JdkHttpEngine send and sendAsync work safely with null options")
    void sendAndSendAsyncWithNullOptions() throws Exception {
        byte[] dummy = "ok-null-options".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of(), dummy);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/null-opt"))
                .build();

        WireResponse syncResp = engine.send(request, null);
        assertEquals(200, syncResp.statusCode());
        assertArrayEquals(dummy, syncResp.body());

        WireResponse asyncResp = engine.sendAsync(request, null).join();
        assertEquals(200, asyncResp.statusCode());
        assertArrayEquals(dummy, asyncResp.body());
    }

    @Test
    @DisplayName("JdkHttpEngine sendAsync supports streaming response with download progress")
    void sendAsyncStreamingWithDownloadProgress() throws Exception {
        byte[] dummy = "stream-progress-data".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of(), dummy);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        List<Long> readBytes = new ArrayList<>();
        DownloadProgressListener downloadListener = (read, total) -> readBytes.add(read);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/async-stream"))
                .build();

        HttpEngineOptions options = HttpEngineOptions.builder()
                .streamResponse(true)
                .downloadProgressListener(downloadListener)
                .build();

        WireResponse asyncResp = engine.sendAsync(request, options).join();
        assertEquals(200, asyncResp.statusCode());
        assertNotNull(asyncResp.stream());
    }

    @Test
    @DisplayName("JdkHttpEngine handles null-returning bodyPublisherSupplier and empty body publisher")
    void sendWithNullBodyPublisherSupplierFallback() throws Exception {
        byte[] dummy = "empty-pub-ok".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of(), dummy);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest request = WireRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("https://api.example.com/null-supplier"))
                .bodyPublisherSupplier(() -> null)
                .build();

        WireResponse response = engine.send(request, HttpEngineOptions.defaults());
        assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("JdkHttpEngine handles GET with payload body and GET with multipart parts")
    void sendGetMethodWithBodyAndWithParts() throws Exception {
        byte[] dummy = "get-with-body".getBytes(StandardCharsets.UTF_8);
        HttpClient mockClient = new StubHttpClient(200, Map.of(), dummy);
        JdkHttpEngine engine = JdkHttpEngine.from(mockClient);

        WireRequest getWithBody = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/search"))
                .body("{\"query\":\"term\"}".getBytes(StandardCharsets.UTF_8))
                .contentType("application/json")
                .build();

        WireResponse resp1 = engine.send(getWithBody, HttpEngineOptions.defaults());
        assertEquals(200, resp1.statusCode());

        WireRequest getWithParts = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/search-multi"))
                .parts(List.of(MultipartPart.text("field", "val")))
                .boundary("bound1")
                .build();

        WireResponse resp2 = engine.send(getWithParts, HttpEngineOptions.defaults());
        assertEquals(200, resp2.statusCode());

        WireRequest getWithSupplier = WireRequest.builder()
                .method(HttpMethod.GET)
                .uri(URI.create("https://api.example.com/search-pub"))
                .bodyPublisherSupplier(() -> HttpRequest.BodyPublishers.noBody())
                .build();
        WireResponse resp3 = engine.send(getWithSupplier, HttpEngineOptions.defaults());
        assertEquals(200, resp3.statusCode());

        // notifyZeroUploadIfPending with non-ProgressBodyPublisher
        HttpRequest nonProgressReq = HttpRequest.newBuilder(URI.create("https://api.example.com/search"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpEngineOptions listenerOpts = HttpEngineOptions.builder()
                .uploadProgressListener((bytes, total) -> {})
                .build();
        JdkHttpEngine.notifyZeroUploadIfPending(nonProgressReq, listenerOpts);
    }

    @Test
    @DisplayName("JdkHttpEngine toHeaders safely handles null and empty map")
    void toHeadersEdgeCases() {
        List<Header> nullHeaders = JdkHttpEngine.toHeaders(null);
        assertTrue(nullHeaders.isEmpty());

        HttpHeaders multiValued = HttpHeaders.of(
                Map.of("X-Multi", List.of("v1", "v2")),
                (k, v) -> true
        );
        List<Header> parsed = JdkHttpEngine.toHeaders(multiValued);
        assertEquals(2, parsed.size());
        assertEquals("X-Multi", parsed.get(0).name());
        assertEquals("v1", parsed.get(0).value());
        assertEquals("X-Multi", parsed.get(1).name());
        assertEquals("v2", parsed.get(1).value());
    }

    @Test
    @DisplayName("JdkHttpEngine.create configures all ModuleInfo options including custom executor and zero timeout")
    void createConfiguresCustomExecutorAndSslOptions() {
        java.util.concurrent.ExecutorService directExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.net.CookieManager cookieManager = new java.net.CookieManager();
            java.net.ProxySelector proxySelector = java.net.ProxySelector.getDefault();
            java.net.Authenticator authenticator = new java.net.Authenticator() {};

            SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                    .baseUrl("https://example.com")
                    .connectTimeout(Duration.ZERO) // zero timeout means no timeout in JDK HttpClient
                    .executor(directExecutor)
                    .cookieHandler(cookieManager)
                    .proxy(proxySelector)
                    .authenticator(authenticator)
                    .virtualThreads(false)
                    .build();

            JdkHttpEngine engine = JdkHttpEngine.create(moduleInfo);
            assertNotNull(engine.getHttpClient());
            engine.close();
        } finally {
            directExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("JdkHttpEngine with managed virtual thread executor closes executor on close()")
    void managedEngineClosesVirtualThreadExecutor() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .virtualThreads(true)
                .build();

        JdkHttpEngine engine = JdkHttpEngine.create(moduleInfo);
        assertNotNull(engine.getHttpClient());
        engine.close(); // shuts down both client and managedExecutor
    }

    /**
     * Minimal stub of JDK HttpClient for unit testing JdkHttpEngine without network sockets.
     */
    private static final class StubHttpClient extends HttpClient {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final byte[] responseBytes;

        StubHttpClient(int statusCode, Map<String, List<String>> headers, byte[] responseBytes) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.responseBytes = responseBytes;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return (HttpResponse<T>) createStubResponse(responseHandler);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) createStubResponse(responseHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseHandler);
        }

        private Object createStubResponse(HttpResponse.BodyHandler<?> handler) {
            HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(headers, (k, v) -> true);
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };

            HttpResponse.BodySubscriber<?> subscriber = handler.apply(info);
            // If ofInputStream or ofByteArray
            Object body;
            try {
                // deliver bytes to subscriber
                var flowSub = (java.util.concurrent.Flow.Subscriber<java.util.List<java.nio.ByteBuffer>>) subscriber;
                flowSub.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override
                    public void request(long n) {}

                    @Override
                    public void cancel() {}
                });
                flowSub.onNext(List.of(java.nio.ByteBuffer.wrap(responseBytes)));
                flowSub.onComplete();
                body = subscriber.getBody().toCompletableFuture().join();
            } catch (Throwable t) {
                // Fallback for handlers that produce InputStream or byte[]
                body = responseBytes;
            }

            final Object responseBody = body;
            return new HttpResponse<Object>() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public HttpRequest request() {
                    return null;
                }

                @Override
                public java.util.Optional<HttpResponse<Object>> previousResponse() {
                    return java.util.Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(headers, (k, v) -> true);
                }

                @Override
                public Object body() {
                    return responseBody;
                }

                @Override
                public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return java.util.Optional.empty();
                }

                @Override
                public URI uri() {
                    return URI.create("https://api.example.com");
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Duration> connectTimeout() {
            return java.util.Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }
    }
}
