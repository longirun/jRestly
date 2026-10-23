package ru.jrestly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.EventDto;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.JacksonCodec;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JacksonCodecDefaultsTest {

    private final JacksonCodec codec = JacksonCodec.defaults();

    @Test
    @DisplayName("defaults() serializes java.time types according to ISO-8601 standard")
    void defaultsSerializeIso8601() {
        LocalDateTime ldt = LocalDateTime.of(2026, 8, 24, 12, 34, 56);
        assertEquals("\"2026-08-24T12:34:56\"", codec.serialize(ldt));

        LocalDate ld = LocalDate.of(2026, 8, 24);
        assertEquals("\"2026-08-24\"", codec.serialize(ld));

        Instant instant = Instant.parse("2026-08-24T12:34:56Z");
        assertEquals("\"2026-08-24T12:34:56Z\"", codec.serialize(instant));
    }

    @Test
    @DisplayName("defaults() preserves sub-second fractional precision on serialization and deserialization")
    void defaultsPreserveSubSecondPrecision() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321);

        String json = codec.serialize(withNanos);
        assertEquals("\"2026-08-24T12:34:56.987654321\"", json);

        LocalDateTime back = codec.deserialize(json, LocalDateTime.class);
        assertEquals(withNanos, back);
    }

    @Test
    @DisplayName("defaults() deserializes standard ISO-8601 with fractional seconds (TODO.md field bug)")
    void defaultsDeserializeIsoFractionalTimestamp() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000);

        assertEquals(expected, codec.deserialize("\"2026-10-08T09:25:53.123456\"", LocalDateTime.class));
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53),
                codec.deserialize("\"2026-10-08T09:25:53\"", LocalDateTime.class));

        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":\"2026-10-08T09:25:53.123456\"}", EventDto.class);
        assertEquals(7L, back.id());
        assertEquals(expected, back.createdAt());
    }

    @Test
    @DisplayName("custom date format is configured via custom ObjectMapper passed to JacksonCodec")
    void customDateFormatViaCustomObjectMapper() {
        DateTimeFormatter customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        JavaTimeModule customTimeModule = new JavaTimeModule();
        customTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(customFormatter));

        ObjectMapper customMapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .registerModule(customTimeModule);

        JacksonCodec customCodec = new JacksonCodec(customMapper);

        LocalDateTime value = LocalDateTime.of(2026, 8, 24, 12, 34, 56);
        assertEquals("\"2026-08-24 12:34:56\"", customCodec.serialize(value));
    }

    @Test
    @DisplayName("defaults() ignores unknown JSON properties (FAIL_ON_UNKNOWN_PROPERTIES disabled)")
    void defaultsIgnoreUnknownProperties() {
        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":\"2026-08-24T12:34:56\",\"extraField\":\"ignored\"}",
                EventDto.class);

        assertEquals(7L, back.id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 34, 56), back.createdAt());
    }

    @Test
    @DisplayName("explicit ObjectMapper is used as is: snake_case round-trip via constructor")
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
        JacksonCodec customCodec = new JacksonCodec(new ObjectMapper());

        assertThrows(UncheckedIOException.class, () -> customCodec.deserialize(
                "{\"id\":1,\"name\":\"x\",\"description\":\"d\",\"unknownField\":true}",
                TestDto.class));
    }

    @Test
    @DisplayName("malformed JSON fails with UncheckedIOException naming target type")
    void malformedJsonFailsWithTypeInMessage() {
        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> codec.deserialize("{not json", EventDto.class));

        assertTrue(ex.getMessage().contains("Failed to deserialize"));
        assertTrue(ex.getMessage().contains(EventDto.class.getTypeName()));
    }

    @Test
    @DisplayName("deserialize(Type) supports parameterized types (controller List<T> return path)")
    void deserializeSupportsParameterizedTypes() {
        String json = "[{\"id\":1,\"createdAt\":\"2026-08-24T12:34:56\"},"
                + "{\"id\":2,\"createdAt\":\"2026-08-24T12:35:56\"}]";

        List<EventDto> events = codec.deserialize(json, new TypeReference<List<EventDto>>() {}.getType());

        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 35, 56), events.get(1).createdAt());
    }

    private record Person(String firstName, String lastName) {}
}
