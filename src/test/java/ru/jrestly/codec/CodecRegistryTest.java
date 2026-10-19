package ru.jrestly.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.json.JsonCodec;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CodecRegistry} (ADR-0060): media-type resolution
 * including {@code +suffix} structured types, normalization,
 * first-registration-wins ordering, default fallback and the
 * {@code JsonCodecAdapter} bridge. Pure unit tests.
 */
@DisplayName("CodecRegistry: media-type selection, structured suffixes, order, default fallback (ADR-0060)")
class CodecRegistryTest {

    /** Minimal zero-dep codec claiming a fixed base media type and its +suffix. */
    private record FakeCodec(String base) implements PayloadCodec {
        @Override
        public boolean supports(String mediaType) {
            return mediaType.equals(base) || mediaType.endsWith("+" + base.substring(base.indexOf('/') + 1));
        }

        @Override
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            return null;
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return ("x:" + value).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static CodecRegistry jsonDefault() {
        return CodecRegistry.of(stubJson());
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
    @DisplayName("of(jsonCodec) claims JSON and serves as the default for unknown types")
    void jsonOfClaimsJsonAndDefaults() {
        CodecRegistry registry = jsonDefault();
        assertTrue(registry.resolve("application/json") instanceof JsonCodecAdapter);
        assertTrue(registry.resolve("application/vnd.company.v1+json") instanceof JsonCodecAdapter,
                "structured +json suffix must be claimed (RFC 6839)");
        assertNull(registry.resolve("application/xml"), "no XML codec is registered");
        assertTrue(registry.resolveOrDefault("application/xml") instanceof JsonCodecAdapter,
                "unknown response media type falls back to JSON (backward compatibility)");
    }

    @Test
    @DisplayName("normalization strips parameters, trims, and lower-cases before matching")
    void normalizationApplied() {
        CodecRegistry registry = CodecRegistry.builder()
                .codec(new FakeCodec("application/xml"))
                .defaultCodec(new JsonCodecAdapter(stubJson()))
                .build();

        assertNotNull(registry.resolve("Application/XML; charset=UTF-8"));
        assertNotNull(registry.resolve("  application/xml  "));
        assertNull(registry.resolve("   "));
        assertNull(registry.resolve(null));
        assertEquals(new JsonCodecAdapter(stubJson()).getClass(),
                registry.resolveOrDefault(null).getClass());
    }

    @Test
    @DisplayName("registration order is priority when two codecs claim the same type")
    void firstRegistrationWins() {
        PayloadCodec first = new FakeCodec("application/xml");
        PayloadCodec second = new FakeCodec("application/xml");
        CodecRegistry registry = CodecRegistry.builder()
                .codec(first)
                .codec(second)
                .defaultCodec(new JsonCodecAdapter(stubJson()))
                .build();

        assertSame(first, registry.resolve("application/xml"));
        assertEquals(List.of(first, second), registry.codecs().subList(0, 2));
    }

    @Test
    @DisplayName("the default codec is appended to the resolution order so its own supports() applies")
    void defaultCodecIsResolvable() {
        JsonCodecAdapter json = new JsonCodecAdapter(stubJson());
        CodecRegistry registry = CodecRegistry.builder().defaultCodec(json).build();
        assertSame(json, registry.resolve("application/json"));
        assertSame(json, registry.defaultCodec());
    }

    @Test
    @DisplayName("a null default codec leaves unknown types unresolved (no crash)")
    void noDefaultIsTolerated() {
        CodecRegistry registry = CodecRegistry.builder().codec(new FakeCodec("application/xml")).build();
        assertNull(registry.resolve("application/json"));
        assertNull(registry.resolveOrDefault("application/json"));
        assertNull(registry.defaultCodec());
    }

    @Test
    @DisplayName("JsonCodecAdapter delegates serialize/deserialize/prettyPrint to the wrapped codec")
    void adapterDelegates() {
        JsonCodec json = stubJson();
        JsonCodecAdapter adapter = new JsonCodecAdapter(json);
        assertSame(json, adapter.jsonCodec());
        assertArrayEquals("42".getBytes(StandardCharsets.UTF_8), adapter.serialize(42, "application/json"));
        assertNull(adapter.deserialize("null".getBytes(StandardCharsets.UTF_8), Integer.class, "application/json"));
        assertEquals("{\"a\":1}", adapter.prettyPrint("{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                "application/json", StandardCharsets.UTF_8));
        assertNull(adapter.prettyPrint(null, "application/json", StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("JsonCodecAdapter rejects a null codec")
    void adapterRequiresCodec() {
        assertThrows(NullPointerException.class, () -> new JsonCodecAdapter(null));
    }

    @Test
    @DisplayName("PayloadCodec default prettyPrint decodes text with specified charset or UTF-8 fallback")
    void payloadCodecDefaultPrettyPrint() {
        PayloadCodec codec = new FakeCodec("application/xml");
        assertNull(codec.prettyPrint(null, "application/xml", null));
        assertEquals("<tag/>", codec.prettyPrint("<tag/>".getBytes(StandardCharsets.UTF_8), "application/xml", null));
        assertEquals("<tag/>", codec.prettyPrint("<tag/>".getBytes(StandardCharsets.ISO_8859_1), "application/xml", StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("a null codec registration is ignored (documented builder contract)")
    void nullCodecRegistrationIsIgnored() {
        CodecRegistry registry = CodecRegistry.builder()
                .codec(null)
                .codec(new FakeCodec("application/xml"))
                .build();
        assertEquals(1, registry.codecs().size(), "null codec must not enter the registry");
        assertNotNull(registry.resolve("application/xml"), "the real codec still resolves");
    }

    @Test
    @DisplayName("a default codec already registered explicitly is not duplicated in the resolution order")
    void defaultCodecAlreadyRegisteredIsNotDuplicated() {
        JsonCodecAdapter json = new JsonCodecAdapter(stubJson());
        CodecRegistry registry = CodecRegistry.builder()
                .codec(json)
                .defaultCodec(json)
                .build();
        assertEquals(1, registry.codecs().size(), "the default codec must not appear twice");
        assertSame(json, registry.resolve("application/json"));
        assertSame(json, registry.defaultCodec());
    }

    @Test
    @DisplayName("text/json is claimed by the adapter alongside application/json")
    void adapterClaimsTextJson() {
        CodecRegistry registry = jsonDefault();
        assertTrue(registry.resolve("text/json") instanceof JsonCodecAdapter,
                "text/json is a documented claim of the JSON bridge");
    }

    @Test
    @DisplayName("JsonCodecAdapter prettyPrint: empty payload short-circuits; null charset falls back to UTF-8")
    void adapterPrettyPrintEdgeCases() {
        JsonCodec marker = new JsonCodec() {
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
                return "MARKER";
            }
        };
        JsonCodecAdapter withMarker = new JsonCodecAdapter(marker);
        assertEquals("", withMarker.prettyPrint(new byte[0], "application/json", null),
                "empty payload short-circuits before the wrapped codec");

        JsonCodecAdapter adapter = new JsonCodecAdapter(stubJson());
        byte[] latin1 = "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(new String(latin1, StandardCharsets.UTF_8),
                adapter.prettyPrint(latin1, "application/json", null),
                "a null charset falls back to UTF-8");
    }

    @Test
    @DisplayName("PayloadCodecProvider default priority is zero")
    void payloadCodecProviderDefaultPriority() {
        PayloadCodecProvider provider = new PayloadCodecProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public PayloadCodec createCodec() {
                return new FakeCodec("application/custom");
            }
        };
        assertEquals(0, provider.priority());
    }
}
