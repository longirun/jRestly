package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0078: the size-only seam {@code MultipartBodyPublisher.contentLength(boundary, parts)}
 * — the exact rendered body size without building the publisher graph. Pinned here:
 * byte parity with {@code of(...).contentLength()} for every part kind, the RFC 7578
 * framing of the total, the -1 propagation, the Path/Supplier/unsupported semantics
 * of the rendered publisher, and the probe-not-build contract (the stream source is
 * never opened, an unbounded part short-circuits before later parts are probed).
 */
class MultipartBodyPublisherContentLengthTest {

    private static final String BOUNDARY = "B7";

    @TempDir
    Path tempDir;

    private static MultipartPart streamPart(String name, String filename, Supplier<InputStream> source) {
        return MultipartPart.file(name, filename, source);
    }

    private static MultipartPart streamPartWithLength(String name, String filename,
                                                      Supplier<InputStream> source, long length) {
        return MultipartPart.file(name, filename, source, length);
    }

    @Test
    @DisplayName("the seam matches of(...).contentLength() for every part kind (byte parity)")
    void seamMatchesRenderedPublisherForAllPartKinds() throws IOException {
        Path file = tempDir.resolve("payload.bin");
        Files.write(file, "file-bytes".getBytes(StandardCharsets.UTF_8));

        List<MultipartPart> parts = List.of(
                MultipartPart.text("field", "hello world"),
                MultipartPart.part("blob", "application/octet-stream", "raw-bytes".getBytes(StandardCharsets.UTF_8)),
                MultipartPart.part("file.bin", "binary", "application/octet-stream", "fbytes".getBytes(StandardCharsets.UTF_8)),
                MultipartPart.file("disk", file),
                streamPartWithLength("known", "known.dat",
                        () -> new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 5L),
                streamPartWithLength("empty", "empty.dat",
                        () -> new ByteArrayInputStream(new byte[0]), 0L));

        long rendered = MultipartBodyPublisher.of(BOUNDARY, parts).contentLength();
        assertNotNull(rendered);
        assertTrue(rendered >= 0, "mixed set must be fully known: " + rendered);
        assertEquals(rendered, MultipartBodyPublisher.contentLength(BOUNDARY, parts),
                "the seam must mirror the rendered publisher's contentLength byte for byte");
    }

    @Test
    @DisplayName("the seam equals the hand-computed RFC 7578 body size")
    void seamMatchesHandComputedFraming() {
        List<MultipartPart> parts = List.of(MultipartPart.text("a", "b"));

        // --B7 CRLF | Content-Disposition CRLF | Content-Type CRLF | CRLF | b | CRLF | --B7-- CRLF
        String wire = "--B7\r\n"
                + "Content-Disposition: form-data; name=\"a\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "b\r\n"
                + "--B7--\r\n";

        assertEquals(wire.getBytes(StandardCharsets.UTF_8).length,
                MultipartBodyPublisher.contentLength(BOUNDARY, parts),
                "header + content + framing bytes, exactly what goes on the wire");
        // and the rendered publisher agrees with the same framing
        assertEquals(wire.getBytes(StandardCharsets.UTF_8).length,
                MultipartBodyPublisher.of(BOUNDARY, parts).contentLength());
    }

    @Test
    @DisplayName("an empty part list is only the closing delimiter")
    void emptyPartsIsOnlyTheClosingDelimiter() {
        long expected = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expected, MultipartBodyPublisher.contentLength(BOUNDARY, List.of()));
        assertEquals(MultipartBodyPublisher.of(BOUNDARY, List.of()).contentLength(),
                MultipartBodyPublisher.contentLength(BOUNDARY, List.of()));
    }

    @Test
    @DisplayName("an unbounded stream part makes the total unknown (-1), like concat")
    void unknownStreamPartMakesTheTotalUnknown() throws IOException {
        List<MultipartPart> parts = List.of(
                MultipartPart.text("known", "abc"),
                streamPart("open", "open.dat", () -> new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8))));

        assertEquals(-1, MultipartBodyPublisher.of(BOUNDARY, parts).contentLength());
        assertEquals(-1, MultipartBodyPublisher.contentLength(BOUNDARY, parts));
    }

    @Test
    @DisplayName("a zero-length stream part counts as 0 (ofStream short-circuits to noBody)")
    void zeroLengthStreamPartCountsAsZero() throws IOException {
        List<MultipartPart> parts = List.of(
                MultipartPart.text("known", "abc"),
                streamPartWithLength("zero", "zero.dat", () -> new ByteArrayInputStream(new byte[0]), 0L));

        long rendered = MultipartBodyPublisher.of(BOUNDARY, parts).contentLength();
        assertTrue(rendered >= 0);
        assertEquals(rendered, MultipartBodyPublisher.contentLength(BOUNDARY, parts));
    }

    @Test
    @DisplayName("a Path part reports the file byte size")
    void pathPartReportsTheFileByteSize() throws IOException {
        Path file = tempDir.resolve("sized.txt");
        Files.write(file, "one-two-three".getBytes(StandardCharsets.UTF_8));

        List<MultipartPart> parts = List.of(MultipartPart.file("disk", file));

        long rendered = MultipartBodyPublisher.of(BOUNDARY, parts).contentLength();
        assertTrue(rendered >= 0);
        assertEquals(rendered, MultipartBodyPublisher.contentLength(BOUNDARY, parts));
        // the seam must count the real file size, not 0 or -1
        assertTrue(MultipartBodyPublisher.contentLength(BOUNDARY, parts) > 13,
                "the 13 file bytes must be counted into the total");
    }

    @Test
    @DisplayName("a missing Path part fails exactly like of(): RuntimeException wrapping FileNotFoundException")
    void missingFilePartThrowsWrappedFileNotFoundException() {
        Path missing = tempDir.resolve("missing.txt");
        List<MultipartPart> parts = List.of(MultipartPart.file("disk", missing));

        RuntimeException fromOf = assertThrows(RuntimeException.class,
                () -> MultipartBodyPublisher.of(BOUNDARY, parts));
        assertInstanceOf(FileNotFoundException.class, fromOf.getCause());

        RuntimeException fromSeam = assertThrows(RuntimeException.class,
                () -> MultipartBodyPublisher.contentLength(BOUNDARY, parts));
        assertInstanceOf(FileNotFoundException.class, fromSeam.getCause());
        assertTrue(fromSeam.getMessage().contains("Cannot read multipart file"), fromSeam.getMessage());
    }

    @Test
    @DisplayName("an existing directory is accepted by of() (JDK 21 opens it lazily) and the seam mirrors it")
    void directoryPartMatchesOfBehavior() {
        List<MultipartPart> parts = List.of(MultipartPart.file("disk", tempDir));

        long rendered = MultipartBodyPublisher.of(BOUNDARY, parts).contentLength();
        assertTrue(rendered >= 0, "JDK 21's ofFile accepts an existing directory at build time");
        assertEquals(rendered, MultipartBodyPublisher.contentLength(BOUNDARY, parts),
                "the seam must report the same directory-entry size as the rendered publisher");
    }

    @Test
    @DisplayName("an unsupported content kind fails exactly like of(): IllegalStateException")
    void unsupportedContentKindThrowsIllegalState() {
        // the canonical constructor admits any Object content; the rendered publisher rejects String
        MultipartPart odd = new MultipartPart("odd", null, null, "raw-string-content", null);
        List<MultipartPart> parts = List.of(odd);

        assertThrows(IllegalStateException.class, () -> MultipartBodyPublisher.of(BOUNDARY, parts));
        IllegalStateException fromSeam = assertThrows(IllegalStateException.class,
                () -> MultipartBodyPublisher.contentLength(BOUNDARY, parts));
        assertTrue(fromSeam.getMessage().contains("Unsupported multipart content"), fromSeam.getMessage());
    }

    @Test
    @DisplayName("the probe never opens the stream source (lazy parity with the rendered publisher)")
    void sizeProbeNeverOpensTheStreamSource() {
        AtomicInteger opens = new AtomicInteger();
        Supplier<InputStream> counting = () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8));
        };

        List<MultipartPart> parts = List.of(streamPartWithLength("counted", "counted.dat", counting, 7L));

        long size = MultipartBodyPublisher.contentLength(BOUNDARY, parts);
        assertTrue(size >= 0);
        assertEquals(0, opens.get(), "measuring the size must not run the user's producer");
    }

    @Test
    @DisplayName("the probe stops at the first unbounded part: later parts are not probed (probe, not build)")
    void probeStopsAtTheFirstUnknownPart() {
        Path missing = tempDir.resolve("missing-later.txt");
        List<MultipartPart> parts = List.of(
                streamPart("open", "open.dat", () -> new ByteArrayInputStream(new byte[0])),
                MultipartPart.file("disk", missing));

        // of() would throw while building the second part's publisher; the seam reports -1 and stops
        assertThrows(RuntimeException.class, () -> MultipartBodyPublisher.of(BOUNDARY, parts));
        assertEquals(-1, MultipartBodyPublisher.contentLength(BOUNDARY, parts));
    }
}
