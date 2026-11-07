package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.json.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for payload decode probe in {@link HttpTemplate}.
 */
class HttpTemplateDecodeProbeTest {

    private static final JsonCodec FAILING_CODEC = new JsonCodec() {
        @Override
        public <T> T deserialize(String payload, Type type) {
            throw new UncheckedIOException("unbindable", new IOException("boom"));
        }

        @Override
        public String serialize(Object value) {
            return "{}";
        }

        @Override
        public String prettyPrint(String payload) {
            return payload;
        }
    };

    private static OnErrorParser onErrorParser(int... statuses) {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(Arrays.stream(statuses).boxed().toList());
        parser.setErrorClass(Object.class);
        return parser;
    }

    private static WireResponse response(int status, String body) {
        return new WireResponse(status, List.of(), body == null ? new byte[0] : body.getBytes());
    }

    private HttpTemplate template(JsonCodec codec, Type returnType) {
        HttpTemplate template = new HttpTemplate();
        template.setJsonCodec(codec);
        template.setReturnType(returnType);
        return template;
    }

    @Test
    @DisplayName("an expected-status body that binds: the probe passes (the success path is untouched)")
    void bindableBodyPasses() {
        HttpTemplate template = template(new JsonCodec() {
            @Override
            public <T> T deserialize(String payload, Type type) {
                return null;
            }

            @Override
            public String serialize(Object value) {
                return "{}";
            }

            @Override
            public String prettyPrint(String payload) {
                return payload;
            }
        }, TestPayload.class);

        assertDoesNotThrow(() -> template.probePayloadDecode(response(200, "{\"ok\":true}")));
    }

    @Test
    @DisplayName("an expected-status body the codec cannot bind: the probe throws the codec's own exception")
    void unbindableBodyThrowsTheCodecException() {
        HttpTemplate template = template(FAILING_CODEC, TestPayload.class);

        assertThrows(UncheckedIOException.class, () -> template.probePayloadDecode(response(200, "{not-json")));
    }

    @Test
    @DisplayName("a declared @OnError status: true — it classifies before the payload decode")
    void declaredOnErrorStatusIsOutOfScope() {
        HttpTemplate template = template(FAILING_CODEC, TestPayload.class);
        template.setOnErrorParser(onErrorParser(429));

        assertDoesNotThrow(() -> template.probePayloadDecode(response(429, "{not-json")));
    }

    @Test
    @DisplayName("an unexpected status: true — the status check classifies before the payload decode")
    void unexpectedStatusIsOutOfScope() {
        HttpTemplate template = template(FAILING_CODEC, TestPayload.class);

        assertDoesNotThrow(() -> template.probePayloadDecode(response(500, "{not-json")));
    }

    @Test
    @DisplayName("no body: true — nothing to decode")
    void nullPayloadPasses() {
        HttpTemplate template = template(FAILING_CODEC, TestPayload.class);

        assertDoesNotThrow(() -> template.probePayloadDecode(response(200, null)));
    }

    @Test
    @DisplayName("String return type: true — returned raw, never decoded")
    void stringReturnTypePasses() {
        HttpTemplate template = template(FAILING_CODEC, String.class);

        assertDoesNotThrow(() -> template.probePayloadDecode(response(200, "{not-json")));
    }

    @Test
    @DisplayName("Void/void return types: true — the body is discarded")
    void voidReturnTypesPass() {
        HttpTemplate voidBoxed = template(FAILING_CODEC, Void.class);
        HttpTemplate voidPrimitive = template(FAILING_CODEC, void.class);

        assertDoesNotThrow(() -> voidBoxed.probePayloadDecode(response(200, "{not-json")));
        assertDoesNotThrow(() -> voidPrimitive.probePayloadDecode(response(200, "{not-json")));
    }

    private static final class TestPayload {
    }
}
