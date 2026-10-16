package ru.jrestly.codec.jacksonxml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JacksonXmlCodec: XML binding through the PayloadCodec SPI (ADR-0060)")
class JacksonXmlCodecTest {

    @JacksonXmlRootElement(localName = "person")
    record Person(String name, int age) {
    }

    private final JacksonXmlCodec codec = JacksonXmlCodec.defaults();

    @Test
    @DisplayName("claims the XML media-type family including the +xml structured suffix")
    void claimsXmlFamily() {
        assertTrue(codec.supports("application/xml"));
        assertTrue(codec.supports("text/xml"));
        assertTrue(codec.supports("application/vnd.company.v1+xml"));
        assertFalse(codec.supports("application/json"));
        assertFalse(codec.supports("application/xmlx"), "no prefix/suffix over-match");
    }

    @Test
    @DisplayName("serialize writes real XML, never JSON under an XML header")
    void serializesToXml() {
        String xml = new String(codec.serialize(new Person("Anton", 42), "application/xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<name>Anton</name>"), xml);
        assertTrue(xml.contains("<age>42</age>"), xml);
        assertFalse(xml.contains("{"), "must not emit JSON: " + xml);
    }

    @Test
    @DisplayName("deserialize binds XML bytes into the requested type")
    void deserializesFromXml() {
        byte[] xml = "<person><name>Anton</name><age>42</age></person>".getBytes(StandardCharsets.UTF_8);
        assertEquals(new Person("Anton", 42), codec.deserialize(xml, Person.class, "application/xml"));
    }

    @Test
    @DisplayName("null and empty payloads deserialize to null (SPI contract)")
    void nullOrEmptyPayloadDeserializesToNull() {
        assertNull(codec.deserialize(null, Person.class, "application/xml"));
        assertNull(codec.deserialize(new byte[0], Person.class, "application/xml"));
    }

    @Test
    @DisplayName("a null value serializes to an empty payload")
    void nullValueSerializesToEmpty() {
        assertArrayEquals(new byte[0], codec.serialize(null, "application/xml"));
    }

    @Test
    @DisplayName("unknown XML elements are ignored by default")
    void unknownPropertiesAreIgnored() {
        byte[] xml = "<person><name>Anton</name><age>42</age><extra>x</extra></person>"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(new Person("Anton", 42), codec.deserialize(xml, Person.class, "application/xml"));
    }

    @Test
    @DisplayName("prettyPrint indents valid XML and falls back to text for broken input")
    void prettyPrintIndentsAndFallsBack() {
        byte[] xml = "<person><name>Anton</name><age>42</age></person>".getBytes(StandardCharsets.UTF_8);
        String pretty = codec.prettyPrint(xml, "application/xml", StandardCharsets.UTF_8);
        assertTrue(pretty.contains("\n"), "pretty output must be indented: " + pretty);
        assertTrue(pretty.contains("<name>Anton</name>"));

        byte[] broken = "<person><name>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<person><name>", codec.prettyPrint(broken, "application/xml", StandardCharsets.UTF_8));
        assertNull(codec.prettyPrint(null, "application/xml", StandardCharsets.UTF_8));
        assertEquals("", codec.prettyPrint(new byte[0], "application/xml", StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an explicit mapper is used as is (and exposed)")
    void explicitMapperIsUsed() {
        XmlMapper mapper = new XmlMapper();
        JacksonXmlCodec custom = new JacksonXmlCodec(mapper);
        assertSame(mapper, custom.mapper());
    }

    @Test
    @DisplayName("a null mapper is rejected")
    void nullMapperIsRejected() {
        assertThrows(NullPointerException.class, () -> new JacksonXmlCodec(null));
    }

    @Test
    @DisplayName("defaults registers the java.time module when it is on the classpath")
    void defaultsRegisterJavaTime() {
        assertTrue(((ObjectMapper) codec.mapper()).getRegisteredModuleIds().stream()
                        .anyMatch(id -> id.toString().contains("jsr310")),
                "jsr310 module should be present in the test classpath");
    }
}
