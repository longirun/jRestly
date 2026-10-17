package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestModuleInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.CookieHandler;
import java.net.ProxySelector;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code Optional} × streaming intersection of
 * {@code HttpTemplate.processResponse} (ADR-0023 × ADR-0015/0016): an
 * {@code Optional<InputStream>}, {@code Optional<Stream<String>>} or
 * {@code Optional<Stream<ServerSentEvent>>} return must yield
 * {@code Optional.empty()} on the missing-resource statuses (404/304/204,
 * the stream closed) and the <em>wrapped</em> streaming artifact on
 * success — never the raw artifact, which would be a
 * {@link ClassCastException} at the caller's typed assignment.
 *
 * <p>No WireMock: the {@code HttpClient} is a recording stub, exactly as in
 * {@code HttpTemplateStreamWireTest} — the seam is the exchange level, the
 * template flags are set directly.
 */
class HttpTemplateOptionalStreamTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------------
    // success path: the artifact must be wrapped, not handed over raw
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Optional<Stream<String>> on 200: the line stream is wrapped in Optional.of, reads work, close propagates")
    void optionalLineStreamIsWrappedOnSuccess() {
        HttpTemplate t = optionalTemplate(streamType("lines"));
        TrackingStream stream = new TrackingStream("lines", "alpha\nbeta".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Object result = t.exchange();

        assertTrue(result instanceof Optional<?>,
                "expected an Optional<Stream<String>>, got " + result.getClass().getName());
        Optional<?> wrapped = (Optional<?>) result;
        assertTrue(wrapped.isPresent());
        @SuppressWarnings("unchecked") // the template's return type is the cast's contract
        Stream<String> lines = (Stream<String>) wrapped.get();
        List<String> all;
        try (lines) { // closing the artifact must reach the wire stream
            all = lines.collect(Collectors.toList());
        }
        assertEquals(List.of("alpha", "beta"), all);
        assertTrue(stream.closed, "closing the line stream must close the wire stream");
    }

    @Test
    @DisplayName("Optional<Stream<ServerSentEvent>> on 200: the event stream is wrapped in Optional.of")
    void optionalEventStreamIsWrappedOnSuccess() {
        HttpTemplate t = optionalTemplate(streamType("events"));
        TrackingStream stream = new TrackingStream("events", "data: one\n\n".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Object result = t.exchange();

        assertTrue(result instanceof Optional<?>,
                "expected an Optional<Stream<ServerSentEvent>>, got " + result.getClass().getName());
        Optional<?> wrapped = (Optional<?>) result;
        assertTrue(wrapped.isPresent());
        @SuppressWarnings("unchecked") // the template's return type is the cast's contract
        Stream<ServerSentEvent> eventStream = (Stream<ServerSentEvent>) wrapped.get();
        List<String> data;
        try (eventStream) { // closing the artifact must reach the wire stream
            data = eventStream.map(ServerSentEvent::data).collect(Collectors.toList());
        }
        assertEquals(List.of("one"), data);
        assertTrue(stream.closed, "closing the event stream must close the wire stream");
    }

    @Test
    @DisplayName("Optional<InputStream> on 200: the wire stream is wrapped in Optional.of (the pre-existing working leg)")
    void optionalInputStreamIsWrappedOnSuccess() {
        HttpTemplate t = optionalTemplate(InputStream.class);
        TrackingStream stream = new TrackingStream("bytes", "raw-bytes".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Object result = t.exchange();

        assertTrue(result instanceof Optional<?>,
                "expected an Optional<InputStream>, got " + result.getClass().getName());
        Optional<?> wrapped = (Optional<?>) result;
        assertTrue(wrapped.isPresent());
        assertSame(stream, wrapped.get());
    }

    @Test
    @DisplayName("async twin: Optional<Stream<String>> on 200 is wrapped in Optional.of, not handed over raw")
    void optionalLineStreamIsWrappedOnSuccessAsync() {
        HttpTemplate t = optionalTemplate(streamType("lines"));
        t.setAsync(true);
        TrackingStream stream = new TrackingStream("lines", "alpha\nbeta".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        CompletableFuture<?> future = t.exchange();
        Object result = future.join();

        assertTrue(result instanceof Optional<?>,
                "expected an Optional<Stream<String>>, got " + result.getClass().getName());
        Optional<?> wrapped = (Optional<?>) result;
        assertTrue(wrapped.isPresent());
        Stream<?> lines = (Stream<?>) wrapped.get();
        lines.close();
        assertTrue(stream.closed, "closing the wrapped line stream must close the wire stream");
    }

    // ------------------------------------------------------------------
    // missing-resource statuses: Optional.empty(), the stream closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Optional<Stream<String>> on 404: Optional.empty() and the stream is closed (expected via the Optional rule)")
    void optionalLineStreamOn404IsEmptyAndClosesTheStream() {
        HttpTemplate t = optionalTemplate(streamType("lines"));
        TrackingStream stream = new TrackingStream("lines404", "never-read".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(404, Map.of(), stream))));

        Object result = t.exchange();

        assertEquals(Optional.empty(), result,
                "expected Optional.empty() on 404, got " + result.getClass().getName());
        assertTrue(stream.closed, "the body stream of a 404 must be closed");
    }

    @Test
    @DisplayName("Optional<Stream<String>> on 304: Optional.empty() and the stream is closed (conditional, expected via the Optional rule)")
    void optionalLineStreamOn304IsEmptyAndClosesTheStream() {
        HttpTemplate t = optionalTemplate(streamType("lines"));
        TrackingStream stream = new TrackingStream("lines304", new byte[0]);
        t.setHttpClient(stubClient(List.of(response(304, Map.of(), stream))));

        Object result = t.exchange();

        assertEquals(Optional.empty(), result,
                "expected Optional.empty() on 304, got " + result.getClass().getName());
        assertTrue(stream.closed, "the body stream of a 304 must be closed");
    }

    // ------------------------------------------------------------------
    // the non-Optional streaming behavior must stay untouched
    // ------------------------------------------------------------------

    @Test
    @DisplayName("non-Optional Stream<String> on 200: the raw line stream is handed over unwrapped (behavior unchanged)")
    void nonOptionalLineStreamStaysUnwrapped() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setReturnType(streamType("lines"));
        TrackingStream stream = new TrackingStream("lines-raw", "alpha".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Object result = t.exchange();

        assertTrue(result instanceof Stream<?>,
                "the non-Optional path must hand the raw stream over, got " + result.getClass().getName());
        Stream<?> lines = (Stream<?>) result;
        assertEquals(List.of("alpha"), lines.collect(Collectors.toList()));
    }

    @Test
    @DisplayName("non-Optional Stream<String> on 404: the unexpected-status exception is thrown (the Optional rule does not apply)")
    void nonOptionalLineStreamOn404Throws() {
        HttpTemplate t = template();
        t.setStreamBody(true);
        t.setReturnType(streamType("lines"));
        TrackingStream stream = new TrackingStream("lines404-raw", "never-read".getBytes(StandardCharsets.UTF_8));
        t.setHttpClient(stubClient(List.of(response(404, Map.of(), stream))));

        assertThrowsWithStatus(404, t);
        assertTrue(stream.closed);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void assertThrowsWithStatus(int expected, HttpTemplate t) {
        try {
            t.exchange();
            throw new AssertionError("expected UnexpectedStatusException(" + expected + ")");
        } catch (RuntimeException e) {
            UnexpectedStatusException use = e instanceof UnexpectedStatusException direct
                    ? direct
                    : (UnexpectedStatusException) e.getCause();
            assertTrue(use != null && use.getStatusCode() == expected,
                    "expected UnexpectedStatusException(" + expected + "), got " + e);
        }
    }

    private HttpTemplate template() {
        HttpTemplate t = new HttpTemplate();
        t.setLogger(NO_OP_LOGGER);
        t.setModuleInfo(new TestModuleInfo("http://localhost:1"));
        t.setUrl("http://localhost:1/api/stream");
        t.setHttpMethod(HttpMethod.GET);
        t.setHeaders(List.of());
        t.setStreamBody(true);
        return t;
    }

    private HttpTemplate optionalTemplate(Type effectiveReturnType) {
        HttpTemplate t = template();
        t.setOptional(true);
        t.setReturnType(effectiveReturnType);
        return t;
    }

    // the effective (Optional-unwrapped) streaming return type, taken from a
    // real signature so the generic shape is exactly the compiler's
    private static Type streamType(String method) {
        try {
            Method m = OptionalStreamController.class.getMethod(method);
            return m.getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    interface OptionalStreamController {
        Stream<String> lines();

        Stream<ServerSentEvent> events();
    }

    private HttpClient stubClient(List<HttpResponse<?>> responses) {
        return new StubHttpClient(new ArrayDeque<>(responses));
    }

    private static final class StubHttpClient extends HttpClient {

        private final Deque<HttpResponse<?>> queue;

        StubHttpClient(Deque<HttpResponse<?>> queue) {
            this.queue = queue;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            @SuppressWarnings("unchecked") // the queue holds the test's stub responses
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
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
        public Optional<CookieHandler> cookieHandler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpClient.Redirect followRedirects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProxySelector> proxy() {
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
        public Optional<java.net.Authenticator> authenticator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Version version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            throw new UnsupportedOperationException();
        }
    }

    private <T> HttpResponse<T> response(int status, Map<String, List<String>> headers, T body) {
        return new StubResponse<>(status, headers, body);
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

    // A payload-carrying InputStream that records its close()
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
}
