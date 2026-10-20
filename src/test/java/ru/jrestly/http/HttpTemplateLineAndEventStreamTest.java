package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.fixtures.TestModuleInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the line and SSE streaming wire and engine seams (ADR-0016, Step 3):
 * <ul>
 *     <li>Builder detection of {@code Stream<String>} and {@code Stream<ServerSentEvent>}
 *         (and their async twins), turning {@code streamBody} on.</li>
 *     <li>The resolved {@code Content-Type} charset driving line/event decoding
 *         (the charset matrix itself lives in {@code ResponseBodyTest}).</li>
 *     <li>{@code HttpTemplate.processResponse} returning live {@code Stream<String>} and
 *         {@code Stream<ServerSentEvent>}.</li>
 *     <li>Stream closing lifecycle: closing the downstream {@code Stream} closes the
 *         underlying network {@link InputStream}.</li>
 *     <li>Streaming payload logging for lines and events without body pre-buffering.</li>
 * </ul>
 */
class HttpTemplateLineAndEventStreamTest {

    private final CapturingLogger logger = new CapturingLogger();

    // ------------------------------------------------------------------
    // Builder detection tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builder: Stream<String> and CompletableFuture<Stream<String>> turn streamBody on")
    void builderDetectsLineStreamAndAsyncTwin() throws Exception {
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamLines")));
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamLinesAsync")));
    }

    @Test
    @DisplayName("builder: Stream<ServerSentEvent> and CompletableFuture<Stream<ServerSentEvent>> turn streamBody on")
    void builderDetectsEventStreamAndAsyncTwin() throws Exception {
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamEvents")));
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamEventsAsync")));
    }

    @Test
    @DisplayName("builder: Stream<TypedServerSentEvent<T>> and CompletableFuture<Stream<TypedServerSentEvent<T>>> turn streamBody on")
    void builderDetectsTypedEventStreamAndAsyncTwin() throws Exception {
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamTypedEvents")));
        assertTrue(detectsStream(StreamingTestController.class.getMethod("streamTypedEventsAsync")));
    }

    @Test
    @DisplayName("builder: unsupported stream types or non-stream types leave streamBody off")
    void builderLeavesStreamBodyOffForOtherTypes() throws Exception {
        assertFalse(detectsStream(StreamingTestController.class.getMethod("streamIntegers")));
        assertFalse(detectsStream(StreamingTestController.class.getMethod("streamRaw")));
        assertFalse(detectsStream(StreamingTestController.class.getMethod("listStrings")));
        assertFalse(detectsStream(StreamingTestController.class.getMethod("stringResponse")));
    }

    // ------------------------------------------------------------------
    // Line streaming tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("line streaming: exchange returns Stream<String> decoding lines with the resolved charset")
    void lineStreamingYieldsLinesWithCorrectCharset() throws Exception {
        String data = "line 1\nline 2: привет\nline 3";
        TrackingInputStream stream = new TrackingInputStream(data.getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate(StreamingTestController.class.getMethod("streamLines"));
        t.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Type", List.of("text/plain; charset=utf-8")), stream))));

        Stream<String> lines = t.exchange();
        assertNotNull(lines);
        List<String> list = lines.toList();

        assertEquals(List.of("line 1", "line 2: привет", "line 3"), list);
        assertFalse(stream.isClosed(), "Stream should remain unclosed while reading");
    }

    @Test
    @DisplayName("line streaming onClose: closing the stream closes the underlying InputStream")
    void lineStreamingClosesUnderlyingStreamOnClose() throws Exception {
        TrackingInputStream stream = new TrackingInputStream("data\n".getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate(StreamingTestController.class.getMethod("streamLines"));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Stream<String> lines = t.exchange();
        assertFalse(stream.isClosed());

        lines.close();
        assertTrue(stream.isClosed(), "Closing Stream<String> must close the underlying InputStream");
    }

    @Test
    @DisplayName("line streaming: payload log line reflects lines streaming with or without Content-Length")
    void lineStreamingLogsStreamingLinesWithAndWithoutContentLength() throws Exception {
        TrackingInputStream stream1 = new TrackingInputStream("test\n".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t1 = createTemplate(StreamingTestController.class.getMethod("streamLines"));
        t1.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Length", List.of("5")), stream1))));
        t1.exchange();
        assertEquals("streaming lines, Content-Length: 5", logger.last());

        TrackingInputStream stream2 = new TrackingInputStream("test\n".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t2 = createTemplate(StreamingTestController.class.getMethod("streamLines"));
        t2.setHttpClient(stubClient(List.of(response(200, Map.of(), stream2))));
        t2.exchange();
        assertEquals("streaming lines (length unknown)", logger.last());
    }

    // ------------------------------------------------------------------
    // Event streaming (SSE) tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("event streaming: exchange returns Stream<ServerSentEvent> yielding parsed events")
    void eventStreamingYieldsParsedEvents() throws Exception {
        String ssePayload = """
                id: 101
                event: greeting
                data: hello world
                
                : ping comment
                
                data: token delta
                
                """;
        TrackingInputStream stream = new TrackingInputStream(ssePayload.getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate(StreamingTestController.class.getMethod("streamEvents"));
        t.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Type", List.of("text/event-stream; charset=utf-8")), stream))));

        Stream<ServerSentEvent> events = t.exchange();
        assertNotNull(events);
        List<ServerSentEvent> eventList = events.toList();

        assertEquals(2, eventList.size());
        assertEquals(new ServerSentEvent("101", "greeting", "hello world", null), eventList.get(0));
        // Last-event-ID is stream-level state per WHATWG: it persists into the next event.
        assertEquals(new ServerSentEvent("101", null, "token delta", null), eventList.get(1));
    }

    @Test
    @DisplayName("event streaming onClose: closing the event stream closes the underlying InputStream")
    void eventStreamingClosesUnderlyingStreamOnClose() throws Exception {
        TrackingInputStream stream = new TrackingInputStream("data: test\n\n".getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate(StreamingTestController.class.getMethod("streamEvents"));
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        Stream<ServerSentEvent> events = t.exchange();
        assertFalse(stream.isClosed());

        events.close();
        assertTrue(stream.isClosed(), "Closing Stream<ServerSentEvent> must close the underlying InputStream");
    }

    @Test
    @DisplayName("event streaming: payload log line reflects events streaming with or without Content-Length")
    void eventStreamingLogsStreamingEventsWithAndWithoutContentLength() throws Exception {
        TrackingInputStream stream1 = new TrackingInputStream("data: 1\n\n".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t1 = createTemplate(StreamingTestController.class.getMethod("streamEvents"));
        t1.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Length", List.of("10")), stream1))));
        t1.exchange();
        assertEquals("streaming events, Content-Length: 10", logger.last());

        TrackingInputStream stream2 = new TrackingInputStream("data: 1\n\n".getBytes(StandardCharsets.UTF_8));
        HttpTemplate t2 = createTemplate(StreamingTestController.class.getMethod("streamEvents"));
        t2.setHttpClient(stubClient(List.of(response(200, Map.of(), stream2))));
        t2.exchange();
        assertEquals("streaming events (length unknown)", logger.last());
    }

    // ------------------------------------------------------------------
    // Helpers and Fixtures
    // ------------------------------------------------------------------

    private boolean detectsStream(Method method) {
        HttpTemplate t = new HttpTemplateBuilder(
                new TestModuleInfo("http://localhost:1"),
                stubClient(List.of()),
                method, new Object[0], StreamingTestController.class).build();
        return t.streamBody();
    }

    private HttpTemplate createTemplate(Method method) {
        HttpTemplate t = new HttpTemplateBuilder(
                new TestModuleInfo("http://localhost:1"),
                stubClient(List.of()),
                method, new Object[0], StreamingTestController.class).build();
        t.setLogger(logger);
        return t;
    }

    private interface StreamingTestController {
        @Get("/lines")
        Stream<String> streamLines();

        @Get("/lines-async")
        CompletableFuture<Stream<String>> streamLinesAsync();

        @Get("/events")
        Stream<ServerSentEvent> streamEvents();

        @Get("/events-async")
        CompletableFuture<Stream<ServerSentEvent>> streamEventsAsync();

        @Get("/typed-events")
        Stream<TypedServerSentEvent<String>> streamTypedEvents();

        @Get("/typed-events-async")
        CompletableFuture<Stream<TypedServerSentEvent<String>>> streamTypedEventsAsync();

        @Get("/integers")
        Stream<Integer> streamIntegers();

        @SuppressWarnings("rawtypes")
        @Get("/stream-raw")
        Stream streamRaw();

        @Get("/list")
        List<String> listStrings();

        @Get("/string")
        String stringResponse();
    }

    private static class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        TrackingInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws java.io.IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws java.io.IOException {
            closed.set(true);
            delegate.close();
        }

        boolean isClosed() {
            return closed.get();
        }
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
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Redirect followRedirects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
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

    private record StubResponse<T>(int status, Map<String, List<String>> rawHeaders, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpHeaders headers() {
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
            return URI.create("http://localhost:1/api/test");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class CapturingLogger implements System.Logger {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "capturing-logger";
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
