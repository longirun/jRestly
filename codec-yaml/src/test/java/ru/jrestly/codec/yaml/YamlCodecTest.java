package ru.jrestly.codec.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage of the YAML {@code PayloadCodec} adapter (ADR-0073): media-type claim
 * including the {@code +yaml} suffix, real YAML serialization/deserialization and the
 * default mapper configuration.
 */
@DisplayName("YamlCodec: YAML binding through the PayloadCodec SPI (ADR-0073)")
class YamlCodecTest {

    record Person(String name, int age) {
    }

    private final YamlCodec codec = YamlCodec.defaults();

    @Test
    @DisplayName("claims the YAML media-type family including the +yaml structured suffix")
    void claimsYamlFamily() {
        assertTrue(codec.supports("application/yaml"));
        assertTrue(codec.supports("text/yaml"));
        assertTrue(codec.supports("application/vnd.company.v1+yaml"));
        assertFalse(codec.supports("application/json"));
        assertFalse(codec.supports("application/yam"), "no prefix/suffix over-match");
        assertFalse(codec.supports("application/yamls"), "no prefix/suffix over-match");
    }

    @Test
    @DisplayName("serialize writes real YAML, never JSON under a YAML header")
    void serializesToYaml() {
        String yaml = new String(codec.serialize(new Person("Anton", 42), "application/yaml"), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("name:"), yaml);
        assertTrue(yaml.contains("Anton"), yaml);
        assertTrue(yaml.contains("age: 42"), yaml);
        assertFalse(yaml.contains("{"), "must not emit JSON: " + yaml);
    }

    @Test
    @DisplayName("deserialize binds YAML bytes into the requested type")
    void deserializesFromYaml() {
        byte[] yaml = "name: Anton\nage: 42".getBytes(StandardCharsets.UTF_8);
        assertEquals(new Person("Anton", 42), codec.deserialize(yaml, Person.class, "application/yaml"));
    }

    @Test
    @DisplayName("null and empty payloads deserialize to null (SPI contract)")
    void nullOrEmptyPayloadDeserializesToNull() {
        assertNull(codec.deserialize(null, Person.class, "application/yaml"));
        assertNull(codec.deserialize(new byte[0], Person.class, "application/yaml"));
    }

    @Test
    @DisplayName("a null value serializes to an empty payload")
    void nullValueSerializesToEmpty() {
        assertArrayEquals(new byte[0], codec.serialize(null, "application/yaml"));
    }

    @Test
    @DisplayName("unknown YAML properties are ignored by default")
    void unknownPropertiesAreIgnored() {
        byte[] yaml = "name: Anton\nage: 42\nextra: x".getBytes(StandardCharsets.UTF_8);
        assertEquals(new Person("Anton", 42), codec.deserialize(yaml, Person.class, "application/yaml"));
    }

    @Test
    @DisplayName("prettyPrint normalizes valid YAML and falls back to text for broken input")
    void prettyPrintNormalizesAndFallsBack() {
        byte[] yaml = "name: Anton\nage: 42".getBytes(StandardCharsets.UTF_8);
        String pretty = codec.prettyPrint(yaml, "application/yaml", StandardCharsets.UTF_8);
        assertTrue(pretty.contains("\n"), "pretty output must be line-oriented: " + pretty);
        assertTrue(pretty.contains("Anton"), pretty);

        byte[] broken = "name: [Anton".getBytes(StandardCharsets.UTF_8);
        assertEquals("name: [Anton", codec.prettyPrint(broken, "application/yaml", StandardCharsets.UTF_8));
        assertNull(codec.prettyPrint(null, "application/yaml", StandardCharsets.UTF_8));
        assertEquals("", codec.prettyPrint(new byte[0], "application/yaml", StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an explicit mapper is used as is (and exposed)")
    void explicitMapperIsUsed() {
        YAMLMapper mapper = new YAMLMapper();
        YamlCodec custom = new YamlCodec(mapper);
        assertSame(mapper, custom.mapper());
    }

    @Test
    @DisplayName("a null mapper is rejected")
    void nullMapperIsRejected() {
        assertThrows(NullPointerException.class, () -> new YamlCodec(null));
    }

    @Test
    @DisplayName("defaults registers the java.time module when it is on the classpath")
    void defaultsRegisterJavaTime() {
        assertTrue(((ObjectMapper) codec.mapper()).getRegisteredModuleIds().stream()
                        .anyMatch(id -> id.toString().contains("jsr310")),
                "jsr310 module should be present in the test classpath");
    }
}
