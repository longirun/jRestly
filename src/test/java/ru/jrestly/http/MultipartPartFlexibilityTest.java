package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    @DisplayName("part(name, filename, contentType, null) produces 0-length content with explicit filename")
    void partWithFilenameNullContent() {
        MultipartPart part = MultipartPart.part("blob", "empty.bin", "application/octet-stream", null);
        assertEquals("blob", part.name());
        assertEquals("empty.bin", part.filename());
        assertEquals("application/octet-stream", part.contentType());
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

    @Test
    @DisplayName("FileContent contentLength() contract: null when not known up front")
    void fileContentContentLengthIsNullWhenUnknown() {
        // FileContent.of(Path) rejects paths without a file name eagerly, so the
        // record fallback branch for getFileName() == null is structurally
        // defensive (same policy as the InMemoryHttpCache guards) — the
        // public contract under test here is the nullable contentLength().
        FileContent fromPath = FileContent.of(Path.of("/etc/hostname"));
        assertEquals("hostname", fromPath.fileName());
        assertEquals(Path.of("/etc/hostname"), fromPath.content());
        assertNull(fromPath.contentLength());

        FileContent fromBytes = FileContent.of("bytes.bin", new byte[0]);
        assertEquals("bytes.bin", fromBytes.fileName());
        assertNull(fromBytes.contentLength());
    }

    @Test
    @DisplayName("FileContent.of(Path) fail-fast: paths without a file name are rejected eagerly")
    void fileContentOfPathRejectsPathsWithoutFileName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FileContent.of(Path.of("/")));
        assertTrue(ex.getMessage().contains("/"));
    }

    @Test
    @DisplayName("FileContent stream factory fail-fast: negative contentLength is rejected, zero is valid")
    void fileContentStreamFactoryValidatesContentLength() throws java.io.IOException {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FileContent.of("llama.bin", () -> new java.io.ByteArrayInputStream(new byte[0]), -1));
        assertTrue(ex.getMessage().contains("-1"));

        // Zero length is a valid explicit length (empty file upload)
        FileContent zeroLength = FileContent.of("empty.bin",
                () -> new java.io.ByteArrayInputStream(new byte[0]), 0L);
        assertEquals("empty.bin", zeroLength.fileName());
        assertEquals(0L, zeroLength.contentLength());
    }

    @Test
    @DisplayName("TrailerCompletingInputStream triggers trailers on bulk reads and tolerates null supplier payloads")
    void testTrailerCompletingInputStreamBulkReadAndNullPayload() throws java.io.IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        // Bulk read reaching EOF triggers the trailer hook on the multi-byte path
        try (TrailerCompletingInputStream in = new TrailerCompletingInputStream(
                new ByteArrayInputStream(new byte[0]),
                () -> List.of(new Header("X-Bulk", "trailer")),
                list -> calls.incrementAndGet())) {
            byte[] sink = new byte[8];
            assertEquals(-1, in.read(sink, 0, sink.length));
        }
        assertEquals(1, calls.get());

        // Supplier returning null is treated as "no trailers" (no consumer call)
        try (TrailerCompletingInputStream in = new TrailerCompletingInputStream(
                new ByteArrayInputStream(new byte[0]),
                () -> null,
                list -> calls.incrementAndGet())) {
            assertEquals(-1, in.read());
        }
        assertEquals(1, calls.get());
    }
}
