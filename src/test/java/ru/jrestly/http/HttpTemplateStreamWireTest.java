package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.fixtures.TestModuleInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the streaming wire seam (ADR-0015, Aspect 2): the
 * {@code BodyHandlers.ofInputStream()} branch of the send path, the
 * {@link WireResponse#stream()} component, and the disposal of intermediate
 * streams — a redirect hop's and a retried attempt's stream is single-use
 * and must be closed <em>before</em> the next send, while the final
 * response's stream is handed over untouched (sync and async twins).
 *
 * <p>No WireMock: the {@code HttpClient} is a recording subclass and
 * the bodies are tracking {@link InputStream} stand-ins, so the seam's
 * decisions are observable event by event — the "closed before the next
 * hop" gate is an order assertion over the recorded events, not an
 * assumption (a gate without a negative check is not a gate).
 *
 * <p>The success branch of {@code processResponse} is the next step of the
 * epic and not in scope yet: a full {@code exchange()}-level call on a
 * stream response still yields {@code null} in these tests — the subject
 * under test is the wire, not the contract.
 */
class HttpTemplateStreamWireTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger sends = new AtomicInteger();

    // ------------------------------------------------------------------
    // the send-path branch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("streamBody on: the send path takes ofInputStream — the wire response carries the stream, body null")
    void streamBodyOnCarriesTheStream() throws Exception {
        HttpTemplate t = template();
        t.setStreamBody(true);
        TrackingStream stream = new TrackingStream("final", "streaming-payload".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200,
                Map.of("content-type", List.of("application/octet-stream")), stream))));

        WireResponse wire = t.send(request());

        assertSame(stream, wire.stream());
        assertNull(wire.body());
        assertEquals(200, wire.statusCode());
        assertTrue(wire.headers().stream().anyMatch(h -> "content-type".equals(h.name())));
        assertFalse(stream.closed);
    }

    @Test
    @DisplayName("streamBody off (default): the send path stays on ofByteArray — the wire response carries the bytes")
    void streamBodyOffKeepsTheBytes() throws Exception {
        HttpTemplate t = template();
        byte[] bytes = "plain-bytes".getBytes(StandardCharsets.UTF_8);
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), bytes))));

        WireResponse wire = t.send(request());

        assertSame(bytes, wire.body());
        assertNull(wire.stream());
    }

    // ------------------------------------------------------------------
    // intermediate streams: sync circuit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("redirect: the intermediate stream is closed before the next send; the final stream is intact")
    void redirectClosesTheIntermediateStreamBeforeTheNextSend() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setFollowRedirectsNumber(3);
        TrackingStream intermediate = new TrackingStream("intermediate", new byte[]{1, 2, 3});
        TrackingStream finalStream = new TrackingStream("final", "final-bytes".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(
                response(307, Map.of("location", List.of("/api/stream-target")), intermediate),
                response(200, Map.of(), finalStream))));

        assertNull(t.exchange());

        assertTrue(intermediate.closed);
        assertFalse(finalStream.closed);
        assertOrder("send-1", "close-intermediate", "send-2");
        assertEquals(2, sends.get());
    }

    @Test
    @DisplayName("retry: the failed attempt's stream is closed before the next attempt; the final stream is intact")
    void retryClosesTheAttemptStreamBeforeTheNextAttempt() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .build());
        TrackingStream failedAttempt = new TrackingStream("failed-attempt", new byte[]{9});
        TrackingStream finalStream = new TrackingStream("final", "final-bytes".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(
                response(503, Map.of(), failedAttempt),
                response(200, Map.of(), finalStream))));

        assertNull(t.exchange());

        assertTrue(failedAttempt.closed);
        assertFalse(finalStream.closed);
        assertOrder("send-1", "close-failed-attempt", "send-2");
        assertEquals(2, sends.get());
    }

    // ------------------------------------------------------------------
    // intermediate streams: async twin
    // ------------------------------------------------------------------

    @Test
    @DisplayName("async redirect: the intermediate stream is closed before the next send; the final stream is intact")
    void asyncRedirectClosesTheIntermediateStreamBeforeTheNextSend() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setAsync(true);
        t.setFollowRedirectsNumber(3);
        TrackingStream intermediate = new TrackingStream("intermediate", new byte[]{1, 2, 3});
        TrackingStream finalStream = new TrackingStream("final", "final-bytes".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(
                response(307, Map.of("location", List.of("/api/stream-target")), intermediate),
                response(200, Map.of(), finalStream))));

        CompletableFuture<Object> future = t.exchange();
        assertNull(future.join());

        assertTrue(intermediate.closed);
        assertFalse(finalStream.closed);
        assertOrder("send-1", "close-intermediate", "send-2");
        assertEquals(2, sends.get());
    }

    @Test
    @DisplayName("async retry: the failed attempt's stream is closed before the next attempt; the final stream is intact")
    void asyncRetryClosesTheAttemptStreamBeforeTheNextAttempt() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setAsync(true);
        t.setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .build());
        TrackingStream failedAttempt = new TrackingStream("failed-attempt", new byte[]{9});
        TrackingStream finalStream = new TrackingStream("final", "final-bytes".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(
                response(503, Map.of(), failedAttempt),
                response(200, Map.of(), finalStream))));

        CompletableFuture<Object> future = t.exchange();
        assertNull(future.join());

        assertTrue(failedAttempt.closed);
        assertFalse(finalStream.closed);
        assertOrder("send-1", "close-failed-attempt", "send-2");
        assertEquals(2, sends.get());
    }

    // ------------------------------------------------------------------
    // builder detection: the effective return type flips the flag
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builder: an InputStream return type flips streamBody on — sync and the async twin; a String return does not")
    void builderDetectsInputStreamReturnTypes() throws Exception {
        assertTrue(detectsStream(StreamController.class.getMethod("download")));
        assertTrue(detectsStream(StreamController.class.getMethod("downloadAsync")));
        assertFalse(detectsStream(StreamController.class.getMethod("raw")));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private HttpTemplate template() {
        HttpTemplate t = new HttpTemplate();
        t.setLogger(NO_OP_LOGGER);
        t.setModuleInfo(new TestModuleInfo("http://localhost:1"));
        t.setUrl("http://localhost:1/api/stream");
        t.setHttpMethod(HttpMethod.GET);
        t.setHeaders(List.of());
        return t;
    }

    private WireRequest request() {
        return new WireRequest(HttpMethod.GET, URI.create("http://localhost:1/api/stream"),
                List.of(), null, null, null, null);
    }

    private boolean detectsStream(Method method) throws Exception {
        // the gate is the flag the builder set from the effective return type —
        // a stubbed send cannot prove it, since the stub answers with a stream
        // body no matter which BodyHandler the send path requested
        HttpTemplate t = new HttpTemplateBuilder(
                new TestModuleInfo("http://localhost:1"),
                stubClient(List.of(response(200, Map.of(),
                        new TrackingStream("final", new byte[0])))),
                method, new Object[0], StreamController.class).build();
        return t.streamBody();
    }

    // A recording stub over HttpClient: every send/sendAsync is logged as
    // "send-N" and answered from the queued stub responses
    private HttpClient stubClient(List<HttpResponse<?>> responses) {
        return new StubHttpClient(new ArrayDeque<>(responses), events, sends);
    }

    // HttpClient is an abstract JDK class (not an interface), so the stub is
    // a minimal subclass: the template only ever calls send / sendAsync,
    // every other method fails fast
    private static final class StubHttpClient extends HttpClient {

        private final Deque<HttpResponse<?>> queue;
        private final List<String> events;
        private final AtomicInteger sends;

        StubHttpClient(Deque<HttpResponse<?>> queue, List<String> events, AtomicInteger sends) {
            this.queue = queue;
            this.events = events;
            this.sends = sends;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            events.add("send-" + sends.incrementAndGet());
            @SuppressWarnings("unchecked") // the queue holds the test's stub responses
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            events.add("send-" + sends.incrementAndGet());
            @SuppressWarnings("unchecked") // the queue holds the test's stub responses
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Redirect followRedirects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Version version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            throw new UnsupportedOperationException();
        }
    }

    private <T> HttpResponse<T> response(int status, Map<String, List<String>> headers, T body) {
        return new StubResponse<>(status, headers, body);
    }

    private void assertOrder(String... labels) {
        List<String> snapshot = List.copyOf(events);
        int previous = -1;
        String previousLabel = "(start)";
        for (String label : labels) {
            int index = snapshot.indexOf(label);
            assertTrue(index > previous,
                    "expected " + label + " after " + previousLabel + " but saw: " + snapshot);
            previous = index;
            previousLabel = label;
        }
    }

    // Java 21: the abstract System.Logger methods are getName, isLoggable,
    // and the two ResourceBundle log variants — everything else is a default
    // delegating to them
    private static final System.Logger NO_OP_LOGGER = new System.Logger() {
        @Override
        public String getName() {
            return "no-op";
        }

        @Override
        public boolean isLoggable(System.Logger.Level level) {
            return false;
        }

        @Override
        public void log(System.Logger.Level level, java.util.ResourceBundle bundle, String messageKey, Object... parameters) {
        }

        @Override
        public void log(System.Logger.Level level, java.util.ResourceBundle bundle, String messageKey, Throwable thrown) {
        }
    };

    private record StubResponse<T>(int status, Map<String, List<String>> rawHeaders, T body) implements HttpResponse<T> {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpHeaders headers() {
            // HttpHeaders.of's second argument is a (name, value) FILTER, not a
            // name matcher — keep everything
            return HttpHeaders.of(rawHeaders, (name, value) -> true);
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost:1/api/stream");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    // A payload-carrying InputStream that records its close() as an event
    private final class TrackingStream extends InputStream {

        private final String label;
        private final byte[] payload;
        private int position;
        private boolean closed;

        TrackingStream(String label, byte[] payload) {
            this.label = label;
            this.payload = payload;
        }

        @Override
        public int read() {
            return position < payload.length ? payload[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= payload.length) {
                return -1;
            }
            int count = Math.min(length, payload.length - position);
            System.arraycopy(payload, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            events.add("close-" + label);
        }
    }

    private interface StreamController {

        @Get(path = "/api/stream")
        InputStream download();

        @Get(path = "/api/stream")
        CompletableFuture<InputStream> downloadAsync();

        @Get(path = "/api/stream")
        String raw();
    }
}
