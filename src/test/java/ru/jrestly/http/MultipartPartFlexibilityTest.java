package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartPartFlexibilityTest {

    @Test
    @DisplayName("text(name, value) creates UTF-8 text part")
    void textDefaultUtf8() {
        MultipartPart part = MultipartPart.text("message", "hello world");
        assertEquals("message", part.name());
        assertNull(part.filename());
        assertEquals("text/plain; charset=UTF-8", part.contentType());
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), (byte[]) part.content());
        assertNull(part.contentLength());
    }

    @Test
    @DisplayName("text(name, null) is null-safe and produces 0-length byte array")
    void textNullSafety() {
        MultipartPart part = MultipartPart.text("emptyField", null);
        assertEquals("emptyField", part.name());
        assertNull(part.filename());
        assertEquals("text/plain; charset=UTF-8", part.contentType());
        assertArrayEquals(new byte[0], (byte[]) part.content());
    }

    @Test
    @DisplayName("text(name, value, charset) encodes with explicit charset")
    void textCustomCharset() {
        MultipartPart part = MultipartPart.text("info", "test", StandardCharsets.ISO_8859_1);
        assertEquals("info", part.name());
        assertNull(part.filename());
        assertEquals("text/plain; charset=ISO-8859-1", part.contentType());
        assertArrayEquals("test".getBytes(StandardCharsets.ISO_8859_1), (byte[]) part.content());
    }

    @Test
    @DisplayName("text(name, null, charset) is null-safe and produces 0-length byte array")
    void textCustomCharsetNullSafety() {
        MultipartPart part = MultipartPart.text("info", null, StandardCharsets.ISO_8859_1);
        assertEquals("info", part.name());
        assertNull(part.filename());
        assertEquals("text/plain; charset=ISO-8859-1", part.contentType());
        assertArrayEquals(new byte[0], (byte[]) part.content());
    }

    @Test
    @DisplayName("text(name, value, null) throws NullPointerException")
    void textNullCharsetThrows() {
        assertThrows(NullPointerException.class, () -> MultipartPart.text("field", "value", null));
    }

    @Test
    @DisplayName("part(name, contentType, bytes) creates non-file part with explicit MIME")
    void partWithoutFilename() {
        byte[] payload = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        MultipartPart part = MultipartPart.part("metadata", "application/json", payload);

        assertEquals("metadata", part.name());
        assertNull(part.filename());
        assertEquals("application/json", part.contentType());
        assertArrayEquals(payload, (byte[]) part.content());
        assertNull(part.contentLength());
    }

    @Test
    @DisplayName("part(name, contentType, null) produces 0-length content")
    void partWithoutFilenameNullContent() {
        MultipartPart part = MultipartPart.part("emptyPart", "application/json", null);
        assertEquals("emptyPart", part.name());
        assertNull(part.filename());
        assertEquals("application/json", part.contentType());
        assertArrayEquals(new byte[0], (byte[]) part.content());
    }

    @Test
    @DisplayName("part(name, filename, contentType, bytes) creates part with explicit filename and MIME")
    void partWithFilename() {
        byte[] payload = "col1,col2\nval1,val2".getBytes(StandardCharsets.UTF_8);
        MultipartPart part = MultipartPart.part("data", "export.csv", "text/csv", payload);

        assertEquals("data", part.name());
        assertEquals("export.csv", part.filename());
        assertEquals("text/csv", part.contentType());
        assertArrayEquals(payload, (byte[]) part.content());
    }

    @Test
    @DisplayName("MultipartPart validates CRLF smuggling in contentType")
    void crlfValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                MultipartPart.part("field", "text/plain\r\nX-Injected: true", new byte[0]));
    }
}
