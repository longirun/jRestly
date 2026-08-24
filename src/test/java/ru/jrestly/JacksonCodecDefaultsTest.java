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
 * Unit-level pins for {@link JacksonCodec#defaults()} and the explicit-mapper constructor.
 * <p>
 * The jsr310-free classpath scenario (Step 4 fix: jsr310 types isolated in the nested
 * JavaTimeSupport class) is verified separately via a fresh Gradle project smoke test and
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
    @DisplayName("defaults() truncates nanoseconds: the pattern has second precision in both directions")
    void defaultsTruncateNanoseconds() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321);

        String json = codec.serialize(withNanos);
        assertEquals("\"2026-08-24 12:34:56\"", json,
                "nanos must not be emitted by the yyyy-MM-dd HH:mm:ss pattern");

        LocalDateTime back = codec.deserialize(json, LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 34, 56), back,
                "nanos are lost by design: second precision");
    }

    @Test
    @DisplayName("defaults() round-trips a DTO with LocalDateTime; ISO 'T' input is rejected")
    void defaultsRoundTripDtoAndRejectIsoSeparator() {
        EventDto event = new EventDto(7L, LocalDateTime.of(2026, 8, 24, 12, 34, 56));

        String json = codec.serialize(event);
        assertEquals("{\"id\":7,\"createdAt\":\"2026-08-24 12:34:56\"}", json);

        EventDto back = codec.deserialize(json, EventDto.class);
        assertEquals(event, back);

        // The custom pattern is really wired in (not the ISO default of JavaTimeModule):
        // the ISO 'T' separator must not parse
        assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("{\"id\":7,\"createdAt\":\"2026-08-24T12:34:56\"}", EventDto.class));
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

    private record Person(String firstName, String lastName) {}
}
