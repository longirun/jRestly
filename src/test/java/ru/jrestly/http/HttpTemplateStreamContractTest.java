package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.fixtures.TestModuleInfo;
import ru.jrestly.json.JsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the synchronous streaming contract (ADR-0015, Aspect 3 + 5):
 * the stream branch of {@code processResponse} — the success path returns
 * the stream as-is (byte binding: codec-free, unread, unclosed), the
 * {@code @OnError} and unexpected-status paths consume it fully and keep
 * the {@code byte[]} contract (decoded error object / full raw payload),
 * and the payload log line is replaced by the streaming variant that never
 * reads the body.
 *
 * <p>No WireMock: the {@code HttpClient} is a recording subclass, the body
 * is a tracking {@link InputStream}, the codec and the logger are stubs —
 * "the success path never touches the codec" is pinned by a codec that
 * throws on use, and "logging does not consume the body" by a stream that
 * records every read byte.
 */
class HttpTemplateStreamContractTest {

    private final AtomicInteger sends = new AtomicInteger();
    private final StubCodec codec = new StubCodec();
    private final CapturingLogger logger = new CapturingLogger();

    // ------------------------------------------------------------------
    // the success path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success: the stream is returned as-is — codec-free, unread, unclosed")
    void successReturnsTheStreamAsIs() {
        TrackingStream stream = new TrackingStream("streaming-payload".getBytes(StandardCharsets.UTF_8));
        // a codec that throws on use: the success path must never touch it
        codec.failure = new IllegalStateException("a stream return never decodes");
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        InputStream result = t.exchange();

        assertSame(stream, result);
        assertFalse(stream.closed);
        assertEquals(0, stream.bytesRead);
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("success: the payload line is replaced by the streaming line with the Content-Length")
    void successLogsTheStreamingLineWithContentLength() {
        TrackingStream stream = new TrackingStream("streaming-payload".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Length", List.of("17")), stream))));

        t.exchange();

        String log = logger.last();
        assertEquals("streaming, Content-Length: 17", log);
        assertFalse(log.contains("streaming-payload"), log); // the body is never read for the log
        assertEquals(0, stream.bytesRead);
    }

    @Test
    @DisplayName("success, no Content-Length: the streaming line says the length is unknown")
    void successLogsTheStreamingLineWithoutContentLength() {
        TrackingStream stream = new TrackingStream("streaming-payload".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        t.exchange();

        assertEquals("streaming (length unknown)", logger.last());
    }

    // ------------------------------------------------------------------
    // the error paths: the stream is consumed, the byte[] contract is kept
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@OnError status: the stream is consumed fully, the error object is decoded, the exception carries object + status + headers")
    void onErrorConsumesTheStreamAndDecodesTheErrorObject() {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(List.of(429));
        parser.setErrorClass(TestDto.class);
        String body = "{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}";
        TrackingStream stream = new TrackingStream(body.getBytes(StandardCharsets.UTF_8));
        TestDto errorObject = new TestDto(429L, "rate", "limited");
        codec.result = errorObject;
        HttpTemplate t = template();
        t.setOnErrorParser(parser);
        t.setHttpClient(stubClient(List.of(response(429,
                Map.of("X-Request-Id", List.of("r-1")), stream))));

        HandledException ex = assertThrows(HandledException.class, t::exchange);

        TestDto details = ex.getDetails();
        assertSame(errorObject, details);
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getResponseHeaders().stream()
                .anyMatch(h -> "X-Request-Id".equalsIgnoreCase(h.name())), ex.getResponseHeaders().toString());
        assertEquals(body, codec.lastPayload); // the stream was consumed fully, in order
        assertTrue(stream.closed);
        assertEquals(body.length(), stream.bytesRead);
    }

    @Test
    @DisplayName("@OnError status with an empty stream: null details, the codec is not called (uniform with the byte[] path)")
    void onErrorWithAnEmptyStreamCarriesNullDetails() {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(List.of(429));
        parser.setErrorClass(TestDto.class);
        TrackingStream stream = new TrackingStream(new byte[0]);
        HttpTemplate t = template();
        t.setOnErrorParser(parser);
        t.setHttpClient(stubClient(List.of(response(429, Map.of(), stream))));

        HandledException ex = assertThrows(HandledException.class, t::exchange);

        assertNull(ex.getDetails());
        assertEquals(429, ex.getStatusCode());
        assertEquals(0, codec.deserializeCalls);
        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("unexpected status: the stream is consumed and the exception carries the full raw payload")
    void unexpectedStatusCarriesTheFullRawPayload() {
        byte[] payload = "full-raw-payload".getBytes(StandardCharsets.UTF_8);
        TrackingStream stream = new TrackingStream(payload);
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(response(404, Map.of(), stream))));

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, t::exchange);

        assertEquals("full-raw-payload", ex.getRawBody());
        assertEquals(404, ex.getStatusCode());
        assertEquals(payload.length, stream.bytesRead);
        assertTrue(stream.closed);
    }

    // ------------------------------------------------------------------
    // the decode axis: a stream return cannot fail to decode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("probe: a stream response — the payload decode cannot fail, true without touching the codec")
    void probeTreatsAStreamResponseAsDecodeProof() {
        HttpTemplate t = template();
        WireResponse wire = new WireResponse(200, List.of(), null,
                new TrackingStream("probe-bytes".getBytes(StandardCharsets.UTF_8)));

        assertTrue(t.probePayloadDecode(wire));
        assertEquals(0, codec.deserializeCalls);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private HttpTemplate template() {
        HttpTemplate t = new HttpTemplate();
        t.setLogger(logger);
        t.setModuleInfo(new TestModuleInfo("http://localhost:1"));
        t.setUrl("http://localhost:1/api/stream");
        t.setHttpMethod(HttpMethod.GET);
        t.setHeaders(List.of());
        t.setJsonCodec(codec);
        t.setReturnType(InputStream.class);
        t.setStreamBody(true);
        return t;
    }

    // A stub over HttpClient: the template only ever calls send on these
    // sync tests, every other method fails fast. HttpClient is an abstract
    // JDK class (not an interface), so the stub is a minimal subclass
    private HttpClient stubClient(List<HttpResponse<?>> responses) {
        return new StubHttpClient(new ArrayDeque<>(responses), sends);
    }

    private static final class StubHttpClient extends HttpClient {

        private final Deque<HttpResponse<?>> queue;
        private final AtomicInteger sends;

        StubHttpClient(Deque<HttpResponse<?>> queue, AtomicInteger sends) {
            this.queue = queue;
            this.sends = sends;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends.incrementAndGet();
            @SuppressWarnings("unchecked") // the queue holds the test's stub responses
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
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

    // A payload-carrying InputStream that records every read byte and its
    // close() — "consumed fully" and "never read" are byte counts, not
    // assumptions
    private static final class TrackingStream extends InputStream {

        private final byte[] payload;
        private int position;
        private int bytesRead;
        private boolean closed;

        TrackingStream(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public int read() {
            int b = position < payload.length ? payload[position++] & 0xFF : -1;
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= payload.length) {
                return -1;
            }
            int count = Math.min(length, payload.length - position);
            System.arraycopy(payload, position, buffer, offset, count);
            position += count;
            bytesRead += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // A stub JsonCodec: records every deserialize call and the payload it
    // was given; returns `result` or throws `failure` (a test sets at most
    // one)
    private static final class StubCodec implements JsonCodec {
        int deserializeCalls;
        String lastPayload;
        Object result;
        RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String payload, Type type) {
            deserializeCalls++;
            lastPayload = payload;
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }

        @Override
        public String serialize(Object value) {
            return "{\"stub\":true}";
        }

        @Override
        public String prettyPrint(String payload) {
            return payload;
        }
    }

    // The System.Logger default log(Level, String, ...) methods funnel into
    // the abstract log(Level, ResourceBundle, String, Object.../Throwable)
    // pair — capture there (JDK 21: System.Logger is an interface)
    private static final class CapturingLogger implements System.Logger {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "jrestly-stream-contract-capture";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            messages.add(params == null || params.length == 0 ? format : String.format(format, params));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Throwable thrown) {
            messages.add(format + (thrown == null ? "" : " cause=" + thrown));
        }

        String last() {
            assertFalse(messages.isEmpty(), "no log records captured");
            return messages.get(messages.size() - 1);
        }
    }
}
