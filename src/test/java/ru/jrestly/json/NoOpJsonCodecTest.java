package ru.jrestly.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpJsonCodecTest {

    @Test
    @DisplayName("NoOpJsonCodec.instance() returns a singleton instance")
    void instanceReturnsSingleton() {
        NoOpJsonCodec first = NoOpJsonCodec.instance();
        NoOpJsonCodec second = NoOpJsonCodec.instance();

        assertSame(first, second);
        assertSame(first, JsonCodec.noop());
    }

    @Test
    @DisplayName("serialize(null) returns the JSON literal 'null'")
    void serializeNullReturnsJsonNull() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();
        assertEquals("null", codec.serialize(null));
    }

    @Test
    @DisplayName("serialize(nonNull) fails fast with actionable IllegalStateException")
    void serializeNonNullThrowsActionableException() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> codec.serialize(new TestDto(1L, "Alice", "test")));

        String msg = ex.getMessage();
        assertTrue(msg.contains("jackson-databind"), "Must mention jackson-databind: " + msg);
        assertTrue(msg.contains("gson"), "Must mention gson: " + msg);
        assertTrue(msg.contains("moshi"), "Must mention moshi: " + msg);
        assertTrue(msg.contains("jsonCodec"), "Must mention jsonCodec escape hatch: " + msg);
    }

    @Test
    @DisplayName("deserialize returns null for null, empty, blank or 'null' payloads")
    void deserializeEmptyOrNullReturnsNull() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();

        assertNull(codec.deserialize(null, TestDto.class));
        assertNull(codec.deserialize("", TestDto.class));
        assertNull(codec.deserialize("   ", TestDto.class));
        assertNull(codec.deserialize("null", TestDto.class));
        assertNull(codec.deserialize("  null \n", TestDto.class));
    }

    @Test
    @DisplayName("deserialize non-empty payload fails fast with actionable IllegalStateException")
    void deserializeNonEmptyPayloadThrowsActionableException() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> codec.deserialize("{\"id\":1}", TestDto.class));

        String msg = ex.getMessage();
        assertTrue(msg.contains("jackson-databind"), "Must mention jackson-databind: " + msg);
        assertTrue(msg.contains("gson"), "Must mention gson: " + msg);
        assertTrue(msg.contains("moshi"), "Must mention moshi: " + msg);
        assertTrue(msg.contains("jsonCodec"), "Must mention jsonCodec escape hatch: " + msg);
    }

    @Test
    @DisplayName("prettyPrint returns payload unchanged")
    void prettyPrintReturnsPayloadUnchanged() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();

        assertNull(codec.prettyPrint(null));
        assertEquals("{\"id\":1}", codec.prettyPrint("{\"id\":1}"));
        assertEquals("hello raw payload", codec.prettyPrint("hello raw payload"));
    }

    @Test
    @DisplayName("deserializeBytes and serializeToBytes handle null and fail on complex types")
    void rawByteMethodsInNoOpJsonCodec() {
        NoOpJsonCodec codec = NoOpJsonCodec.instance();

        assertNull(codec.deserializeBytes(null, TestDto.class));
        assertNull(codec.deserializeBytes(new byte[0], TestDto.class));
        assertNull(codec.deserializeBytes("   ".getBytes(java.nio.charset.StandardCharsets.UTF_8), TestDto.class));

        assertArrayEquals("null".getBytes(java.nio.charset.StandardCharsets.UTF_8), codec.serializeToBytes(null));

        assertThrows(IllegalStateException.class,
                () -> codec.deserializeBytes("{\"id\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8), TestDto.class));
        assertThrows(IllegalStateException.class,
                () -> codec.serializeToBytes(new TestDto(1L, "Alice", "test")));
    }
}
