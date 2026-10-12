package ru.jrestly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.EventDto;
import ru.jrestly.json.GsonCodec;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GsonCodec#defaults()} and the explicit-Gson constructor.
 * <p>
 * The Gson-only classpath scenario (no Jackson) cannot be reproduced here:
 * the test classpath always carries jackson-databind, and the resolver's
 * probe chain hands Jackson first hit. Codec-level behavior is pinned here,
 * resolution priority is pinned in {@code JsonCodecResolverTest}.
 * <p>
 * Pinned against Gson 2.14.0: {@code java.time} values use Gson's frozen
 * reflective-style object representation (e.g.
 * {@code {"date":{"year":2026,"month":8,"day":24},"time":{"hour":12,"minute":34,
 * "second":56,"nano":0}}}), not ISO-8601; {@code java.util.Date} uses the
 * locale-formatted default style.
 */
class GsonCodecTest {

    private final GsonCodec codec = GsonCodec.defaults();

    @Test
    @DisplayName("defaults() serializes LocalDateTime in Gson's frozen java.time format: nested date/time objects, not ISO")
    void defaultsSerializeLocalDateTimeInFrozenJavaTimeFormat() {
        String json = codec.serialize(LocalDateTime.of(2026, 8, 24, 12, 34, 56));

        assertEquals("{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":0}}", json,
                "Gson 2.14 froze the pre-2.14 reflective java.time representation, it is not ISO-8601");
    }

    @Test
    @DisplayName("defaults() preserves sub-second precision in the java.time 'nano' field, never truncated")
    void defaultsPreserveNanosInJavaTimeFormat() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 987_654_321);
        assertEquals("{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":987654321}}",
                codec.serialize(withNanos), "nanos must not be silently truncated on the wire");
        assertEquals(withNanos, codec.deserialize(codec.serialize(withNanos), LocalDateTime.class),
                "full round-trip at nanosecond precision");

        // Sub-second precision is an integer nano field: 1 ms = 1000000, no fractional tail
        LocalDateTime millis = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 1_000_000);
        assertEquals("{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":1000000}}",
                codec.serialize(millis));
    }

    @Test
    @DisplayName("defaults() round-trips a DTO with LocalDateTime in the frozen java.time format")
    void defaultsRoundTripDto() {
        EventDto event = new EventDto(7L, LocalDateTime.of(2026, 8, 24, 12, 34, 56));

        String json = codec.serialize(event);
        assertEquals("{\"id\":7,\"createdAt\":{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":0}}}", json);

        EventDto back = codec.deserialize(json, EventDto.class);
        assertEquals(event, back);
    }

    @Test
    @DisplayName("defaults() deserializes the frozen java.time object form; an ISO-8601 string is not accepted")
    void defaultsDeserializeLocalDateTimeObjectFormOnly() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000);

        assertEquals(expected, codec.deserialize(
                "{\"date\":{\"year\":2026,\"month\":10,\"day\":8},"
                + "\"time\":{\"hour\":9,\"minute\":25,\"second\":53,\"nano\":123456000}}",
                LocalDateTime.class),
                "the frozen object form with nano precision must parse");
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53),
                codec.deserialize(
                "{\"date\":{\"year\":2026,\"month\":10,\"day\":8},"
                + "\"time\":{\"hour\":9,\"minute\":25,\"second\":53}}",
                LocalDateTime.class),
                "a missing nano field is defaulted to zero");
        assertThrows(JsonSyntaxException.class,
                () -> codec.deserialize("\"2026-10-08T09:25:53\"", LocalDateTime.class),
                "Gson's java.time format is an object, not an ISO string");
    }

    @Test
    @DisplayName("defaults() deserializes a DTO whose server sent the frozen java.time form with nano precision")
    void defaultsDeserializeDtoWithJavaTimeFractionalTimestamp() {
        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":{\"date\":{\"year\":2026,\"month\":10,\"day\":8},"
                + "\"time\":{\"hour\":9,\"minute\":25,\"second\":53,\"nano\":123456000}}}", EventDto.class);

        assertEquals(7L, back.id());
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 25, 53, 123_456_000), back.createdAt());
    }

    @Test
    @DisplayName("defaults() ignores unknown JSON properties (Gson's native leniency)")
    void defaultsIgnoreUnknownProperties() {
        EventDto back = codec.deserialize(
                "{\"id\":7,\"createdAt\":{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":0}},\"extraField\":\"ignored\"}",
                EventDto.class);

        assertEquals(7L, back.id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 34, 56), back.createdAt());
    }

    @Test
    @DisplayName("defaults() serializes java.util.Date as a locale-formatted string (Gson's native date semantics, documented not fought)")
    void defaultsSerializeDateAsLocaleString() {
        // Whole-second precision: Gson's default date format has no sub-second
        // component, a fractional tail would be lost on the round-trip
        Date date = new Date(1234567890000L);

        String json = codec.serialize(date);
        assertTrue(json.startsWith("\"") && json.endsWith("\""), "dates serialize as JSON strings");

        assertEquals(date, codec.deserialize(json, Date.class),
                "the locale-formatted string must round-trip to the same instant");
    }

    @Test
    @DisplayName("null handling: serialize(null) is 'null', deserialize('null') is null")
    void nullHandling() {
        assertEquals("null", codec.serialize(null));
        assertNull(codec.deserialize("null", EventDto.class));
    }

    @Test
    @DisplayName("explicit Gson is used as is: snake_case naming policy round-trips via the constructor")
    void explicitGsonUsedAsIs() {
        Gson snakeCase = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
        GsonCodec customCodec = new GsonCodec(snakeCase);

        String json = customCodec.serialize(new Person("Ada", "Lovelace"));
        assertEquals("{\"first_name\":\"Ada\",\"last_name\":\"Lovelace\"}", json);

        Person back = customCodec.deserialize(json, Person.class);
        assertEquals(new Person("Ada", "Lovelace"), back);
    }

    @Test
    @DisplayName("malformed JSON fails with JsonSyntaxException naming the target type, Gson's cause preserved")
    void malformedJsonFailsWithTypeInMessage() {
        JsonSyntaxException ex = assertThrows(JsonSyntaxException.class,
                () -> codec.deserialize("{not json", EventDto.class));

        assertTrue(ex.getMessage().contains("Failed to deserialize"));
        assertTrue(ex.getMessage().contains(EventDto.class.getTypeName()));
        assertNotNull(ex.getCause(), "the original Gson exception must stay the cause");
    }

    @Test
    @DisplayName("deserialize(Type) supports parameterized types (controller List<T> return path)")
    void deserializeSupportsParameterizedTypes() {
        String json = "[{\"id\":1,\"createdAt\":{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":34,\"second\":56,\"nano\":0}}},"
                + "{\"id\":2,\"createdAt\":{\"date\":{\"year\":2026,\"month\":8,\"day\":24},"
                + "\"time\":{\"hour\":12,\"minute\":35,\"second\":56,\"nano\":0}}}]";

        List<EventDto> events = codec.deserialize(json, new TypeToken<List<EventDto>>() {}.getType());

        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).id());
        assertEquals(LocalDateTime.of(2026, 8, 24, 12, 35, 56), events.get(1).createdAt());
    }

    @Test
    @DisplayName("prettyPrint formats valid JSON with newlines and indentation; invalid input is returned as is")
    void prettyPrintFormatsValidJsonAndReturnsInvalidInputAsIs() {
        String pretty = codec.prettyPrint("{\"name\":\"value\",\"nested\":{\"a\":1}}");
        assertTrue(pretty.contains("\n"), "pretty-printed JSON must contain newlines");
        assertTrue(pretty.contains("  \"name\""), "pretty-printed JSON must be indented");
        assertNotEquals("{\"name\":\"value\",\"nested\":{\"a\":1}}", pretty,
                "pretty-printed JSON must differ from the compact input");

        String invalid = "not a json {{{";
        assertEquals(invalid, codec.prettyPrint(invalid), "invalid JSON must be returned unchanged");
    }

    private record Person(String firstName, String lastName) {}
}
