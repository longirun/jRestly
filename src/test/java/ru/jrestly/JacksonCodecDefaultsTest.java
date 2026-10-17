package ru.jrestly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.EventDto;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.JacksonCodec;

import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JacksonCodec#defaults()} and the explicit-mapper constructor.
 * <p>
 * The jsr310-free classpath scenario (jsr310 types isolated in the nested
 * JavaTimeSupport class) is verified separately via an isolated smoke test and
 * cannot be reproduced here: the test classpath always carries jackson-datatype-jsr310.
 */
class JacksonCodecDefaultsTest {

    private final JacksonCodec codec = JacksonCodec.defaults();

    @Test
    @DisplayName("defaults() serializes LocalDateTime as yyyy-MM-dd HH:mm:ss: no ISO 'T', no nanos")
    void defaultsSerializeLocalDateTimeWithConfiguredPattern() {
        LocalDateTime value = LocalDateTime.of(2026, 8, 24, 12, 34, 56);

        String json = codec.serialize(value);

        assertEquals("\"2026-08-24 12:34:56\"", json);
        assertFalse(json.contains("T"), "the pattern must not use the ISO 'T' separator");
    }

    @Test
    @DisplayName("defaults() preserves sub-second precision: fractional tail is emitted only when present")
    void defaultsPreserveFractionalSecondsWhenPresent() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321);

        String json = codec.serialize(withNanos);
        assertEquals("\"2026-08-24 12:34:56.987654321\"", json,
                "nanos must not be silently truncated on the wire");

        LocalDateTime back = codec.deserialize(json, LocalDateTime.class);
        assertEquals(withNanos, back, "full round-trip at nanosecond precision");

        // Sub-millisecond values are trimmed to significant digits, no trailing zeros
        LocalDateTime millis = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 1_000_000);
        assertEquals("\"2026-08-24 12:34:56.001\"", codec.serialize(millis));
    }

    @Test
    @DisplayName("defaults() round-trips a DTO with LocalDateTime; the space separator is the wire default")
    void defaultsRoundTripDtoWithSpaceSeparator() {
        EventDto event = new EventDto(7L, LocalDateTime.of(2026, 8, 24, 12, 34, 56));

        String json = codec.serialize(event);
        assertEquals("{\"id\":7,\"createdAt\":\"2026-08-24 12:34:56\"}", json);

        EventDto back = codec.deserialize(json, EventDto.class);
        assertEquals(event, back);

        // The custom wire format is really wired in (not the ISO default of JavaTimeModule):
        // a bare date has no time part and must not parse
        assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("{\"id\":7,\"createdAt\":\"2026-08-24\"}", EventDto.class));
    }

    @Test
    @DisplayName("defaults() deserializes LocalDateTime with ISO 'T' separator and fractional seconds")
    void defaultsDeserializeIsoSeparatorWithFractionalSeconds() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000);

        assertEquals(expected, codec.deserialize("\"2026-10-08T09:25:53.123456\"", LocalDateTime.class),
                "ISO-8601 with 'T' and fractional digits must parse");
        assertEquals(expected, codec.deserialize("\"2026-10-08 09:25:53.123456\"", LocalDateTime.class),
                "classic space separator with fractional digits must parse");
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53),
                codec.deserialize("\"2026-10-08T09:25:53\"", LocalDateTime.class),
                "ISO-8601 without fractional seconds must parse");
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53),
                codec.deserialize("\"2026-10-08 09:25:53\"", LocalDateTime.class),
                "classic format without fractional seconds must parse");
    }

    @Test
    @DisplayName("defaults() deserializes a DTO whose server sent ISO-8601 with fractional seconds")
    void defaultsDeserializeDtoWithIsoFractionalTimestamp() {
        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":\"2026-10-08T09:25:53.123456\"}", EventDto.class);

        assertEquals(7L, back.id());
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000), back.createdAt());
    }

    @Test
    @DisplayName("defaults() still rejects shapes outside the wire contract (bare date, lowercase 't', missing seconds)")
    void defaultsRejectShapesOutsideWireContract() {
        assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("\"2026-10-08\"", LocalDateTime.class),
                "a bare date has no time part");
        assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("\"2026-10-08t09:25:53\"", LocalDateTime.class),
                "lowercase 't' is not an accepted separator");
        assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("\"2026-10-08 09:25\"", LocalDateTime.class),
                "seconds are required");
    }

    @Test
    @DisplayName("defaults(ISO_8601) serializes LocalDateTime with the ISO 'T' separator; the default stays classic")
    void isoDefaultsSerializeWithTSeparator() {
        JacksonCodec iso = JacksonCodec.defaults(JacksonCodec.LocalDateTimeFormat.ISO_8601);
        LocalDateTime value = LocalDateTime.of(2026, 8, 24, 12, 34, 56);

        assertEquals("\"2026-08-24T12:34:56\"", iso.serialize(value),
                "ISO mode must print the 'T' separator");
        assertEquals("\"2026-08-24 12:34:56\"", codec.serialize(value),
                "the no-arg default must remain byte-compatible with the classic format");
    }

    @Test
    @DisplayName("defaults(ISO_8601) preserves sub-second precision: fractional tail is emitted only when present")
    void isoDefaultsPreserveFractionalSeconds() {
        JacksonCodec iso = JacksonCodec.defaults(JacksonCodec.LocalDateTimeFormat.ISO_8601);

        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321);
        assertEquals("\"2026-08-24T12:34:56.987654321\"", iso.serialize(withNanos),
                "nanos must not be silently truncated on the wire");
        assertEquals(withNanos, iso.deserialize("\"2026-08-24T12:34:56.987654321\"", LocalDateTime.class),
                "full round-trip at nanosecond precision");

        // Sub-millisecond values are trimmed to significant digits, no trailing zeros
        LocalDateTime millis = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 1_000_000);
        assertEquals("\"2026-08-24T12:34:56.001\"", iso.serialize(millis));
    }

    @Test
    @DisplayName("defaults(ISO_8601) still deserializes the classic space separator (lenient parser is unchanged)")
    void isoDefaultsStillParseClassicSpaceSeparator() {
        JacksonCodec iso = JacksonCodec.defaults(JacksonCodec.LocalDateTimeFormat.ISO_8601);
        LocalDateTime expected = LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000);

        assertEquals(expected, iso.deserialize("\"2026-10-08 09:25:53.123456\"", LocalDateTime.class),
                "legacy space-separator payloads must keep parsing in ISO mode");
        assertEquals(expected, iso.deserialize("\"2026-10-08T09:25:53.123456\"", LocalDateTime.class));
    }

    @Test
    @DisplayName("defaults(SPACE) is byte-identical to defaults()")
    void spaceDefaultsAreByteIdenticalToDefault() {
        JacksonCodec space = JacksonCodec.defaults(JacksonCodec.LocalDateTimeFormat.SPACE);

        for (LocalDateTime value : new LocalDateTime[] {
                LocalDateTime.of(2026, 8, 24, 12, 34, 56),
                LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321),
                LocalDateTime.of(2026, 8, 24, 12, 34, 56, 1_000_000)}) {
            assertEquals(codec.serialize(value), space.serialize(value));
        }
    }

    @Test
    @DisplayName("defaults(format) fails fast on a null format")
    void defaultsFailFastOnNullFormat() {
        assertThrows(NullPointerException.class, () -> JacksonCodec.defaults(null));
    }

    @Test
    @DisplayName("null handling: serialize(null) is 'null', deserialize('null') is null (JsonCodec contract)")
    void nullHandling() {
        assertEquals("null", codec.serialize(null));
        assertNull(codec.deserialize("null", TestDto.class));
    }

    @Test
    @DisplayName("defaults() ignores unknown JSON properties (FAIL_ON_UNKNOWN_PROPERTIES disabled)")
    void defaultsIgnoreUnknownProperties() {
        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":\"2026-08-24 12:34:56\",\"extraField\":\"ignored\"}",
                EventDto.class);

        assertEquals(7L, back.id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 34, 56), back.createdAt());
    }

    @Test
    @DisplayName("explicit ObjectMapper is used as is: snake_case round-trip via the constructor")
    void explicitMapperUsedAsIs() {
        ObjectMapper snakeCase = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JacksonCodec customCodec = new JacksonCodec(snakeCase);

        String json = customCodec.serialize(new Person("Ada", "Lovelace"));
        assertEquals("{\"first_name\":\"Ada\",\"last_name\":\"Lovelace\"}", json);

        Person back = customCodec.deserialize(json, Person.class);
        assertEquals(new Person("Ada", "Lovelace"), back);
    }

    @Test
    @DisplayName("explicit ObjectMapper is not reconfigured: strict unknown-property handling is preserved")
    void explicitMapperIsNotReconfigured() {
        // A plain ObjectMapper fails on unknown properties, while defaults() disables that.
        // If the codec reconfigured the provided mapper, this deserialization would succeed.
        JacksonCodec customCodec = new JacksonCodec(new ObjectMapper());

        assertThrows(UncheckedIOException.class, () -> customCodec.deserialize(
                "{\"id\":1,\"name\":\"x\",\"description\":\"d\",\"unknownField\":true}",
                TestDto.class));
    }

    @Test
    @DisplayName("malformed JSON fails with UncheckedIOException naming the target type")
    void malformedJsonFailsWithTypeInMessage() {
        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("{not json", EventDto.class));

        assertTrue(ex.getMessage().contains("Failed to deserialize"));
        assertTrue(ex.getMessage().contains(EventDto.class.getTypeName()));
    }

    @Test
    @DisplayName("deserialize(Type) supports parameterized types (controller List<T> return path)")
    void deserializeSupportsParameterizedTypes() {
        String json = "[{\"id\":1,\"createdAt\":\"2026-08-24 12:34:56\"},"
                + "{\"id\":2,\"createdAt\":\"2026-08-24 12:35:56\"}]";

        List<EventDto> events = codec.deserialize(json, new TypeReference<List<EventDto>>() {}.getType());

        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 35, 56), events.get(1).createdAt());
    }

    @Test
    @DisplayName("deserializeBytes() parses raw byte array without intermediate string allocation")
    void deserializeBytesParsesRawBytes() {
        byte[] bytes = "{\"id\":42,\"name\":\"Alice\",\"description\":\"Engineer\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        TestDto dto = codec.deserializeBytes(bytes, TestDto.class);

        assertNotNull(dto);
        assertEquals(42L, dto.id());
        assertEquals("Alice", dto.name());
        assertEquals("Engineer", dto.description());

        assertNull(codec.deserializeBytes(null, TestDto.class));
        assertNull(codec.deserializeBytes(new byte[0], TestDto.class));
        assertThrows(UncheckedIOException.class, () ->
                codec.deserializeBytes("{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8), TestDto.class));
    }

    @Test
    @DisplayName("serializeToBytes() serializes directly to raw byte array")
    void serializeToBytesSerializesDirectly() {
        TestDto dto = new TestDto(42L, "Bob", "Tester");

        byte[] bytes = codec.serializeToBytes(dto);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        TestDto roundtrip = codec.deserializeBytes(bytes, TestDto.class);
        assertEquals(dto, roundtrip);

        assertArrayEquals("null".getBytes(java.nio.charset.StandardCharsets.UTF_8), codec.serializeToBytes(null));

        class FailingDto {
            public String getFails() {
                throw new IllegalStateException("failing serializer");
            }
        }

        assertThrows(UncheckedIOException.class, () -> codec.serialize(new FailingDto()));
        assertThrows(UncheckedIOException.class, () -> codec.serializeToBytes(new FailingDto()));
    }

    private record Person(String firstName, String lastName) {}
}

