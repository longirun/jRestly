package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTrailersTest {

    interface StreamingClient {

        @Get(path = "/stream/bytes")
        InputStream streamBytes(ResponseHeaders headers);

        @Get(path = "/stream/lines")
        Stream<String> streamLines(ResponseHeaders headers);

        @Get(path = "/stream/events")
        Stream<ServerSentEvent> streamEvents(ResponseHeaders headers);

        @Get(path = "/stream/capture-lines")
        Stream<String> captureLines();

        @Get(path = "/stream/async-lines")
        CompletableFuture<Stream<String>> asyncLines(ResponseHeaders headers);
    }

    /**
     * Synthetic HttpEngine returning a WireResponse with live stream and a trailers supplier.
     */
    static class MockStreamingEngine implements HttpEngine {

        private final AtomicInteger trailersRequested = new AtomicInteger();

        @Override
        public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException {
            boolean isEvents = request.uri().getPath().contains("/events");
            byte[] payload = isEvents
                    ? "data: e1\n\ndata: e2\n\n".getBytes(StandardCharsets.UTF_8)
                    : "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8);
            InputStream stream = new ByteArrayInputStream(payload);

            return new WireResponse(
                    200,
                    List.of(new Header("Trailer", "Server-Timing, X-Checksum"), new Header("Content-Type", isEvents ? "text/event-stream" : "text/plain")),
                    null,
                    stream,
                    HttpClient.Version.HTTP_1_1,
                    List.of(),
                    () -> {
                        trailersRequested.incrementAndGet();
                        return List.of(
                                new Header("Server-Timing", "app;dur=12.5"),
                                new Header("X-Checksum", "deadbeef")
                        );
                    }
            );
        }

        @Override
        public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
            try {
                return CompletableFuture.completedFuture(send(request, options));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("InputStream return: reading to EOF populates ResponseHeaders trailers")
    void inputStreamDrainedPopulatesTrailers() throws IOException {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        ResponseHeaders headers = new ResponseHeaders();
        try (InputStream in = client.streamBytes(headers)) {
            // Before reading to completion, trailers are empty
            assertTrue(headers.trailers().isEmpty());
            byte[] bytes = in.readAllBytes();
            assertEquals("first line\nsecond line\n", new String(bytes, StandardCharsets.UTF_8));
        }

        // Draining stream triggered the terminal hook
        assertEquals(1, engine.trailersRequested.get());
        assertEquals(Optional.of("app;dur=12.5"), headers.firstTrailer("Server-Timing"));
        assertEquals(Optional.of("deadbeef"), headers.firstTrailer("X-Checksum"));
    }

    @Test
    @DisplayName("InputStream return: closing before EOF triggers trailers completion once")
    void inputStreamEarlyClosePopulatesTrailers() throws IOException {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        ResponseHeaders headers = new ResponseHeaders();
        InputStream in = client.streamBytes(headers);
        assertTrue(headers.trailers().isEmpty());

        // Close prematurely
        in.close();

        // Trailers supplier was requested exactly once
        assertEquals(1, engine.trailersRequested.get());
        assertEquals(Optional.of("app;dur=12.5"), headers.firstTrailer("Server-Timing"));

        // Repeated close is idempotent
        in.close();
        assertEquals(1, engine.trailersRequested.get());
    }

    @Test
    @DisplayName("Stream<String> return: consuming lines populates ResponseHeaders trailers")
    void streamLinesPopulatesTrailers() {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        ResponseHeaders headers = new ResponseHeaders();
        List<String> lines;
        try (Stream<String> stream = client.streamLines(headers)) {
            lines = stream.toList();
        }

        assertEquals(List.of("first line", "second line"), lines);
        assertEquals(1, engine.trailersRequested.get());
        assertEquals(Optional.of("app;dur=12.5"), headers.firstTrailer("Server-Timing"));
    }

    @Test
    @DisplayName("Stream<ServerSentEvent> return: consuming events populates ResponseHeaders trailers")
    void streamEventsPopulatesTrailers() {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        ResponseHeaders headers = new ResponseHeaders();
        List<ServerSentEvent> events;
        try (Stream<ServerSentEvent> stream = client.streamEvents(headers)) {
            events = stream.toList();
        }

        assertEquals(2, events.size());
        assertEquals(1, engine.trailersRequested.get());
        assertEquals(Optional.of("app;dur=12.5"), headers.firstTrailer("Server-Timing"));
        assertEquals(Optional.of("deadbeef"), headers.firstTrailer("X-Checksum"));
    }

    @Test
    @DisplayName("ResponseHeaders.capture captures streaming trailers after stream completion")
    void captureWithStreamLinesPopulatesTrailers() {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        CapturedResponse<Stream<String>> captured = ResponseHeaders.capture(client::captureLines);
        // Initially trailers are empty
        assertTrue(captured.headers().trailers().isEmpty());

        List<String> lines;
        try (Stream<String> stream = captured.result()) {
            lines = stream.toList();
        }

        assertEquals(List.of("first line", "second line"), lines);
        assertEquals(Optional.of("app;dur=12.5"), captured.headers().firstTrailer("Server-Timing"));
        assertEquals(Optional.of("deadbeef"), captured.headers().firstTrailer("X-Checksum"));
    }

    @Test
    @DisplayName("CompletableFuture<Stream<String>> async return: trailers populated on drain")
    void asyncStreamLinesPopulatesTrailers() {
        MockStreamingEngine engine = new MockStreamingEngine();
        StreamingClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .httpEngine(engine)
                .build()
                .get(StreamingClient.class);

        ResponseHeaders headers = new ResponseHeaders();
        CompletableFuture<Stream<String>> future = client.asyncLines(headers);
        Stream<String> stream = future.join();

        assertTrue(headers.trailers().isEmpty());
        List<String> lines = stream.toList();

        assertEquals(List.of("first line", "second line"), lines);
        assertEquals(Optional.of("app;dur=12.5"), headers.firstTrailer("Server-Timing"));
    }
}
