package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.JsonCodec;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code HttpTemplate.probePayloadDecode} — the
 * side-effect-free decode probe of ADR-0011 Aspect 2: it reproduces the
 * payload-decode branch of {@code processResponse} and nothing else, so
 * the retry loop can ask "would this payload decode" without classifying
 * the response. No WireMock — the seam is package-private on purpose, and
 * the codec is a stub through the public {@code setJsonCodec} setter.
 *
 * <p>The template is kept deliberately bare (no logger installed, no auth
 * consumer set): if the probe ever touches a logger or an auth-capture,
 * one of these tests NPEs — "the probe is side-effect-free" is pinned by
 * the fixture shape, not an assertion.
 */
class HttpTemplateDecodeProbeTest {

    private static final String GOOD_BODY = "{\"id\":7,\"name\":\"ok\",\"description\":\"fine\"}";
    private static final String GARBAGE_BODY = "{\"id\":\"seven\",\"name\":";

    private final StubCodec codec = new StubCodec();
    private final HttpTemplate template = new HttpTemplate();

    HttpTemplateDecodeProbeTest() {
        template.setJsonCodec(codec);
    }

    private static WireResponse response(int status, String body) {
        return new WireResponse(status, List.of(),
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("expected status, decodable payload: true, the codec runs exactly once")
    void expectedStatusThatDecodesIsTrue() {
        template.setReturnType(TestDto.class);
        codec.result = new TestDto(7L, "ok", "fine");

        assertTrue(template.probePayloadDecode(response(200, GOOD_BODY)));
        assertEquals(1, codec.deserializeCalls);
    }

    @Test
    @DisplayName("expected status, unbindable payload: the codec's own exception propagates, same instance, unwrapped")
    void expectedStatusThatDoesNotBindThrowsTheCodecException() {
        template.setReturnType(TestDto.class);
        RuntimeException failure = new IllegalStateException("cannot bind the payload to TestDto");
        codec.failure = failure;

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> template.probePayloadDecode(response(200, GARBAGE_BODY))));
        assertEquals(1, codec.deserializeCalls);
    }

    @Test
    @DisplayName("@OnError status: true without touching the codec (classifies before the payload decode)")
    void onErrorStatusIsTrueWithoutTouchingTheCodec() {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(List.of(429));
        parser.setErrorClass(TestDto.class);
        template.setOnErrorParser(parser);
        template.setReturnType(TestDto.class);

        assertTrue(template.probePayloadDecode(response(429, GARBAGE_BODY)));
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("unexpected status: true without touching the codec (the status check classifies first)")
    void unexpectedStatusIsTrueWithoutTouchingTheCodec() {
        template.setReturnType(TestDto.class);

        assertTrue(template.probePayloadDecode(response(404, GARBAGE_BODY)));
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("no body: true without touching the codec")
    void emptyBodyIsTrueWithoutTouchingTheCodec() {
        template.setReturnType(TestDto.class);

        assertTrue(template.probePayloadDecode(response(200, null)));
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("String return type: the body is returned raw, true without touching the codec")
    void stringReturnTypeIsTrueWithoutTouchingTheCodec() {
        template.setReturnType(String.class);

        assertTrue(template.probePayloadDecode(response(200, GARBAGE_BODY)));
        assertEquals(0, codec.deserializeCalls);
    }

    @Test
    @DisplayName("Void / void return types: the body is discarded, true without touching the codec")
    void voidReturnTypesAreTrueWithoutTouchingTheCodec() {
        template.setReturnType(Void.class);
        assertTrue(template.probePayloadDecode(response(200, GARBAGE_BODY)));
        assertEquals(0, codec.deserializeCalls);

        template.setReturnType(void.class);
        assertTrue(template.probePayloadDecode(response(200, GARBAGE_BODY)));
        assertEquals(0, codec.deserializeCalls);
    }

    /**
     * A stub {@link JsonCodec}: records every deserialize call; returns
     * {@code result} or throws {@code failure} (a test sets exactly one).
     */
    private static final class StubCodec implements JsonCodec {
        int deserializeCalls;
        Object result;
        RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String payload, Type type) {
            deserializeCalls++;
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
}
