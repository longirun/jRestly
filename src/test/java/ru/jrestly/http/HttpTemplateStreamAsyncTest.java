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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * Unit tests for the asynchronous streaming contract (ADR-0015, Aspect 4):
 * a {@code CompletableFuture<InputStream>} return type is dispatched via
 * {@code sendAsync} and the future completes with exactly what the
 * synchronous twin would return — the stream as-is on success (byte
 * binding: codec-free, unread, unclosed, the same bytes to EOF), and on
 * the error paths the same exception the synchronous call would throw — a
 * {@link HandledException} with the decoded error object, an
 * {@link UnexpectedStatusException} with the full raw payload, or a
 * {@link RuntimeException} wrapping the transport failure — surfaced by
 * {@code join()} as the cause of the {@code CompletionException}.
 *
 * <p>No WireMock: the {@code HttpClient} is a stub subclass whose
 * {@code sendAsync} answers from queued, pre-completed or pre-failed
 * futures, so the whole chain runs deterministically on the calling
 * thread. The wire seam itself (the {@code sendAsync} branch,
 * intermediate-stream disposal) is gated by
 * {@code HttpTemplateStreamWireTest}; the shared tail (the stream branch
 * of {@code processResponse}) by {@code HttpTemplateStreamContractTest}.
 */
class HttpTemplateStreamAsyncTest {

    private final AtomicInteger sends = new AtomicInteger();
    private final StubCodec codec = new StubCodec();

    // ------------------------------------------------------------------
    // the success path: the future carries the stream itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success: the future completes with the stream as-is — codec-free, unread, unclosed")
    void successCompletesWithTheStreamAsIs() {
        TrackingStream stream = new TrackingStream("streaming-payload".getBytes(StandardCharsets.UTF_8));
        // a codec that throws on use: the success path must never touch it
        codec.failure = new IllegalStateException("a stream return never decodes");
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(
                CompletableFuture.completedFuture(response(200, Map.of(), stream)))));

        CompletableFuture<Object> future = t.exchange();
        InputStream result = (InputStream) future.join();

        assertSame(stream, result);
        assertFalse(stream.closed);
        assertEquals(0, stream.bytesRead);
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("success: the stream from the future delivers the same bytes to EOF")
    void successDeliversTheSameBytes() throws IOException {
        byte[] payload = "streaming-payload".getBytes(StandardCharsets.UTF_8);
        TrackingStream stream = new TrackingStream(payload);
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(
                CompletableFuture.completedFuture(response(200, Map.of(), stream)))));

        CompletableFuture<Object> future = t.exchange();
        InputStream result = (InputStream) future.join();

        assertEquals("streaming-payload", new String(result.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(payload.length, stream.bytesRead);
        // JDK 21's readAllBytes() does not close the stream (probed 2026-10-14)
        assertFalse(stream.closed);
    }

    @Test
    @DisplayName("success with an empty body: the future still completes with the stream — EOF on the first read, never null")
    void successWithAnEmptyBodyStillCompletesWithTheStream() throws IOException {
        TrackingStream stream = new TrackingStream(new byte[0]);
        // a codec that throws on use: an empty body is not decoded either
        codec.failure = new IllegalStateException("a stream return never decodes");
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(
                CompletableFuture.completedFuture(response(200, Map.of(), stream)))));

        CompletableFuture<Object> future = t.exchange();
        InputStream result = (InputStream) future.join();

        assertSame(stream, result);
        assertEquals(-1, result.read()); // EOF on the first read
        assertEquals(0, codec.deserializeCalls);
    }

    // ------------------------------------------------------------------
    // the error paths: the future fails with the sync twin's exception
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@OnError status: the future fails with the HandledException — decoded object, status, headers; the stream is consumed fully")
    void onErrorCompletesExceptionallyWithTheHandledException() {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(List.of(429));
        parser.setErrorClass(TestDto.class);
        String body = "{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}";
        TrackingStream stream = new TrackingStream(body.getBytes(StandardCharsets.UTF_8));
        TestDto errorObject = new TestDto(429L, "rate", "limited");
        codec.result = errorObject;
        HttpTemplate t = template();
        t.setOnErrorParser(parser);
        t.setHttpClient(stubClient(List.of(
                CompletableFuture.completedFuture(response(429,
                        Map.of("X-Request-Id", List.of("r-1")), stream)))));

        CompletableFuture<Object> future = t.exchange();
        HandledException ex = causeOf(future::join);

        assertSame(errorObject, ex.getDetails());
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getResponseHeaders().stream()
                .anyMatch(h -> "X-Request-Id".equalsIgnoreCase(h.name())), ex.getResponseHeaders().toString());
        assertEquals(body, codec.lastPayload); // the stream was consumed fully, in order
        assertEquals(1, codec.deserializeCalls);
        assertEquals(body.length(), stream.bytesRead);
        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("unexpected status: the future fails with the UnexpectedStatusException carrying the full raw payload; the stream is consumed")
    void unexpectedStatusCompletesExceptionallyWithTheFullRawPayload() {
        byte[] payload = "full-raw-payload".getBytes(StandardCharsets.UTF_8);
        TrackingStream stream = new TrackingStream(payload);
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(
                CompletableFuture.completedFuture(response(404, Map.of(), stream)))));

        CompletableFuture<Object> future = t.exchange();
        UnexpectedStatusException ex = causeOf(future::join);

        assertEquals("full-raw-payload", ex.getRawBody());
        assertEquals(404, ex.getStatusCode());
        assertEquals(payload.length, stream.bytesRead);
        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("transport failure: the future fails with the same RuntimeException the sync twin would throw (the IOException as the cause)")
    void transportFailureIsMappedLikeTheSyncTwin() {
        IOException io = new IOException("connection reset");
        HttpTemplate t = template();
        t.setHttpClient(stubClient(List.of(CompletableFuture.failedFuture(io))));

        CompletableFuture<Object> future = t.exchange();
        RuntimeException runtime = causeOf(future::join);

        assertSame(io, runtime.getCause());
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
        t.setJsonCodec(codec);
        t.setReturnType(InputStream.class);
        t.setStreamBody(true);
        t.setAsync(true);
        return t;
    }

    private HttpClient stubClient(List<CompletableFuture<HttpResponse<?>>> futures) {
        return new StubHttpClient(new ArrayDeque<>(futures), sends);
    }

    // A stub over HttpClient: the template only ever calls sendAsync on
    // these tests, every other method fails fast. HttpClient is an abstract
    // JDK class (not an interface), so the stub is a minimal subclass
    private static final class StubHttpClient extends HttpClient {

        private final Deque<CompletableFuture<HttpResponse<?>>> queue;
        private final AtomicInteger sends;

        StubHttpClient(Deque<CompletableFuture<HttpResponse<?>>> queue, AtomicInteger sends) {
            this.queue = queue;
            this.sends = sends;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends.incrementAndGet();
            @SuppressWarnings("unchecked") // the queue holds the test's stub futures
            CompletableFuture<HttpResponse<T>> response =
                    (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) queue.poll();
            return response;
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
        public void log(System.Logger.Level level, ResourceBundle bundle, String messageKey, Object... parameters) {
        }

        @Override
        public void log(System.Logger.Level level, ResourceBundle bundle, String messageKey, Throwable thrown) {
        }
    };
}
