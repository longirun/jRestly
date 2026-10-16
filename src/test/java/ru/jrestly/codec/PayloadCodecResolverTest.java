package ru.jrestly.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.json.JsonCodec;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PayloadCodecResolver: ServiceLoader discovery, priority, registry assembly (ADR-0060 phase 3)")
class PayloadCodecResolverTest {

    private static final String TYPE = "application/x-test";

    /** Zero-dep codec identifying itself by a tag, claiming {@link #TYPE}. */
    private record TaggedCodec(String tag) implements PayloadCodec {
        @Override
        public boolean supports(String mediaType) {
            return TYPE.equals(mediaType);
        }

        @Override
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            return null;
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return (tag + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Provider reporting a fixed availability and priority. */
    private record FakeProvider(boolean available, int priority, PayloadCodec codec) implements PayloadCodecProvider {
        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public PayloadCodec createCodec() {
            return codec;
        }
    }

    private static JsonCodec stubJson() {
        return new JsonCodec() {
            @Override
            public <T> T deserialize(String payload, Type type) {
                return null;
            }

            @Override
            public String serialize(Object value) {
                return String.valueOf(value);
            }

            @Override
            public String prettyPrint(String payload) {
                return payload;
            }
        };
    }

    @Test
    @DisplayName("discovery skips unavailable providers")
    void skipsUnavailable() {
        PayloadCodec off = new TaggedCodec("off");
        List<PayloadCodec> discovered = PayloadCodecResolver.discoverFromSpi(List.of(
                new FakeProvider(false, 0, off),
                new FakeProvider(true, 0, new TaggedCodec("on"))));

        assertEquals(1, discovered.size());
        assertInstanceOf(TaggedCodec.class, discovered.get(0));
    }

    @Test
    @DisplayName("discovery orders providers by descending priority, stable on ties")
    void ordersByPriorityDescending() {
        PayloadCodec low = new TaggedCodec("low");
        PayloadCodec firstHigh = new TaggedCodec("high-a");
        PayloadCodec secondHigh = new TaggedCodec("high-b");

        List<PayloadCodec> discovered = PayloadCodecResolver.discoverFromSpi(List.of(
                new FakeProvider(true, 5, firstHigh),
                new FakeProvider(true, 1, low),
                new FakeProvider(true, 5, secondHigh)));

        assertEquals(List.of(firstHigh, secondHigh, low), discovered,
                "higher priority wins; equal priorities keep the given order");
    }

    @Test
    @DisplayName("discovery tolerates null providers and providers yielding a null codec")
    void toleratesNulls() {
        assertTrue(PayloadCodecResolver.discoverFromSpi(null).isEmpty());
        assertTrue(PayloadCodecResolver.discoverFromSpi(List.of(
                new FakeProvider(true, 0, null))).isEmpty());
    }

    @Test
    @DisplayName("registry(json, explicit) puts explicit codecs first, discovered after, JSON as default")
    void registryAssemblesExplicitThenDiscovered() {
        PayloadCodec explicit = new TaggedCodec("explicit");
        CodecRegistry registry = PayloadCodecResolver.registry(stubJson(), List.of(explicit));

        assertSame(explicit, registry.codecs().get(0),
                "explicitly registered codecs take priority over auto-discovered ones");
        assertTrue(registry.resolve("application/json") instanceof JsonCodecAdapter,
                "the JSON codec is the fallback for JSON types");
        assertInstanceOf(JsonCodecAdapter.class, registry.resolveOrDefault("application/unknown"),
                "unknown media types still fall back to JSON (backward compatibility)");
    }

    @Test
    @DisplayName("registry(json) with no explicit codecs still resembles the JSON-only registry")
    void registryWithoutExplicitCodecs() {
        CodecRegistry registry = PayloadCodecResolver.registry(stubJson());
        assertEquals(1, registry.codecs().size());
        assertInstanceOf(JsonCodecAdapter.class, registry.defaultCodec());
    }
}
