package ru.jrestly.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.PayloadCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseProcessorTest {

    interface SampleApi {
        String string();
        Stream<String> streamString();
        Stream<ServerSentEvent> streamSse();
    }

    private static Type typeOf(String methodName) {
        try {
            return SampleApi.class.getMethod(methodName).getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        ResponseHeaders.CURRENT_CAPTOR.remove();
    }

    private static WireResponse wireBuffered(int status, String body, List<Header> headers) {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return new WireResponse(status, headers != null ? headers : List.of(), bytes);
    }

    private static WireResponse wireStream(int status, InputStream stream, List<Header> headers) {
        return new WireResponse(status, headers != null ? headers : List.of(), null, stream);
    }

    @Test
    void configAllowsNullReturnType() {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                null, false, null, null, null, null, false, null, null
        );
        assertNotNull(config);
        assertNull(config.returnType());
    }

    @Test
    void processBufferedRawString() throws IOException {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        String result = processor.process(wireBuffered(200, "hello world", List.of(new Header("Content-Type", "text/plain"))), System.nanoTime());
        assertEquals("hello world", result);
    }

    @Test
    void processBufferedRawBytes() throws IOException {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                byte[].class, false, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        byte[] result = processor.process(wireBuffered(200, "bytes payload", List.of()), System.nanoTime());
        assertArrayEquals("bytes payload".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void processBufferedOptionalPresentAndMissing() throws IOException {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<String> present = processor.process(wireBuffered(200, "data", List.of()), System.nanoTime());
        assertTrue(present.isPresent());
        assertEquals("data", present.get());

        Optional<String> missing404 = processor.process(wireBuffered(404, "not found", List.of()), System.nanoTime());
        assertTrue(missing404.isEmpty());

        Optional<String> missing204 = processor.process(wireBuffered(204, "", List.of()), System.nanoTime());
        assertTrue(missing204.isEmpty());
    }

    @Test
    void processNotifiesAuthDetailsAndHeaderHolderAndCaptor() throws IOException {
        ResponseHeaders holder = new ResponseHeaders();
        AtomicReference<List<Header>> authRef = new AtomicReference<>();
        AtomicReference<ResponseHeaders> captorRef = new AtomicReference<>();
        ResponseHeaders.CURRENT_CAPTOR.set(captorRef::set);

        List<Header> headers = List.of(new Header("X-Auth", "token-123"));
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, holder, authRef::set, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        long start = System.nanoTime() - 1_000_000; // 1ms ago
        processor.process(wireBuffered(200, "ok", headers), start);

        assertEquals(200, holder.statusCode());
        assertEquals(headers, holder.headers());
        assertEquals("token-123", holder.firstValue("X-Auth").orElse(null));
        assertNotNull(holder.duration());

        assertEquals(headers, authRef.get());

        assertNotNull(captorRef.get());
        assertEquals(200, captorRef.get().statusCode());
        assertEquals(headers, captorRef.get().headers());
    }

    @Test
    void processStreamLines() throws IOException {
        String lines = "line 1\nline 2\nline 3\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8));
        Type streamStringType = typeOf("streamString");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                streamStringType, false, null, null, null, null, false, null, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        try (Stream<String> stream = processor.process(wireStream(200, bais, List.of()), System.nanoTime())) {
            List<String> collected = stream.toList();
            assertEquals(List.of("line 1", "line 2", "line 3"), collected);
        }
    }

    @Test
    void processStreamLinesOptionalMissingClosesStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("error".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Type streamStringType = typeOf("streamString");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                streamStringType, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<String>> result = processor.process(wireStream(404, bais, List.of()), System.nanoTime());
        assertTrue(result.isEmpty());
        assertTrue(closed.get());
    }

    @Test
    void processStreamLinesOptionalPresent() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("line 1\n".getBytes(StandardCharsets.UTF_8));
        Type streamStringType = typeOf("streamString");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                streamStringType, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<String>> result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result.isPresent());
        try (Stream<String> s = result.get()) {
            assertEquals(List.of("line 1"), s.toList());
        }
    }

    @Test
    void processStreamEvents() throws IOException {
        String sse = "event: greet\ndata: hello\n\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        Type sseType = typeOf("streamSse");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                sseType, false, null, null, null, null, false, null, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        try (Stream<ServerSentEvent> stream = processor.process(wireStream(200, bais, List.of()), System.nanoTime())) {
            List<ServerSentEvent> events = stream.toList();
            assertEquals(1, events.size());
            assertEquals("greet", events.get(0).event());
            assertEquals("hello", events.get(0).data());
        }
    }

    @Test
    void processStreamEventsOptionalMissingClosesStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Type sseType = typeOf("streamSse");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                sseType, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<ServerSentEvent>> result = processor.process(wireStream(404, bais, List.of()), System.nanoTime());
        assertTrue(result.isEmpty());
        assertTrue(closed.get());
    }

    @Test
    void processStreamEventsOptionalPresent() throws IOException {
        String sse = "data: ping\n\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        Type sseType = typeOf("streamSse");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                sseType, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<ServerSentEvent>> result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result.isPresent());
        try (Stream<ServerSentEvent> stream = result.get()) {
            assertEquals("ping", stream.findFirst().orElseThrow().data());
        }
    }

    @Test
    void processRawInputStreamOptionalMissingClosesStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                InputStream.class, true, null, null, null, null, false, null, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<InputStream> result = processor.process(wireStream(404, bais, List.of()), System.nanoTime());
        assertTrue(result.isEmpty());
        assertTrue(closed.get());
    }

    @Test
    void processRawInputStreamOptionalPresent() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("raw bytes".getBytes(StandardCharsets.UTF_8));

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                InputStream.class, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<InputStream> result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result.isPresent());
        assertEquals("raw bytes", new String(result.get().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void processRawInputStreamNonOptional() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("raw bytes".getBytes(StandardCharsets.UTF_8));

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                InputStream.class, false, null, null, null, null, false, null, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        InputStream stream = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertNotNull(stream);
        assertEquals("raw bytes", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void processBufferedUnexpectedStatusThrowsException() {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, null, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        ClientErrorException ex = assertThrows(ClientErrorException.class, () ->
                processor.process(wireBuffered(400, "{\"error\":\"bad\"}", List.of()), System.nanoTime()));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void processStreamUnexpectedStatusThrowsException() {
        ByteArrayInputStream bais = new ByteArrayInputStream("{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8));
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, null, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        ClientErrorException ex = assertThrows(ClientErrorException.class, () ->
                processor.process(wireStream(400, bais, List.of()), System.nanoTime()));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void processCodecResolverExceptionSwallowedInUnexpectedStatus() {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, null, List.of(200), false,
                headers -> { throw new RuntimeException("boom"); }, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        ClientErrorException ex = assertThrows(ClientErrorException.class, () ->
                processor.process(wireBuffered(400, "error", List.of()), System.nanoTime()));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void processBufferedEmptyOrNullBytesOnUnexpectedStatus() {
        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, null, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        ClientErrorException exNull = assertThrows(ClientErrorException.class, () ->
                processor.process(wireBuffered(400, null, List.of()), System.nanoTime()));
        assertEquals(400, exNull.getStatusCode());

        ClientErrorException exEmpty = assertThrows(ClientErrorException.class, () ->
                processor.process(wireBuffered(400, "", List.of()), System.nanoTime()));
        assertEquals(400, exEmpty.getStatusCode());
    }

    @SuppressWarnings("serial")
    public static class CustomErrorEx extends RuntimeException {
        public CustomErrorEx(String msg) {
            super(msg);
        }
    }

    @Test
    void processBufferedOnErrorParserMatches() {
        OnErrorParser parser = new OnErrorParser();
        parser.register(400, CustomErrorEx.class);

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, parser, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        CustomErrorEx ex = assertThrows(CustomErrorEx.class, () ->
                processor.process(wireBuffered(400, "{\"msg\":\"custom\"}", List.of()), System.nanoTime()));
        assertNotNull(ex);
    }

    @Test
    void processStreamOnErrorParserMatches() {
        OnErrorParser parser = new OnErrorParser();
        parser.register(400, CustomErrorEx.class);
        ByteArrayInputStream bais = new ByteArrayInputStream("{\"msg\":\"custom\"}".getBytes(StandardCharsets.UTF_8));

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, parser, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        CustomErrorEx ex = assertThrows(CustomErrorEx.class, () ->
                processor.process(wireStream(400, bais, List.of()), System.nanoTime()));
        assertNotNull(ex);
    }

    @Test
    void processRawInputStreamOptionalNonInputStreamReturnsStream() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                Object.class, true, null, null, null, null, false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Object result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertEquals(bais, result);
    }
}
