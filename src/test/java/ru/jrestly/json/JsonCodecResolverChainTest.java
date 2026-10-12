package ru.jrestly.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain semantics of {@link JsonCodecResolver} through its package-private
 * test seam (ADR-0007, Aspect 6): every classpath combination must resolve
 * the first hit of the fixed jackson &rarr; gson &rarr; moshi order.
 *
 * <p>The real-classpath pin ("all three present &rarr; Jackson wins") lives
 * in {@code ru.jrestly.JsonCodecResolverTest}; this class drives the chain
 * deterministically with simulated classpaths.
 */
class JsonCodecResolverChainTest {

    private static final String JACKSON = "com.fasterxml.jackson.databind.ObjectMapper";
    private static final String GSON = "com.google.gson.Gson";
    private static final String MOSHI = "com.squareup.moshi.Moshi";

    private static Predicate<String> classpathWith(String... present) {
        Set<String> presentClasses = Set.of(present);
        return presentClasses::contains;
    }

    @Test
    @DisplayName("chain head: jackson wins when all three libraries are present")
    void jacksonWinsWhenAllThreePresent() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(JACKSON, GSON, MOSHI));

        assertInstanceOf(JacksonCodec.class, codec);
    }

    @Test
    @DisplayName("chain head: jackson wins over gson")
    void jacksonWinsOverGson() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(JACKSON, GSON));

        assertInstanceOf(JacksonCodec.class, codec);
    }

    @Test
    @DisplayName("chain head: jackson wins over moshi")
    void jacksonWinsOverMoshi() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(JACKSON, MOSHI));

        assertInstanceOf(JacksonCodec.class, codec);
    }

    @Test
    @DisplayName("gson-only classpath resolves the working GsonCodec.defaults()")
    void gsonWinsWhenOnlyGsonPresent() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(GSON));

        assertInstanceOf(GsonCodec.class, codec);
        // Functional pin: the resolved codec is a usable defaults() instance
        assertEquals(fixtureDtoJson(), codec.serialize(
                new TestDto(7L, "jRestly", "a declarative HTTP client")));
    }

    @Test
    @DisplayName("without jackson, gson wins over moshi")
    void gsonWinsOverMoshiWhenJacksonAbsent() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(GSON, MOSHI));

        assertInstanceOf(GsonCodec.class, codec);
    }

    @Test
    @DisplayName("moshi-only classpath resolves the working MoshiCodec.defaults()")
    void moshiWinsWhenOnlyMoshiPresent() {
        JsonCodec codec = JsonCodecResolver.resolve(classpathWith(MOSHI));

        assertInstanceOf(MoshiCodec.class, codec);
        // Functional pin: the resolved codec is a usable defaults() instance.
        // A DTO record, not a Map: plain Moshi's reflective adapter accepts
        // only collection *interfaces*, so Map.of(...)'s
        // ImmutableCollections$Map1 would be rejected (pinned against Moshi
        // 1.15.2; the expected JSON matches the MoshiCodecTest pin).
        assertEquals(fixtureDtoJson(), codec.serialize(
                new TestDto(7L, "jRestly", "a declarative HTTP client")));
    }

    @Test
    @DisplayName("empty classpath fails fast naming all three libraries and the explicit codec")
    void failFastWhenClasspathIsEmpty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JsonCodecResolver.resolve(classpathWith()));

        String message = e.getMessage();
        assertTrue(message.contains("jackson-databind"),
                "fail-fast message must name jackson-databind: " + message);
        assertTrue(message.contains("gson"),
                "fail-fast message must name gson: " + message);
        assertTrue(message.contains("moshi"),
                "fail-fast message must name moshi: " + message);
        assertTrue(message.contains("jsonCodec"),
                "fail-fast message must point at the explicit-codec escape hatch: " + message);
    }

    /**
     * The compact JSON of the shared {@link TestDto} fixture, as produced by
     * each codec's defaults() (field order follows the record declaration).
     */
    private static String fixtureDtoJson() {
        return "{\"id\":7,\"name\":\"jRestly\",\"description\":\"a declarative HTTP client\"}";
    }
}
