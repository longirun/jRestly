package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    // fixtures
    // ------------------------------------------------------------------

    private interface TypeSource {
        Stream<String> lines();

        Stream<ServerSentEvent> events();

        Stream<Integer> integers();

        List<String> strings();
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
}
