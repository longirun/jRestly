package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.fixtures.TestModuleInfo;
import ru.jrestly.json.JsonCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * Unit tests for the synchronous error contract and asynchronous twins
 * of {@code Stream<String>} and {@code Stream<ServerSentEvent>} (ADR-0016, Step 4):
 * <ul>
 *     <li>Synchronous error paths: {@code @OnError} JSON decoding into {@link HandledException},
 *         unexpected statuses into {@link UnexpectedStatusException}, full consumption of the
 *         underlying {@link InputStream}, and stream closure.</li>
 *     <li>Async twins: {@code CompletableFuture<Stream<String>>} and
 *         {@code CompletableFuture<Stream<ServerSentEvent>>} completing with live, readable streams
 *         whose {@code close()} releases the underlying connection.</li>
 *     <li>Async error paths: exceptional completion with {@link HandledException},
 *         {@link UnexpectedStatusException}, or transport {@link RuntimeException}.</li>
 *     <li>Decode probe: stream return types are decode-proof and never touch the JSON codec.</li>
 * </ul>
 */
class HttpTemplateLineAndEventStreamErrorAndAsyncTest {

    private final AtomicInteger sends = new AtomicInteger();
    private final CapturingLogger logger = new CapturingLogger();

    // ------------------------------------------------------------------
    // Synchronous error paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sync @OnError: line stream consumes error payload, decodes error object, closes stream")
    void syncLineStreamOnErrorConsumesAndDecodes() throws Exception {
        String errorJson = "{\"code\":\"RATE_LIMIT\",\"message\":\"too many requests\"}";
        TrackingInputStream stream = new TrackingInputStream(errorJson.getBytes(StandardCharsets.UTF_8));
        StubCodec codec = new StubCodec();
        TestDto errorObject = new TestDto(429L, "RATE_LIMIT", "too many requests");
        codec.result = errorObject;

        HttpTemplate t = createTemplate("linesOnError");
        t.setJsonCodec(codec);
        t.setHttpClient(stubClient(List.of(response(429,
                Map.of("X-Rate-Limit", List.of("0")), stream))));

        HandledException ex = assertThrows(HandledException.class, t::exchange);

        assertSame(errorObject, ex.getDetails());
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getResponseHeaders().stream().anyMatch(h -> "X-Rate-Limit".equalsIgnoreCase(h.name())));
        assertEquals(errorJson, codec.lastPayload);
        assertEquals(1, codec.deserializeCalls);
        assertTrue(stream.isClosed(), "Underlying InputStream must be closed after error consumption");
    }

    @Test
    @DisplayName("sync @OnError with empty body: SSE stream yields null error details, closes stream without decoding")
    void syncEventStreamOnErrorEmptyBodyCarriesNullDetails() throws Exception {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);
        StubCodec codec = new StubCodec();

        HttpTemplate t = createTemplate("eventsOnError");
        t.setJsonCodec(codec);
        t.setHttpClient(stubClient(List.of(response(400, Map.of(), stream))));

        HandledException ex = assertThrows(HandledException.class, t::exchange);

        assertNull(ex.getDetails());
        assertEquals(400, ex.getStatusCode());
        assertEquals(0, codec.deserializeCalls);
        assertTrue(stream.isClosed(), "Empty stream must be closed");
    }

    @Test
    @DisplayName("sync unexpected status: line stream throws UnexpectedStatusException with raw body, closes stream")
    void syncLineStreamUnexpectedStatusCarriesRawBody() throws Exception {
        byte[] payload = "404 Not Found error body".getBytes(StandardCharsets.UTF_8);
        TrackingInputStream stream = new TrackingInputStream(payload);

        HttpTemplate t = createTemplate("lines");
        t.setHttpClient(stubClient(List.of(response(404, Map.of(), stream))));

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, t::exchange);

        assertEquals("404 Not Found error body", ex.getRawBody());
        assertEquals(404, ex.getStatusCode());
        assertTrue(stream.isClosed(), "Underlying InputStream must be closed after raw body extraction");
    }

    @Test
    @DisplayName("sync unexpected status: SSE stream throws UnexpectedStatusException with empty raw body on empty stream")
    void syncEventStreamUnexpectedStatusWithEmptyStream() throws Exception {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);

        HttpTemplate t = createTemplate("events");
        t.setHttpClient(stubClient(List.of(response(500, Map.of(), stream))));

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, t::exchange);

        assertEquals("", ex.getRawBody());
        assertEquals(500, ex.getStatusCode());
        assertTrue(stream.isClosed());
    }

    @Test
    @DisplayName("probePayloadDecode: stream return types are decode-proof without touching the JSON codec")
    void probeTreatsStreamReturnAsDecodeProof() throws Exception {
        StubCodec codec = new StubCodec();
        codec.failure = new IllegalStateException("probe should never touch codec for streams");

        HttpTemplate tLine = createTemplate("lines");
        tLine.setJsonCodec(codec);
        WireResponse wireLine = new WireResponse(200, List.of(), null,
                new TrackingInputStream("data\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(tLine.probePayloadDecode(wireLine));

        HttpTemplate tEvent = createTemplate("events");
        tEvent.setJsonCodec(codec);
        WireResponse wireEvent = new WireResponse(200, List.of(), null,
                new TrackingInputStream("data: hi\n\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(tEvent.probePayloadDecode(wireEvent));

        assertEquals(0, codec.deserializeCalls);
    }

    // ------------------------------------------------------------------
    // Asynchronous success paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("async line stream success: CompletableFuture completes with readable Stream<String>")
    void asyncLineStreamSuccessCompletesWithLiveStream() throws Exception {
        String data = "first line\nsecond line\nthird line";
        TrackingInputStream stream = new TrackingInputStream(data.getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate("linesAsync");
        t.setHttpClient(stubClient(List.of(response(200, Map.of(), stream))));

        CompletableFuture<Stream<String>> future = t.exchange();
        assertNotNull(future);
        Stream<String> lines = future.join();
        assertNotNull(lines);

        assertEquals(List.of("first line", "second line", "third line"), lines.toList());
        assertFalse(stream.isClosed(), "Stream should remain unclosed until explicit close");

        lines.close();
        assertTrue(stream.isClosed(), "Closing Stream<String> must close the underlying InputStream");
    }

    @Test
    @DisplayName("async SSE stream success: CompletableFuture completes with parsed Stream<ServerSentEvent>")
    void asyncEventStreamSuccessCompletesWithParsedEvents() throws Exception {
        String sseData = """
                id: 1
                data: first chunk
                
                id: 2
                event: delta
                data: second chunk
                
                """;
        TrackingInputStream stream = new TrackingInputStream(sseData.getBytes(StandardCharsets.UTF_8));

        HttpTemplate t = createTemplate("eventsAsync");
        t.setHttpClient(stubClient(List.of(response(200,
                Map.of("Content-Type", List.of("text/event-stream; charset=utf-8")), stream))));

        CompletableFuture<Stream<ServerSentEvent>> future = t.exchange();
        assertNotNull(future);
        Stream<ServerSentEvent> events = future.join();
        assertNotNull(events);

        List<ServerSentEvent> eventList = events.toList();
        assertEquals(2, eventList.size());
        assertEquals("first chunk", eventList.get(0).data());
        assertEquals("second chunk", eventList.get(1).data());
        assertEquals("delta", eventList.get(1).event());

        events.close();
        assertTrue(stream.isClosed(), "Closing Stream<ServerSentEvent> must close the underlying InputStream");
    }

    @Test
    @DisplayName("async empty stream: completes with empty streams without hanging or failing")
    void asyncEmptyStreamYieldsEmptyStream() throws Exception {
        TrackingInputStream stream1 = new TrackingInputStream(new byte[0]);
        HttpTemplate t1 = createTemplate("linesAsync");
        t1.setHttpClient(stubClient(List.of(response(200, Map.of(), stream1))));

        CompletableFuture<Stream<String>> f1 = t1.exchange();
        assertEquals(List.of(), f1.join().toList());

        TrackingInputStream stream2 = new TrackingInputStream(new byte[0]);
        HttpTemplate t2 = createTemplate("eventsAsync");
        t2.setHttpClient(stubClient(List.of(response(200, Map.of(), stream2))));

        CompletableFuture<Stream<ServerSentEvent>> f2 = t2.exchange();
        assertEquals(List.of(), f2.join().toList());
    }

    // ------------------------------------------------------------------
    // Asynchronous error paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("async @OnError: future fails with HandledException, stream consumed and closed")
    void asyncOnErrorFailsWithHandledException() throws Exception {
        String errorJson = "{\"code\":\"QUOTA\",\"message\":\"out of credits\"}";
        TrackingInputStream stream = new TrackingInputStream(errorJson.getBytes(StandardCharsets.UTF_8));
        StubCodec codec = new StubCodec();
        TestDto errorObject = new TestDto(429L, "QUOTA", "out of credits");
        codec.result = errorObject;

        HttpTemplate t = createTemplate("linesAsyncOnError");
        t.setJsonCodec(codec);
        t.setHttpClient(stubClient(List.of(response(429,
                Map.of("X-Quota", List.of("exceeded")), stream))));

        CompletableFuture<Stream<String>> future = t.exchange();
        HandledException ex = causeOf(future::join);

        assertSame(errorObject, ex.getDetails());
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getResponseHeaders().stream().anyMatch(h -> "X-Quota".equalsIgnoreCase(h.name())));
        assertEquals(errorJson, codec.lastPayload);
        assertTrue(stream.isClosed(), "Underlying stream must be closed after async error consumption");
    }

    @Test
    @DisplayName("async unexpected status: future fails with UnexpectedStatusException carrying full raw payload")
    void asyncUnexpectedStatusFailsWithRawPayload() throws Exception {
        byte[] payload = "Internal Server Error details".getBytes(StandardCharsets.UTF_8);
        TrackingInputStream stream = new TrackingInputStream(payload);

        HttpTemplate t = createTemplate("eventsAsync");
        t.setHttpClient(stubClient(List.of(response(500, Map.of(), stream))));

        CompletableFuture<Stream<ServerSentEvent>> future = t.exchange();
        UnexpectedStatusException ex = causeOf(future::join);

        assertEquals("Internal Server Error details", ex.getRawBody());
        assertEquals(500, ex.getStatusCode());
        assertTrue(stream.isClosed(), "Underlying stream must be closed after unexpected status");
    }

    @Test
    @DisplayName("async transport failure: future fails with RuntimeException wrapping IOException")
    void asyncTransportFailureWrapsIOException() throws Exception {
        IOException transportError = new IOException("Connection terminated prematurely");

        HttpTemplate t = createTemplate("linesAsync");
        t.setHttpClient(new StubHttpClient(new ArrayDeque<>()) {
            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                return CompletableFuture.failedFuture(transportError);
            }
        });

        CompletableFuture<Stream<String>> future = t.exchange();
        RuntimeException runtime = causeOf(future::join);

        assertSame(transportError, runtime.getCause());
    }

    // ------------------------------------------------------------------
    // Helpers and fixtures
    // ------------------------------------------------------------------

    private HttpTemplate createTemplate(String methodName) throws NoSuchMethodException {
        Method method = findMethod(methodName);
        HttpTemplate t = new HttpTemplateBuilder(
                new TestModuleInfo("http://localhost:1"),
                stubClient(List.of()),
                method, new Object[0], StreamingAsyncErrorController.class).build();
        t.setLogger(logger);
        return t;
    }

    private Method findMethod(String name) throws NoSuchMethodException {
        for (Method m : StreamingAsyncErrorController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private interface StreamingAsyncErrorController {
        @Get("/lines")
        Stream<String> lines();

        @Get("/lines-error")
        @OnError(statuses = {429}, errorObject = TestDto.class)
        Stream<String> linesOnError();

        @Get("/events")
        Stream<ServerSentEvent> events();

        @Get("/events-error")
        @OnError(statuses = {400}, errorObject = TestDto.class)
        Stream<ServerSentEvent> eventsOnError();

        @Get("/lines-async")
        CompletableFuture<Stream<String>> linesAsync();

        @Get("/lines-async-error")
        @OnError(statuses = {429}, errorObject = TestDto.class)
        CompletableFuture<Stream<String>> linesAsyncOnError();

        @Get("/events-async")
        CompletableFuture<Stream<ServerSentEvent>> eventsAsync();

        @Get("/events-async-error")
        @OnError(statuses = {400}, errorObject = TestDto.class)
        CompletableFuture<Stream<ServerSentEvent>> eventsAsyncOnError();
    }

    private static class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        TrackingInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
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

    private class StubHttpClient extends HttpClient {
        private final Deque<HttpResponse<?>> queue;

        StubHttpClient(Deque<HttpResponse<?>> queue) {
            this.queue = queue;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends.incrementAndGet();
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) queue.poll();
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends.incrementAndGet();
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
    }
}
