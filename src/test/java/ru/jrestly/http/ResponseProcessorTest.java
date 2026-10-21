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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseProcessorTest {

    record SimpleDto(String message) {}

    interface SampleApi {
        String string();
        Stream<String> streamString();
        Stream<ServerSentEvent> streamSse();
        Stream<TypedServerSentEvent<SimpleDto>> streamTypedSse();
        Stream<SimpleDto> streamDto();
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

    private static final System.Logger SILENT_LOGGER = new System.Logger() {
        @Override public String getName() { return "silent"; }
        @Override public boolean isLoggable(Level level) { return false; }
        @Override public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {}
        @Override public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {}
    };

    private static final System.Logger VERBOSE_LOGGER = new System.Logger() {
        @Override public String getName() { return "verbose"; }
        @Override public boolean isLoggable(Level level) { return true; }
        @Override public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {}
        @Override public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {}
    };

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
    void processStreamTypedEvents() throws IOException {
        String sse = "event: chat\nid: 7\ndata: {\"message\":\"hello\"}\n\ndata: [DONE]\n\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        Type typedSseType = typeOf("streamTypedSse");

        ru.jrestly.codec.PayloadCodec fakeCodec = new ru.jrestly.codec.PayloadCodec() {
            @Override
            public boolean supports(String mediaType) {
                return true;
            }

            @Override
            public byte[] serialize(Object object, String mediaType) {
                return new byte[0];
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T deserialize(byte[] payload, Type targetType, String mediaType) {
                return (T) new SimpleDto("decoded: " + new String(payload, StandardCharsets.UTF_8));
            }
        };

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typedSseType, false, null, null, null, null, false, headers -> fakeCodec, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        try (Stream<TypedServerSentEvent<SimpleDto>> stream = processor.process(wireStream(200, bais, List.of()), System.nanoTime())) {
            List<TypedServerSentEvent<SimpleDto>> events = stream.toList();
            assertEquals(2, events.size());

            TypedServerSentEvent<SimpleDto> e1 = events.get(0);
            assertEquals("7", e1.id());
            assertEquals("chat", e1.event());
            assertNotNull(e1.data());
            assertTrue(e1.data().message().contains("hello"));
            assertFalse(e1.isDone());

            TypedServerSentEvent<SimpleDto> e2 = events.get(1);
            assertEquals("7", e2.id());
            assertNull(e2.data());
            assertEquals("[DONE]", e2.rawData());
            assertTrue(e2.isDone());
        }
    }

    @Test
    void processStreamTypedEventsMissingCodecThrows() {
        String sse = "data: {\"message\":\"hi\"}\n\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        Type typedSseType = typeOf("streamTypedSse");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typedSseType, false, null, null, null, null, false, headers -> null, System.getLogger("test")
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                processor.process(wireStream(200, bais, List.of()), System.nanoTime()));
        assertTrue(ex.getMessage().contains("PayloadCodec is required"));
    }

    @Test
    void processStreamTypedEventsOptionalMissingClosesStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Type typedSseType = typeOf("streamTypedSse");

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typedSseType, true, null, null, null, null, false, headers -> null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<TypedServerSentEvent<SimpleDto>>> result = processor.process(wireStream(404, bais, List.of()), System.nanoTime());
        assertTrue(result.isEmpty());
        assertTrue(closed.get());
    }

    @Test
    void processStreamTypedEventsOptionalPresent() throws IOException {
        String sse = "data: {\"message\":\"ping\"}\n\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        Type typedSseType = typeOf("streamTypedSse");

        ru.jrestly.codec.PayloadCodec fakeCodec = new ru.jrestly.codec.PayloadCodec() {
            @Override
            public boolean supports(String mediaType) {
                return true;
            }

            @Override
            public byte[] serialize(Object object, String mediaType) {
                return new byte[0];
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T deserialize(byte[] payload, Type targetType, String mediaType) {
                return (T) new SimpleDto("ok");
            }
        };

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typedSseType, true, null, null, null, null, false, headers -> fakeCodec, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Optional<Stream<TypedServerSentEvent<SimpleDto>>> result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result.isPresent());
        try (Stream<TypedServerSentEvent<SimpleDto>> stream = result.get()) {
            assertEquals("ok", stream.findFirst().orElseThrow().data().message());
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

    @Test
    void processOnErrorParserDoesNotMatchStatusCode() throws IOException {
        OnErrorParser parser = new OnErrorParser();
        parser.register(400, CustomErrorEx.class);

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, parser, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        // Status 500 is not 400 (onErrorParser doesn't match) and not expected (200), throws UnexpectedStatusException
        assertThrows(UnexpectedStatusException.class, () ->
                processor.process(wireBuffered(500, "server error", List.of()), System.nanoTime()));

        ByteArrayInputStream bais = new ByteArrayInputStream("server error".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnexpectedStatusException.class, () ->
                processor.process(wireStream(500, bais, List.of()), System.nanoTime()));
    }

    @Test
    void processBufferedOnErrorParserWithEmptyAndNullPayload() {
        OnErrorParser parser = new OnErrorParser();
        parser.register(400, CustomErrorEx.class);

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                String.class, false, null, null, parser, List.of(200), false, null, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        // Empty bytes
        WireResponse emptyBody = new WireResponse(400, List.of(), new byte[0]);
        CustomErrorEx ex1 = assertThrows(CustomErrorEx.class, () -> processor.process(emptyBody, System.nanoTime()));
        assertNotNull(ex1);

        // Null bytes
        WireResponse nullBody = new WireResponse(400, List.of(), (byte[]) null);
        CustomErrorEx ex2 = assertThrows(CustomErrorEx.class, () -> processor.process(nullBody, System.nanoTime()));
        assertNotNull(ex2);
    }

    @Test
    void processStreamTypedSseWithSilentLogger() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("data: {\"message\":\"hi\"}\n\n".getBytes(StandardCharsets.UTF_8));
        PayloadCodec fakeCodec = new PayloadCodec() {
            @Override public boolean supports(String mediaType) { return true; }
            @Override public byte[] serialize(Object value, String contentType) { return new byte[0]; }
            @SuppressWarnings("unchecked")
            @Override public <T> T deserialize(byte[] payload, Type targetType, String contentType) { return (T) new SimpleDto("hi"); }
        };

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typeOf("streamTypedSse"), false, null, null, null, List.of(200), false, headers -> fakeCodec, SILENT_LOGGER
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Object result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result instanceof Stream);
        ((Stream<?>) result).close();

        // Verbose logger path (isLoggable == true)
        ResponseProcessor.ResponseConfig verboseConfig = new ResponseProcessor.ResponseConfig(
                typeOf("streamTypedSse"), false, null, null, null, List.of(200), false, headers -> fakeCodec, VERBOSE_LOGGER
        );
        ResponseProcessor verboseProcessor = new ResponseProcessor(verboseConfig);
        ByteArrayInputStream baisVerbose = new ByteArrayInputStream("data: {\"message\":\"verbose\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Object verboseResult = verboseProcessor.process(wireStream(200, baisVerbose, List.of()), System.nanoTime());
        assertTrue(verboseResult instanceof Stream);
        ((Stream<?>) verboseResult).close();
    }

    @Test
    void processStreamDataDirectWithCodecAndOptional() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("data: {\"message\":\"data-direct\"}\n\n".getBytes(StandardCharsets.UTF_8));
        PayloadCodec fakeCodec = new PayloadCodec() {
            @Override public boolean supports(String mediaType) { return true; }
            @Override public byte[] serialize(Object value, String contentType) { return new byte[0]; }
            @SuppressWarnings("unchecked")
            @Override public <T> T deserialize(byte[] payload, Type targetType, String contentType) { return (T) new SimpleDto("data-direct"); }
        };

        ResponseProcessor.ResponseConfig config = new ResponseProcessor.ResponseConfig(
                typeOf("streamDto"), false, null, null, null, List.of(200), false, headers -> fakeCodec, null
        );
        ResponseProcessor processor = new ResponseProcessor(config);

        Object result = processor.process(wireStream(200, bais, List.of()), System.nanoTime());
        assertTrue(result instanceof Stream);
        @SuppressWarnings("unchecked")
        List<SimpleDto> list = ((Stream<SimpleDto>) result).toList();
        assertEquals(1, list.size());
        assertEquals("data-direct", list.get(0).message());

        // Optional 404 missing resource
        ResponseProcessor.ResponseConfig optConfig = new ResponseProcessor.ResponseConfig(
                typeOf("streamDto"), true, null, null, null, List.of(200, 404), false, headers -> fakeCodec, null
        );
        ResponseProcessor optProcessor = new ResponseProcessor(optConfig);
        Optional<?> optResult = optProcessor.process(wireStream(404, new ByteArrayInputStream(new byte[0]), List.of()), System.nanoTime());
        assertTrue(optResult.isEmpty());

        // Optional 200 present
        ByteArrayInputStream bais2 = new ByteArrayInputStream("data: {\"message\":\"opt\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Optional<?> optPresent = optProcessor.process(wireStream(200, bais2, List.of()), System.nanoTime());
        assertTrue(optPresent.isPresent());
        ((Stream<?>) optPresent.get()).close();

        // Silent logger path for direct stream
        ResponseProcessor.ResponseConfig silentConfig = new ResponseProcessor.ResponseConfig(
                typeOf("streamDto"), false, null, null, null, List.of(200), false, headers -> fakeCodec, SILENT_LOGGER
        );
        ResponseProcessor silentProcessor = new ResponseProcessor(silentConfig);
        ByteArrayInputStream baisSilent = new ByteArrayInputStream("data: {\"message\":\"silent\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Object silentResult = silentProcessor.process(wireStream(200, baisSilent, List.of()), System.nanoTime());
        assertTrue(silentResult instanceof Stream);
        ((Stream<?>) silentResult).close();

        // Verbose logger path for direct stream
        ResponseProcessor.ResponseConfig verboseDirectConfig = new ResponseProcessor.ResponseConfig(
                typeOf("streamDto"), false, null, null, null, List.of(200), false, headers -> fakeCodec, VERBOSE_LOGGER
        );
        ResponseProcessor verboseDirectProcessor = new ResponseProcessor(verboseDirectConfig);
        ByteArrayInputStream baisVerboseDirect = new ByteArrayInputStream("data: {\"message\":\"verbose\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Object verboseDirectResult = verboseDirectProcessor.process(wireStream(200, baisVerboseDirect, List.of()), System.nanoTime());
        assertTrue(verboseDirectResult instanceof Stream);
        ((Stream<?>) verboseDirectResult).close();

        // Missing codec throws IllegalStateException
        ResponseProcessor.ResponseConfig noCodecConfig = new ResponseProcessor.ResponseConfig(
                typeOf("streamDto"), false, null, null, null, List.of(200), false, headers -> null, null
        );
        ResponseProcessor noCodecProcessor = new ResponseProcessor(noCodecConfig);
        ByteArrayInputStream bais3 = new ByteArrayInputStream("data: hi\n\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> noCodecProcessor.process(wireStream(200, bais3, List.of()), System.nanoTime()));
    }
}
