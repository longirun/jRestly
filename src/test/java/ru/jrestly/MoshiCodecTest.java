package ru.jrestly;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.EventDto;
import ru.jrestly.fixtures.NullableFieldDto;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.MoshiCodec;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MoshiCodec#defaults()} and the explicit-Moshi constructor.
 * <p>
 * Pinned against Moshi 1.15.2. The Moshi-only classpath scenario (no Jackson)
 * cannot be reproduced here: the test classpath always carries
 * jackson-databind, and the resolver's probe chain hands Jackson first hit.
 * Codec-level behavior is pinned here, resolution priority is pinned in
 * {@code JsonCodecResolverTest}.
 * <p>
 * Key plain-Moshi realities pinned below: neither {@code java.time} nor
 * {@code java.util.Date} is first-class — Moshi 1.x has no built-in or
 * standard add-on adapters for them, and its reflective class adapter
 * rejects {@code java.*} platform classes outright (the escape hatch is an
 * explicit {@code Moshi} with user-registered adapters).
 */
class MoshiCodecTest {

    private final MoshiCodec codec = MoshiCodec.defaults();

    @Test
    @DisplayName("defaults() round-trips a DTO (Moshi's reflective record support)")
    void defaultsRoundTripDto() {
        TestDto dto = new TestDto(7L, "jRestly", "a declarative HTTP client");

        String json = codec.serialize(dto);
        assertEquals("{\"id\":7,\"name\":\"jRestly\",\"description\":\"a declarative HTTP client\"}", json);

        assertEquals(dto, codec.deserialize(json, TestDto.class));
    }

    @Test
    @DisplayName("defaults(): java.time is not first-class — serializing LocalDateTime fails (no adapter registered in a plain Moshi)")
    void defaultsJavaTimeNotFirstClass() {
        JsonDataException ex = assertThrows(JsonDataException.class,
                () -> codec.serialize(LocalDateTime.of(2026, 8, 24, 12, 34, 56)));

        assertTrue(ex.getMessage().contains("Failed to serialize"),
                "the wrapper must name the operation, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(LocalDateTime.class.getName()),
                "the wrapper must name the value type, got: " + ex.getMessage());
        assertNotNull(ex.getCause(), "Moshi's platform-class rejection must stay the cause");
        assertTrue(ex.getCause().getMessage().contains("Platform class java.time.LocalDateTime"),
                "the cause must be Moshi's platform-class error, got: " + ex.getCause().getMessage());
    }

    @Test
    @DisplayName("defaults(): deserializing a LocalDateTime payload fails naming the target type")
    void defaultsJavaTimeDeserializeNotFirstClass() {
        JsonDataException ex = assertThrows(JsonDataException.class,
                () -> codec.deserialize("\"2026-08-24T12:34:56\"", LocalDateTime.class));

        assertTrue(ex.getMessage().contains("Failed to deserialize"));
        assertTrue(ex.getMessage().contains(LocalDateTime.class.getTypeName()));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("defaults(): java.util.Date is not first-class either — Moshi rejects java.* platform classes for its reflective adapter")
    void defaultsDateRequiresExplicitAdapter() {
        Date date = new Date(1234567890000L);

        JsonDataException ex = assertThrows(JsonDataException.class,
                () -> codec.serialize(date),
                "plain Moshi must not silently field-reflect a platform class");

        assertTrue(ex.getMessage().contains("Failed to serialize"));
        assertTrue(ex.getMessage().contains(Date.class.getName()));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Platform class java.util.Date"),
                "Moshi's platform-class rejection must stay the cause, got: " + ex.getCause().getMessage());
    }

    @Test
    @DisplayName("defaults() ignores unknown JSON properties (Moshi's native leniency)")
    void defaultsIgnoreUnknownProperties() {
        TestDto back = codec.deserialize(
                "{\"id\":7,\"name\":\"jRestly\",\"description\":\"a declarative HTTP client\",\"extraField\":\"ignored\"}",
                TestDto.class);

        assertEquals(7L, back.id());
        assertEquals("jRestly", back.name());
    }

    @Test
    @DisplayName("defaults() omits null object fields on serialization (JsonWriter without serializeNulls); they deserialize back to null")
    void defaultsOmitNullFieldsOnSerialize() {
        NullableFieldDto dto = new NullableFieldDto(7L, null);

        String json = codec.serialize(dto);
        assertEquals("{\"id\":7}", json, "null fields must be omitted, not serialized as null");

        NullableFieldDto back = codec.deserialize(json, NullableFieldDto.class);
        assertEquals(7L, back.id());
        assertNull(back.name());
    }

    @Test
    @DisplayName("null handling: serialize(null) is 'null', deserialize('null') is null")
    void nullHandling() {
        assertEquals("null", codec.serialize(null));
        assertNull(codec.deserialize("null", TestDto.class));
    }

    @Test
    @DisplayName("explicit Moshi is used as is: a registered ISO-8601 LocalDateTime adapter makes java.time first-class")
    void explicitMoshiJavaTimeAdapterUsedAsIs() {
        MoshiCodec isoCodec = new MoshiCodec(new Moshi.Builder()
                .add(LocalDateTime.class, new IsoLocalDateTimeAdapter())
                .build());

        LocalDateTime at = LocalDateTime.of(2026, 8, 24, 12, 34, 56);
        assertEquals("{\"id\":7,\"createdAt\":\"2026-08-24T12:34:56\"}", isoCodec.serialize(new EventDto(7L, at)));
        assertEquals(new EventDto(7L, at), isoCodec.deserialize(
                "{\"id\":7,\"createdAt\":\"2026-08-24T12:34:56\"}", EventDto.class));

        // the same adapter prints fractional seconds when present
        LocalDateTime withMillis = LocalDateTime.of(2026, 8, 24, 12, 34, 56, 1_000_000);
        assertEquals("\"2026-08-24T12:34:56.001\"", isoCodec.serialize(withMillis));
    }

    @Test
    @DisplayName("explicit Moshi is used as is: a custom scalar adapter for a DTO type wins over the reflective one")
    void explicitMoshiUsedAsIs() {
        Moshi customMoshi = new Moshi.Builder()
                .add(Person.class, new PersonAsStringAdapter())
                .build();
        MoshiCodec customCodec = new MoshiCodec(customMoshi);

        String json = customCodec.serialize(new Person("Ada", "Lovelace"));
        assertEquals("\"Ada Lovelace\"", json, "the explicit Moshi's custom adapter must be used as is");

        assertEquals(new Person("Ada", "Lovelace"), customCodec.deserialize(json, Person.class));
    }

    @Test
    @DisplayName("malformed JSON fails with JsonDataException naming the target type, the original cause preserved")
    void malformedJsonFailsWithTypeInMessage() {
        JsonDataException ex = assertThrows(JsonDataException.class,
                () -> codec.deserialize("{not json", TestDto.class));

        assertTrue(ex.getMessage().contains("Failed to deserialize"));
        assertTrue(ex.getMessage().contains(TestDto.class.getTypeName()));
        assertNotNull(ex.getCause(), "Moshi's original exception must stay the cause");
    }

    @Test
    @DisplayName("deserialize(Type) supports parameterized types (controller List<T> return path)")
    void deserializeSupportsParameterizedTypes() {
        String json = "[{\"id\":1,\"name\":\"one\",\"description\":\"first\"},"
                + "{\"id\":2,\"name\":\"two\",\"description\":\"second\"}]";

        Type listType = Types.newParameterizedType(List.class, TestDto.class);
        List<TestDto> dtos = codec.deserialize(json, listType);

        assertEquals(2, dtos.size());
        assertEquals(1L, dtos.get(0).id());
        assertEquals("second", dtos.get(1).description());
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

    @Test
    @DisplayName("prettyPrint preserves number literals: integers stay integers, decimals keep their exact digits")
    void prettyPrintPreservesNumberLiterals() {
        String payload = "{\"count\":1,\"ratio\":1.10,\"big\":123456789012345}";

        String pretty = codec.prettyPrint(payload);
        assertTrue(pretty.contains("\"count\": 1"), "integers must not widen to doubles: " + pretty);
        assertTrue(pretty.contains("\"ratio\": 1.10"), "the exact decimal literal must survive: " + pretty);
        assertTrue(pretty.contains("\"big\": 123456789012345"), "long literals must keep full precision: " + pretty);
    }

    @Test
    @DisplayName("deserializeBytes() and serializeToBytes() roundtrip raw bytes in Moshi")
    void rawByteRoundtripInMoshi() {
        TestDto dto = new TestDto(99L, "ZeroAlloc", "Moshi byte pipeline");

        byte[] bytes = codec.serializeToBytes(dto);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        TestDto roundtrip = codec.deserializeBytes(bytes, TestDto.class);
        assertEquals(dto, roundtrip);

        assertNull(codec.deserializeBytes(null, TestDto.class));
        assertNull(codec.deserializeBytes(new byte[0], TestDto.class));
        assertArrayEquals("null".getBytes(java.nio.charset.StandardCharsets.UTF_8), codec.serializeToBytes(null));

        assertThrows(JsonDataException.class, () ->
                codec.deserializeBytes("{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8), TestDto.class));
        assertThrows(JsonDataException.class, () ->
                codec.serializeToBytes(new Date()));
    }

    @Test
    @DisplayName("prettyPrint returns empty and blank payloads unchanged (nothing to re-print)")
    void prettyPrintEmptyAndBlankPayloadsReturnUnchanged() {
        // Best-effort contract: a payload with no JSON value (empty or
        // whitespace-only) cannot be re-printed and comes back as is.
        assertEquals("", codec.prettyPrint(""));
        assertEquals("   ", codec.prettyPrint("   "));
    }

    @Test
    @DisplayName("prettyPrint formats arrays, booleans, and nulls properly")
    void prettyPrintTokensHandling() {
        String input = "[1,true,false,null,\"string\",{\"k\":2}]";
        String pretty = codec.prettyPrint(input);

        assertTrue(pretty.contains("[\n"));
        assertTrue(pretty.contains("true"));
        assertTrue(pretty.contains("false"));
        assertTrue(pretty.contains("null"));
        assertTrue(pretty.contains("\"string\""));
        assertTrue(pretty.contains("\"k\": 2"));
    }

    private record Person(String firstName, String lastName) {}

    /** The java.time escape hatch in action: an ISO-8601 string adapter for {@link LocalDateTime}. */
    private static final class IsoLocalDateTimeAdapter extends JsonAdapter<LocalDateTime> {

        @Override
        public void toJson(JsonWriter out, LocalDateTime value) throws IOException {
            out.value(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }

        @Override
        public LocalDateTime fromJson(JsonReader in) throws IOException {
            return LocalDateTime.parse(in.nextString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    /** A scalar adapter that prints a {@link Person} as "first last" — proves an explicit Moshi is used as is. */
    private static final class PersonAsStringAdapter extends JsonAdapter<Person> {

        @Override
        public void toJson(JsonWriter out, Person value) throws IOException {
            out.value(value.firstName() + " " + value.lastName());
        }

        @Override
        public Person fromJson(JsonReader in) throws IOException {
            String[] parts = in.nextString().split(" ");
            return new Person(parts[0], parts[1]);
        }
    }
}
