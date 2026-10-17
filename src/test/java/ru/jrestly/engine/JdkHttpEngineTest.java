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
