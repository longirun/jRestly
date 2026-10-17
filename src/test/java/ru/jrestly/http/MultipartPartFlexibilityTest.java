package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the multipart part factory (ADR-0024): text parts with UTF-8
 * or an explicit charset and null-safe handling, non-file parts with explicit
 * MIME, filename parts and CRLF-smuggling rejection in the content type. No
 * network.
 */
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

    @Test
    @DisplayName("MultipartPart.file creates parts from stream supplier, known length, and Path")
    void fileStreamSupplierAndPathFactories() {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream("streamed-bytes".getBytes(StandardCharsets.UTF_8));
        MultipartPart streamPart = MultipartPart.file("streamField", "document.pdf", () -> bis);
        assertEquals("streamField", streamPart.name());
        assertEquals("document.pdf", streamPart.filename());
        assertEquals("application/pdf", streamPart.contentType());
        assertNull(streamPart.contentLength());

        MultipartPart sizedPart = MultipartPart.file("sizedField", "image.png", () -> bis, 1024L);
        assertEquals("sizedField", sizedPart.name());
        assertEquals("image.png", sizedPart.filename());
        assertEquals("image/png", sizedPart.contentType());
        assertEquals(1024L, sizedPart.contentLength());

        java.nio.file.Path testPath = java.nio.file.Path.of("test-file.json");
        MultipartPart pathPart = MultipartPart.file("pathField", testPath);
        assertEquals("pathField", pathPart.name());
        assertEquals("test-file.json", pathPart.filename());
        assertEquals("application/json", pathPart.contentType());
        assertEquals(testPath, pathPart.content());

        assertThrows(NullPointerException.class, () -> MultipartPart.file("f", (java.nio.file.Path) null));
    }

    @Test
    @DisplayName("MultipartBodyPublisher subscription cancel() cleans up resources")
    void multipartBodyPublisherSubscriptionCancel() {
        var part = MultipartPart.file("stream", "test.txt", () -> new java.io.ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        var publisher = MultipartBodyPublisher.of("boundary123", java.util.List.of(part));
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override public void onNext(java.nio.ByteBuffer item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });
    }

    @Test
    @DisplayName("MultipartBodyPublisher ofStream subscription cancel() closes underlying stream")
    void multipartStreamSubscriptionCancel() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.io.InputStream is = new java.io.ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws java.io.IOException {
                closed.set(true);
                super.close();
            }
        };
        var publisher = MultipartBodyPublisher.ofStream(() -> is, 4L);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                // Request 1 chunk to open the stream
                subscription.request(1);
                // Cancel subscription
                subscription.cancel();
            }

            @Override public void onNext(java.nio.ByteBuffer item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });
        assertTrue(closed.get());
    }
}
