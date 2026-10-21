package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import ru.jrestly.codec.PayloadCodec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResponseBody} — the response-body decode primitives
 * extracted from {@code HttpTemplate} (ADR-0066, step 5): charset resolution
 * from {@code Content-Type} (ADR-0056), stream-type classification (ADR-0016),
 * full stream consumption of an error payload (ADR-0015 Aspect 3) and buffered
 * body-to-text extraction. No wire round-trip — the collaborator is stateless
 * and every input is an argument.
 */
class ResponseBodyTest {

    // ------------------------------------------------------------------
    // resolveCharset
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveCharset: parses charset from Content-Type, handles quotes, case-insensitivity, and fallback")
    void resolveCharsetRules() {
        // Explicit standard charsets
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream; charset=utf-8"))));
        assertEquals(StandardCharsets.ISO_8859_1,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/plain; charset=ISO-8859-1"))));
        assertEquals(StandardCharsets.US_ASCII,
                ResponseBody.resolveCharset(List.of(new Header("content-type", "application/x-ndjson; boundary=x; charset=us-ascii"))));

        // Quoted charset parameter
        assertEquals(StandardCharsets.ISO_8859_1,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream; charset=\"ISO-8859-1\""))));

        // Whitespace variations
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream;  charset = UTF-8  "))));

        // Invalid or empty charsets fallback to UTF-8
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream; charset=non-existent-charset-xyz"))));
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream; charset=\"\""))));
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream; charset="))));

        // Absent charset or absent Content-Type header fallback to UTF-8
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/event-stream"))));
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("X-Custom", "123"))));
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of()));
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(null));
    }

    @Test
    @DisplayName("resolveCharset: malformed charset parameters fall back to UTF-8 without throwing")
    void resolveCharsetMalformedParameters() {
        // A parameter without '=' is skipped, and the loop reaches the end
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/plain; charset"))));
        // An unterminated quoted charset (starts with a quote, does not end with one)
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/plain; charset=\"utf-8"))));
        // A lone quote (starts and ends with the same quote, shorter than two chars)
        assertEquals(StandardCharsets.UTF_8,
                ResponseBody.resolveCharset(List.of(new Header("Content-Type", "text/plain; charset=\""))));
    }

    @Test
    @DisplayName("resolveCharset: skips a null-named and a null-valued header, decodes the later Content-Type")
    void resolveCharsetSkipsNullHeaders() {
        List<Header> headers = new java.util.ArrayList<>();
        headers.add(new Header(null, "text/plain; charset=ISO-8859-1"));
        headers.add(new Header("Content-Type", null));
        headers.add(new Header("Content-Type", "text/plain; charset=US-ASCII"));
        assertEquals(StandardCharsets.US_ASCII, ResponseBody.resolveCharset(headers));
    }

    // ------------------------------------------------------------------
    // isStreamType
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isStreamType: recognizes Stream<String> and Stream<ServerSentEvent>, rejects everything else")
    void isStreamTypeMatrix() throws Exception {
        assertTrue(ResponseBody.isStreamType(genericReturn("lines"), String.class));
        assertTrue(ResponseBody.isStreamType(genericReturn("events"), ServerSentEvent.class));
        assertFalse(ResponseBody.isStreamType(genericReturn("lines"), ServerSentEvent.class));
        assertFalse(ResponseBody.isStreamType(genericReturn("integers"), String.class));
        // A parameterized type whose raw type is not Stream
        assertFalse(ResponseBody.isStreamType(genericReturn("strings"), String.class));
        // A plain class is not a ParameterizedType
        assertFalse(ResponseBody.isStreamType(String.class, String.class));
        // A ParameterizedType with the wrong arity never matches
        assertFalse(ResponseBody.isStreamType(wrongArity(Stream.class), String.class));
    }

    // ------------------------------------------------------------------
    // consumePayload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("consumePayload: decodes bytes with the charset and closes the stream")
    void consumePayloadDecodesAndCloses() throws IOException {
        byte[] raw = "héllo".getBytes(StandardCharsets.ISO_8859_1);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream stream = new ByteArrayInputStream(raw) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        assertEquals("héllo", ResponseBody.consumePayload(stream, StandardCharsets.ISO_8859_1));
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("consumePayload: an empty stream is a null payload, as an empty body is")
    void consumePayloadEmptyIsNull() throws IOException {
        assertNull(ResponseBody.consumePayload(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // extractText
    // ------------------------------------------------------------------

    @Test
    @DisplayName("extractText: null response or null body is an empty string")
    void extractTextNullCases() {
        assertEquals("", ResponseBody.extractText(null));
        assertEquals("", ResponseBody.extractText(WireResponse.of(200)));
    }

    @Test
    @DisplayName("extractText: buffered body decoded with the Content-Type charset")
    void extractTextHonorsCharset() {
        byte[] body = "précis".getBytes(StandardCharsets.ISO_8859_1);
        WireResponse response = WireResponse.of(200,
                List.of(new Header("Content-Type", "text/plain; charset=ISO-8859-1")), body);
        assertEquals("précis", ResponseBody.extractText(response));
    }

    // ------------------------------------------------------------------
    // isMissingResource — shared Optional rule (ADR-0023 × ADR-0016)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isMissingResource: 404/304/204 is a missing resource, everything else is not")
    void isMissingResourceMatrix() {
        assertTrue(ResponseBody.isMissingResource(404));
        assertTrue(ResponseBody.isMissingResource(304));
        assertTrue(ResponseBody.isMissingResource(204));
        assertFalse(ResponseBody.isMissingResource(200));
        assertFalse(ResponseBody.isMissingResource(403));
        assertFalse(ResponseBody.isMissingResource(500));
    }

    // ------------------------------------------------------------------
    // lineStream — wire stream → Stream<String> (ADR-0016 Aspect 1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lineStream: decodes lines with the declared charset and closes the wire stream on close")
    void lineStreamDecodesAndClosesWire() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        InputStream wire = closingTrack("héllo\nworld".getBytes(StandardCharsets.ISO_8859_1), wireClosed);
        Stream<String> lineStream = ResponseBody.lineStream(wire, StandardCharsets.ISO_8859_1, null);

        List<String> lines = lineStream.toList();
        assertEquals(List.of("héllo", "world"), lines);
        assertFalse(wireClosed.get());

        lineStream.close();
        assertTrue(wireClosed.get());
    }

    @Test
    @DisplayName("lineStream: an empty wire stream yields an empty artifact, close is still safe")
    void lineStreamEmptyWire() {
        Stream<String> lineStream = ResponseBody.lineStream(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8, null);
        assertEquals(List.of(), lineStream.toList());
        lineStream.close();
    }

    @Test
    @DisplayName("lineStream: a failing wire close is logged, never thrown out of the artifact")
    void lineStreamCloseFailureIsLoggedNotThrown() {
        InputStream wire = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                throw new IOException("boom");
            }
        };
        RecordingLogger logger = new RecordingLogger();
        Stream<String> lineStream = ResponseBody.lineStream(wire, StandardCharsets.UTF_8, logger);
        lineStream.close();
        assertEquals("Failed to close the response stream", logger.lastMessage);
    }

    // ------------------------------------------------------------------
    // eventStream — wire stream → Stream<ServerSentEvent> (ADR-0016 Aspect 5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("eventStream: parses events over the line stream and close propagates to the wire stream")
    void eventStreamParsesAndClosePropagates() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        InputStream wire = closingTrack("data: hi\n\n".getBytes(StandardCharsets.UTF_8), wireClosed);
        Stream<ServerSentEvent> eventStream = ResponseBody.eventStream(wire, StandardCharsets.UTF_8, null);

        List<ServerSentEvent> events = eventStream.toList();
        assertEquals(1, events.size());
        assertEquals("hi", events.get(0).data());
        assertFalse(wireClosed.get());

        // close propagates through the parser: event stream → line stream → wire stream
        eventStream.close();
        assertTrue(wireClosed.get());
    }

    @Test
    @DisplayName("eventStream: the declared charset decodes event data")
    void eventStreamHonorsCharset() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        InputStream wire = closingTrack("data: précis\n\n".getBytes(StandardCharsets.ISO_8859_1), wireClosed);
        Stream<ServerSentEvent> eventStream = ResponseBody.eventStream(wire, StandardCharsets.ISO_8859_1, null);
        assertEquals("précis", eventStream.toList().get(0).data());
        eventStream.close();
    }

    // ------------------------------------------------------------------
    // typedEventStream — wire stream → Stream<TypedServerSentEvent<T>> (ADR-0097)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isTypedEventStream: recognizes Stream<TypedServerSentEvent<T>> and raw Stream<TypedServerSentEvent>, rejects others")
    void isTypedEventStreamMatrix() throws Exception {
        assertTrue(ResponseBody.isTypedEventStream(genericReturn("typedEvents")));
        assertTrue(ResponseBody.isTypedEventStream(genericReturn("rawTypedEvents")));
        assertFalse(ResponseBody.isTypedEventStream(genericReturn("events")));
        assertFalse(ResponseBody.isTypedEventStream(genericReturn("lines")));
        assertFalse(ResponseBody.isTypedEventStream(genericReturn("streamOfLists")));
        assertFalse(ResponseBody.isTypedEventStream(genericReturn("typedEventList")));
        assertFalse(ResponseBody.isTypedEventStream(String.class));
        assertFalse(ResponseBody.isTypedEventStream(wrongArity(Stream.class)));
    }

    @Test
    @DisplayName("extractTypedEventType: extracts payload type T, falls back to Object.class")
    void extractTypedEventTypeMatrix() throws Exception {
        assertEquals(Dto.class, ResponseBody.extractTypedEventType(genericReturn("typedEvents")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(genericReturn("rawTypedEvents")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(genericReturn("events")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(genericReturn("lines")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(genericReturn("streamOfLists")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(genericReturn("strings")));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(String.class));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(wrongArity(Stream.class)));
        assertEquals(Object.class, ResponseBody.extractTypedEventType(wrongSubArity(Stream.class, TypedServerSentEvent.class)));
    }

    @Test
    @DisplayName("typedEventStream: parses and deserializes typed events, close propagates to wire")
    void typedEventStreamDecodesAndClosesWire() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        InputStream wire = closingTrack("data: payload\n\n".getBytes(StandardCharsets.UTF_8), wireClosed);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = new Dto();

        Stream<TypedServerSentEvent<Dto>> stream = ResponseBody.typedEventStream(
                wire, StandardCharsets.UTF_8, codec, Dto.class, null);

        List<TypedServerSentEvent<Dto>> events = stream.toList();
        assertEquals(1, events.size());
        assertSame(codec.result, events.get(0).data());
        assertFalse(wireClosed.get());

        stream.close();
        assertTrue(wireClosed.get());
    }

    // ------------------------------------------------------------------
    // dataStream — wire stream → Stream<T> direct data stream (ADR-0098)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isDataStream: recognizes Stream<T> and raw Stream, rejects String, SSE, and non-streams")
    void isDataStreamMatrix() throws Exception {
        assertTrue(ResponseBody.isDataStream(genericReturn("integers")));
        assertTrue(ResponseBody.isDataStream(genericReturn("streamOfLists")));
        assertTrue(ResponseBody.isDataStream(genericReturn("rawStream")));
        assertFalse(ResponseBody.isDataStream(genericReturn("typedEvents")));
        assertFalse(ResponseBody.isDataStream(genericReturn("rawTypedEvents")));
        assertFalse(ResponseBody.isDataStream(genericReturn("events")));
        assertFalse(ResponseBody.isDataStream(genericReturn("lines")));
        assertFalse(ResponseBody.isDataStream(genericReturn("strings")));
        assertFalse(ResponseBody.isDataStream(genericReturn("typedEventList")));
        assertFalse(ResponseBody.isDataStream(String.class));
        assertFalse(ResponseBody.isDataStream(wrongArity(Stream.class)));
    }

    @Test
    @DisplayName("extractStreamElementType: extracts payload type T, falls back to Object.class")
    void extractStreamElementTypeMatrix() throws Exception {
        assertEquals(Integer.class, ResponseBody.extractStreamElementType(genericReturn("integers")));
        assertEquals(Object.class, ResponseBody.extractStreamElementType(genericReturn("rawStream")));
        assertEquals(Object.class, ResponseBody.extractStreamElementType(genericReturn("strings")));
        assertEquals(Object.class, ResponseBody.extractStreamElementType(String.class));
        assertEquals(Object.class, ResponseBody.extractStreamElementType(wrongArity(Stream.class)));
    }

    @Test
    @DisplayName("isNdjsonContentType: recognizes ndjson, jsonlines, and stream+json, rejects others and null")
    void isNdjsonContentTypeMatrix() {
        assertTrue(ResponseBody.isNdjsonContentType("application/x-ndjson"));
        assertTrue(ResponseBody.isNdjsonContentType("application/jsonlines; charset=utf-8"));
        assertTrue(ResponseBody.isNdjsonContentType("application/json-lines"));
        assertTrue(ResponseBody.isNdjsonContentType("application/stream+json"));
        assertFalse(ResponseBody.isNdjsonContentType("text/event-stream"));
        assertFalse(ResponseBody.isNdjsonContentType("application/json"));
        assertFalse(ResponseBody.isNdjsonContentType(null));
    }

    @Test
    @DisplayName("dataStream: parses SSE events, skips keepalive/ping, stops at [DONE], and close propagates to wire")
    void dataStreamDecodesSseAndStopsOnDone() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        String sseData = """
                :keepalive
                data: {"value": 1}

                event: ping
                data:

                data: {"value": 2}

                data: [DONE]

                data: {"value": 3}
                """;
        InputStream wire = closingTrack(sseData.getBytes(StandardCharsets.UTF_8), wireClosed);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = new Dto();

        Stream<Dto> stream = ResponseBody.dataStream(
                wire, StandardCharsets.UTF_8, codec, Dto.class, "text/event-stream", null);

        List<Dto> items = stream.toList();
        assertEquals(2, items.size());
        assertFalse(wireClosed.get());

        stream.close();
        assertTrue(wireClosed.get());
    }

    @Test
    @DisplayName("dataStream: parses NDJSON lines, ignores blank lines, and close propagates to wire")
    void dataStreamDecodesNdjson() {
        AtomicBoolean wireClosed = new AtomicBoolean();
        String ndjsonData = "{\"value\":1}\n\n{\"value\":2}\n";
        InputStream wire = closingTrack(ndjsonData.getBytes(StandardCharsets.UTF_8), wireClosed);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = new Dto();

        Stream<Dto> stream = ResponseBody.dataStream(
                wire, StandardCharsets.UTF_8, codec, Dto.class, "application/x-ndjson", null);

        List<Dto> items = stream.toList();
        assertEquals(2, items.size());
        assertFalse(wireClosed.get());

        stream.close();
        assertTrue(wireClosed.get());
    }

    // ------------------------------------------------------------------
    // wrapForTrailers — terminal hook (ADR-0059)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wrapForTrailers: no supplier or no holders — the wire stream is passed through untouched")
    void wrapForTrailersPassThroughCases() {
        ByteArrayInputStream wire = new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8));
        assertSame(wire, ResponseBody.wrapForTrailers(wire, null, new ResponseHeaders(), new ResponseHeaders()));
        assertSame(wire, ResponseBody.wrapForTrailers(wire, List::of, null, null));
        assertSame(wire, ResponseBody.wrapForTrailers(wire, null, null, null));
    }

    @Test
    @DisplayName("wrapForTrailers: the hook attaches when both a supplier and a receiver are present")
    void wrapForTrailersAttachesHook() {
        assertInstanceOf(TrailerCompletingInputStream.class, ResponseBody.wrapForTrailers(
                new ByteArrayInputStream(new byte[0]), List::of, new ResponseHeaders(), null));
        assertInstanceOf(TrailerCompletingInputStream.class, ResponseBody.wrapForTrailers(
                new ByteArrayInputStream(new byte[0]), List::of, null, new ResponseHeaders()));
        assertInstanceOf(TrailerCompletingInputStream.class, ResponseBody.wrapForTrailers(
                new ByteArrayInputStream(new byte[0]), List::of, new ResponseHeaders(), new ResponseHeaders()));
    }

    @Test
    @DisplayName("wrapForTrailers: the attached hook populates the per-call holder and capture holder on EOF")
    void wrapForTrailersPopulatesHoldersAtEof() throws IOException {
        ResponseHeaders perCall = new ResponseHeaders();
        ResponseHeaders captured = new ResponseHeaders();
        List<Header> trailers = List.of(new Header("X-Hash", "abc"));
        InputStream hooked = ResponseBody.wrapForTrailers(
                new ByteArrayInputStream("chunk".getBytes(StandardCharsets.UTF_8)),
                () -> trailers, perCall, captured);

        assertEquals("chunk", new String(hooked.readAllBytes(), StandardCharsets.UTF_8));
        hooked.close();

        assertEquals(trailers, perCall.trailers());
        assertEquals(trailers, captured.trailers());
    }

    @Test
    @DisplayName("wrapForTrailers: a holder that is null leaves the sister holder untouched at EOF")
    void wrapForTrailersNullSisterHolderUntouched() throws IOException {
        ResponseHeaders captured = new ResponseHeaders();
        List<Header> trailers = List.of(new Header("X-Hash", "abc"));
        InputStream hooked = ResponseBody.wrapForTrailers(
                new ByteArrayInputStream(new byte[0]), () -> trailers, null, captured);

        hooked.readAllBytes();
        hooked.close();

        assertEquals(trailers, captured.trailers());
    }

    @Test
    @DisplayName("wrapForTrailers: perCallHolder non-null and captured null populates only perCallHolder at EOF")
    void wrapForTrailersCapturedNullHolderUntouched() throws IOException {
        ResponseHeaders perCall = new ResponseHeaders();
        List<Header> trailers = List.of(new Header("X-Hash", "abc"));
        InputStream hooked = ResponseBody.wrapForTrailers(
                new ByteArrayInputStream(new byte[0]), () -> trailers, perCall, null);

        hooked.readAllBytes();
        hooked.close();

        assertEquals(trailers, perCall.trailers());
    }

    // ------------------------------------------------------------------
    // bufferedOptional — the Optional contour of the buffered decode leg (5c)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bufferedOptional: a missing resource or a null/empty body is Optional.empty() with the codec untouched")
    void optionalMissingResourceAndEmptyBody() {
        byte[] body = {1, 0, 2};
        RecordingLogger verbose = new RecordingLogger();
        for (int status : new int[]{404, 304, 204}) {
            assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                    status, body, Dto.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
            assertEquals("No response or empty/status for Optional return.", verbose.lastMessage());

            RecordingLogger quiet = new RecordingLogger(false);
            assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                    status, body, Dto.class, StandardCharsets.UTF_8, "application/json", never(), quiet));
            assertNull(quiet.lastMessage());
        }
        // A null body is a missing resource too
        assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                200, null, Dto.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        // So is an empty body
        assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                200, new byte[0], Dto.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
    }

    @Test
    @DisplayName("bufferedOptional: byte[] returns Optional.of(the same array) with the codec untouched")
    void optionalByteArray() {
        byte[] body = {1, 2, 3};
        RecordingLogger verbose = new RecordingLogger();
        assertEquals(Optional.of(body), ResponseBody.bufferedOptional(
                200, body, byte[].class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        assertEquals("Response size: 3 bytes", verbose.lastMessage());

        RecordingLogger quiet = new RecordingLogger(false);
        assertEquals(Optional.of(body), ResponseBody.bufferedOptional(
                200, body, byte[].class, StandardCharsets.UTF_8, "application/json", never(), quiet));
        assertNull(quiet.lastMessage());
    }

    @Test
    @DisplayName("bufferedOptional: the String return decodes with the payload charset, not UTF-8")
    void optionalStringHonorsPayloadCharset() {
        // "é" in UTF-8 is two bytes; the same bytes in ISO-8859-1 decode to "Ã©"
        byte[] body = {(byte) 0xC3, (byte) 0xA9};
        RecordingLogger verbose = new RecordingLogger();
        assertEquals(Optional.of("Ã©"), ResponseBody.bufferedOptional(
                200, body, String.class, StandardCharsets.ISO_8859_1, "text/plain", never(), verbose));
        assertEquals("Response size: 2 bytes\nÃ©", verbose.lastMessage());

        RecordingLogger quiet = new RecordingLogger(false);
        assertEquals(Optional.of("Ã©"), ResponseBody.bufferedOptional(
                200, body, String.class, StandardCharsets.ISO_8859_1, "text/plain", never(), quiet));
        assertNull(quiet.lastMessage());
    }

    @Test
    @DisplayName("bufferedOptional: Void and void returns are Optional.empty() with the codec untouched")
    void optionalVoidReturn() {
        RecordingLogger verbose = new RecordingLogger();
        byte[] body = {1};
        assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                200, body, Void.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                200, body, void.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
    }

    @Test
    @DisplayName("bufferedOptional: a DTO decodes through the codec, null-safe wrapped into an Optional")
    void optionalDtoDecodesThroughCodec() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        StubPayloadCodec codec = new StubPayloadCodec();
        RecordingLogger quiet = new RecordingLogger(false);

        codec.result = "decoded";
        assertEquals(Optional.of("decoded"), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, quiet));
        assertEquals(1, codec.deserializeCalls);
        assertEquals(Dto.class, codec.deserializeType);
        assertEquals("application/json", codec.deserializeMediaType);

        // A null decode result is Optional.empty(), not IllegalStateException
        codec.result = null;
        assertEquals(Optional.empty(), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, quiet));
        assertEquals(2, codec.deserializeCalls);
    }

    @Test
    @DisplayName("bufferedOptional: the DTO size line reports the wire byte size, prettyPrint receives the payload charset")
    void optionalDtoLogWireByteSizeAndPayloadCharset() {
        byte[] body = {(byte) 0xC3, (byte) 0xA9};
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        RecordingLogger verbose = new RecordingLogger();

        assertEquals(Optional.of("decoded"), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.ISO_8859_1, "application/xml", () -> codec, verbose));
        String message = verbose.lastMessage();
        // The size line is the wire byte size — no preview string is
        // materialized just to count its characters (ADR-0077)
        assertTrue(message.startsWith("Response size: 2 bytes\n"), "not a wire byte size: " + message);
        // prettyPrint receives the payload charset: the same bytes decode to "Ã©"
        assertTrue(message.contains("pretty:application/xml:Ã©"), "prettyPrint got the wrong charset: " + message);
        assertEquals(1, codec.deserializeCalls);
    }

    @Test
    @DisplayName("bufferedOptional: the DTO leg resolves the codec supplier exactly once (ADR-0077)")
    void optionalDtoLegResolvesCodecOnce() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        AtomicInteger resolutions = new AtomicInteger();
        Supplier<PayloadCodec> counting = () -> {
            resolutions.incrementAndGet();
            return codec;
        };

        assertEquals(Optional.of("decoded"), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.UTF_8, "application/json", counting, new RecordingLogger()));
        assertEquals(1, resolutions.get(), "the log preview and the deserialize must share one resolution");

        // a quiet logger must not change the contract: still exactly one resolution
        assertEquals(Optional.of("decoded"), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.UTF_8, "application/json", counting, new RecordingLogger(false)));
        assertEquals(2, resolutions.get());
    }

    @Test
    @DisplayName("bufferedOptional: the DTO size line reports the wire byte size, not a materialized preview (ADR-0077)")
    void optionalDtoSizeLineIsWireByteSize() {
        // one character, three UTF-8 bytes — a materialized preview would count 1
        byte[] body = "中".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, body.length);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        RecordingLogger verbose = new RecordingLogger();

        assertEquals(Optional.of("decoded"), ResponseBody.bufferedOptional(
                200, body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, verbose));
        String message = verbose.lastMessage();
        assertTrue(message.startsWith("Response size: 3 bytes\n"), "not a wire byte size: " + message);
        assertEquals(1, codec.deserializeCalls);
    }

    // ------------------------------------------------------------------
    // bufferedRaw — the raw contour of the buffered decode leg (5c)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bufferedRaw: a null or empty body returns null with the codec untouched")
    void rawEmptyBodyReturnsNull() {
        RecordingLogger verbose = new RecordingLogger();
        assertNull(ResponseBody.bufferedRaw(
                null, Dto.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        assertEquals("No response.", verbose.lastMessage());
        assertNull(ResponseBody.bufferedRaw(
                new byte[0], Dto.class, StandardCharsets.UTF_8, "application/json", never(), verbose));

        RecordingLogger quiet = new RecordingLogger(false);
        assertNull(ResponseBody.bufferedRaw(
                new byte[0], Dto.class, StandardCharsets.UTF_8, "application/json", never(), quiet));
        assertNull(quiet.lastMessage());
    }

    @Test
    @DisplayName("bufferedRaw: byte[] returns the same array, not a copy or wrapper")
    void rawByteArray() {
        byte[] body = {1, 2, 3};
        RecordingLogger verbose = new RecordingLogger();
        assertSame(body, ResponseBody.bufferedRaw(
                body, byte[].class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        assertEquals("Response size: 3 bytes", verbose.lastMessage());

        RecordingLogger quiet = new RecordingLogger(false);
        assertSame(body, ResponseBody.bufferedRaw(
                body, byte[].class, StandardCharsets.UTF_8, "application/json", never(), quiet));
        assertNull(quiet.lastMessage());
    }

    @Test
    @DisplayName("bufferedRaw: the String return decodes with the payload charset, not UTF-8")
    void rawStringHonorsPayloadCharset() {
        byte[] body = {(byte) 0xC3, (byte) 0xA9};
        RecordingLogger verbose = new RecordingLogger();
        assertEquals("Ã©", ResponseBody.bufferedRaw(
                body, String.class, StandardCharsets.ISO_8859_1, "text/plain", never(), verbose));
        assertEquals("Response size: 2 bytes\nÃ©", verbose.lastMessage());

        RecordingLogger quiet = new RecordingLogger(false);
        assertEquals("Ã©", ResponseBody.bufferedRaw(
                body, String.class, StandardCharsets.ISO_8859_1, "text/plain", never(), quiet));
        assertNull(quiet.lastMessage());
    }

    @Test
    @DisplayName("bufferedRaw: Void and void returns are null with the codec untouched")
    void rawVoidReturn() {
        RecordingLogger verbose = new RecordingLogger();
        byte[] body = {1};
        assertNull(ResponseBody.bufferedRaw(
                body, Void.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
        assertNull(ResponseBody.bufferedRaw(
                body, void.class, StandardCharsets.UTF_8, "application/json", never(), verbose));
    }

    @Test
    @DisplayName("bufferedRaw: a DTO decodes through the codec without an Optional wrapper, null passes through")
    void rawDtoDecodesThroughCodec() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        StubPayloadCodec codec = new StubPayloadCodec();
        RecordingLogger quiet = new RecordingLogger(false);

        codec.result = null;
        assertNull(ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, quiet));
        assertEquals(1, codec.deserializeCalls);
        assertEquals(Dto.class, codec.deserializeType);

        codec.result = "decoded";
        assertEquals("decoded", ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, quiet));
        assertEquals(2, codec.deserializeCalls);
    }

    @Test
    @DisplayName("bufferedRaw: the DTO size line reports the wire byte size, prettyPrint receives the payload charset")
    void rawDtoLogWireByteSizeAndPayloadCharset() {
        byte[] body = {(byte) 0xC3, (byte) 0xA9};
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        RecordingLogger verbose = new RecordingLogger();

        assertEquals("decoded", ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.ISO_8859_1, "application/xml", () -> codec, verbose));
        String message = verbose.lastMessage();
        // The size line is the wire byte size — no preview string is
        // materialized just to count its characters (ADR-0077)
        assertTrue(message.startsWith("Response size: 2 bytes\n"), "not a wire byte size: " + message);
        // prettyPrint receives the payload charset: the same bytes decode to "Ã©"
        assertTrue(message.contains("pretty:application/xml:Ã©"), "prettyPrint got the wrong charset: " + message);
        assertEquals(1, codec.deserializeCalls);
    }

    @Test
    @DisplayName("bufferedRaw: the DTO leg resolves the codec supplier exactly once (ADR-0077)")
    void rawDtoLegResolvesCodecOnce() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        AtomicInteger resolutions = new AtomicInteger();
        Supplier<PayloadCodec> counting = () -> {
            resolutions.incrementAndGet();
            return codec;
        };

        assertEquals("decoded", ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.UTF_8, "application/json", counting, new RecordingLogger()));
        assertEquals(1, resolutions.get(), "the log preview and the deserialize must share one resolution");

        // a quiet logger must not change the contract: still exactly one resolution
        assertEquals("decoded", ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.UTF_8, "application/json", counting, new RecordingLogger(false)));
        assertEquals(2, resolutions.get());
    }

    @Test
    @DisplayName("bufferedRaw: the DTO size line reports the wire byte size, not a materialized preview (ADR-0077)")
    void rawDtoSizeLineIsWireByteSize() {
        // one character, three UTF-8 bytes — a materialized preview would count 1
        byte[] body = "中".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, body.length);
        StubPayloadCodec codec = new StubPayloadCodec();
        codec.result = "decoded";
        RecordingLogger verbose = new RecordingLogger();

        assertEquals("decoded", ResponseBody.bufferedRaw(
                body, Dto.class, StandardCharsets.UTF_8, "application/json", () -> codec, verbose));
        String message = verbose.lastMessage();
        assertTrue(message.startsWith("Response size: 3 bytes\n"), "not a wire byte size: " + message);
        assertEquals(1, codec.deserializeCalls);
    }

    @Test
    @DisplayName("buffered legs: byte[], String and Void bindings never resolve the codec (bare-template survival)")
    void rawBindingsNeverResolveCodec() {
        byte[] body = {1};
        for (Type binding : new Type[]{byte[].class, String.class, Void.class, void.class}) {
            assertDoesNotThrow(() -> ResponseBody.bufferedOptional(
                    200, body, binding, StandardCharsets.UTF_8, "application/json", never(), new RecordingLogger(false)));
            assertDoesNotThrow(() -> ResponseBody.bufferedRaw(
                    body, binding, StandardCharsets.UTF_8, "application/json", never(), new RecordingLogger(false)));
        }
    }

    // ------------------------------------------------------------------
    // closeQuietly — tolerant close
    // ------------------------------------------------------------------

    @Test
    @DisplayName("contentTypeOf: Content-Type is picked case-insensitively and null headers/names are skipped")
    void contentTypeOfRules() {
        assertNull(ResponseBody.contentTypeOf(null));
        assertNull(ResponseBody.contentTypeOf(List.of()));
        assertEquals("application/json",
                ResponseBody.contentTypeOf(List.of(new Header("Content-Type", "application/json"))));
        assertEquals("application/json",
                ResponseBody.contentTypeOf(List.of(new Header("content-type", "application/json"))));
        // A response without a Content-Type keeps the codec selection null-aware
        assertNull(ResponseBody.contentTypeOf(List.of(new Header("X-Other", "1"))));

        java.util.ArrayList<Header> dirty = new java.util.ArrayList<>();
        dirty.add(new Header(null, "application/json"));
        dirty.add(new Header("Content-Type", null));
        assertEquals(null, ResponseBody.contentTypeOf(dirty));
    }

    @Test
    @DisplayName("closeQuietly: null stream is a no-op, a failing close is logged and not thrown")
    void closeQuietlyToleratesFailures() throws IOException {
        ResponseBody.closeQuietly(null, null);

        InputStream wire = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                throw new IOException("boom");
            }
        };
        assertDoesNotThrow(() -> ResponseBody.closeQuietly(wire, null));
        assertDoesNotThrow(() -> ResponseBody.closeQuietly(wire, new RecordingLogger()));
    }

    // ------------------------------------------------------------------
    // streamingPayloadLine — log line of a streamed payload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("streamingPayloadLine: Content-Length is picked up case-insensitively, absent means length unknown")
    void streamingPayloadLineMatrix() {
        assertEquals("streaming lines, Content-Length: 42", ResponseBody.streamingPayloadLine("streaming lines",
                List.of(new Header("content-length", "42"))));
        assertEquals("streaming lines (length unknown)", ResponseBody.streamingPayloadLine("streaming lines", List.of()));
        assertEquals("streaming lines (length unknown)", ResponseBody.streamingPayloadLine("streaming lines",
                List.of(new Header("X-Other", "val"))));
        assertEquals("streaming lines (length unknown)", ResponseBody.streamingPayloadLine("streaming lines",
                List.of(new Header("Content-Length", null))));

        List<Header> mixed = new java.util.ArrayList<>();
        mixed.add(new Header(null, "42"));
        mixed.add(new Header("Content-Length", "7"));
        assertEquals("streaming events, Content-Length: 7", ResponseBody.streamingPayloadLine("streaming events", mixed));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static InputStream closingTrack(byte[] data, AtomicBoolean closed) {
        return new ByteArrayInputStream(data) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
    }

    /** A codec supplier that must never be resolved on the raw bindings. */
    private static Supplier<PayloadCodec> never() {
        return () -> {
            throw new AssertionError("the codec must not be resolved on this leg");
        };
    }

    /** A payload type that is none of the raw bindings (byte[], String, Void). */
    private static final class Dto {
    }

    /** A codec stub that records how it is called and can return a null result. */
    private static final class StubPayloadCodec implements PayloadCodec {
        Object result;
        int deserializeCalls;
        Type deserializeType;
        String deserializeMediaType;

        @Override
        public boolean supports(String mediaType) {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            deserializeCalls++;
            deserializeType = type;
            deserializeMediaType = mediaType;
            return (T) result;
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return new byte[0];
        }

        @Override
        public String prettyPrint(byte[] payload, String mediaType, Charset charset) {
            return "pretty:" + mediaType + ":" + new String(payload, charset);
        }
    }

    /** Captures the logged message instead of emitting it — no console noise. */
    private static final class RecordingLogger implements System.Logger {
        private final boolean verbose;
        private String lastMessage;

        RecordingLogger() {
            this(true);
        }

        RecordingLogger(boolean verbose) {
            this.verbose = verbose;
        }

        String lastMessage() {
            return lastMessage;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return verbose;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Object... params) {
            lastMessage = msg;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
            lastMessage = msg;
        }

        public void log(Level level, java.util.ResourceBundle bundle, String msg) {
            lastMessage = msg;
        }
    }

    private interface TypeSource {
        Stream<String> lines();

        Stream<ServerSentEvent> events();

        Stream<TypedServerSentEvent<Dto>> typedEvents();

        @SuppressWarnings("rawtypes")
        Stream<TypedServerSentEvent> rawTypedEvents();

        Stream<Integer> integers();

        Stream<List<String>> streamOfLists();

        List<String> strings();

        List<TypedServerSentEvent<Dto>> typedEventList();

        @SuppressWarnings("rawtypes")
        Stream rawStream();
    }

    private static Type genericReturn(String name) throws Exception {
        Method method = TypeSource.class.getDeclaredMethod(name);
        return method.getGenericReturnType();
    }

    private static ParameterizedType wrongArity(Type raw) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{String.class, String.class};
            }

            @Override
            public Type getRawType() {
                return raw;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    private static ParameterizedType wrongSubArity(Type rawOuter, Type rawInner) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{
                        new ParameterizedType() {
                            @Override
                            public Type[] getActualTypeArguments() {
                                return new Type[]{String.class, Integer.class};
                            }

                            @Override
                            public Type getRawType() {
                                return rawInner;
                            }

                            @Override
                            public Type getOwnerType() {
                                return null;
                            }
                        }
                };
            }

            @Override
            public Type getRawType() {
                return rawOuter;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }
}
