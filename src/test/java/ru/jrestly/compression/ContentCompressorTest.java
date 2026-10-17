package ru.jrestly.compression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the built-in {@code ContentCompressor} implementations and
 * their registry (ADR-0041): GZIP/Deflate round-trips, null/empty handling,
 * streaming decompression, the unsupported default {@code decompress} and
 * encoding-keyed resolution. Pure unit tests.
 */
@DisplayName("ContentCompressor & Registry Unit Tests (ADR-0041)")
class ContentCompressorTest {

    @Test
    @DisplayName("GzipContentCompressor compresses and decompresses payload roundtrip")
    void testGzipRoundtrip() throws IOException {
        GzipContentCompressor compressor = GzipContentCompressor.INSTANCE;
        assertEquals("gzip", compressor.encoding());

        byte[] original = "Hello jRestly Request Compression! Repeated content repeated content.".repeat(20).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compressor.compress(original);

        assertTrue(compressed.length > 0);
        assertTrue(compressed.length < original.length, "Compressed size should be smaller for repetitive data");

        byte[] decompressed = compressor.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    @DisplayName("GzipContentCompressor handles empty and null inputs safely")
    void testGzipEmptyAndNull() throws IOException {
        GzipContentCompressor compressor = GzipContentCompressor.INSTANCE;

        assertArrayEquals(new byte[0], compressor.compress(null));
        assertArrayEquals(new byte[0], compressor.compress(new byte[0]));
        assertArrayEquals(new byte[0], compressor.decompress((byte[]) null));
        assertArrayEquals(new byte[0], compressor.decompress(new byte[0]));
        assertThrows(NullPointerException.class, () -> compressor.decompress((java.io.InputStream) null));
    }

    @Test
    @DisplayName("DeflateContentCompressor compresses and decompresses payload roundtrip")
    void testDeflateRoundtrip() throws IOException {
        DeflateContentCompressor compressor = DeflateContentCompressor.INSTANCE;
        assertEquals("deflate", compressor.encoding());

        byte[] original = "Deflate testing in jRestly: 1234567890 1234567890 1234567890".repeat(25).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compressor.compress(original);

        assertTrue(compressed.length > 0);
        assertTrue(compressed.length < original.length);

        byte[] decompressed = compressor.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    @DisplayName("DeflateContentCompressor handles empty and null inputs safely")
    void testDeflateEmptyAndNull() throws IOException {
        DeflateContentCompressor compressor = DeflateContentCompressor.INSTANCE;

        assertArrayEquals(new byte[0], compressor.compress(null));
        assertArrayEquals(new byte[0], compressor.compress(new byte[0]));
        assertArrayEquals(new byte[0], compressor.decompress((byte[]) null));
        assertArrayEquals(new byte[0], compressor.decompress(new byte[0]));
        assertThrows(NullPointerException.class, () -> compressor.decompress((java.io.InputStream) null));
    }

    @Test
    @DisplayName("ContentCompressors registry resolves built-ins and handles null/NONE")
    void testRegistryResolution() {
        assertSame(GzipContentCompressor.INSTANCE, ContentCompressors.get(CompressionAlgorithm.GZIP));
        assertSame(DeflateContentCompressor.INSTANCE, ContentCompressors.get(CompressionAlgorithm.DEFLATE));
        assertNull(ContentCompressors.get(CompressionAlgorithm.NONE));
        assertNull(ContentCompressors.get((CompressionAlgorithm) null));

        assertSame(GzipContentCompressor.INSTANCE, ContentCompressors.get("gzip"));
        assertSame(GzipContentCompressor.INSTANCE, ContentCompressors.get("x-gzip"));
        assertSame(GzipContentCompressor.INSTANCE, ContentCompressors.get(" GZIP "));
        assertSame(DeflateContentCompressor.INSTANCE, ContentCompressors.get("deflate"));
        assertSame(DeflateContentCompressor.INSTANCE, ContentCompressors.get(" DEFLATE "));

        assertNull(ContentCompressors.get("unknown"));
        assertNull(ContentCompressors.get((String) null));

        assertNotNull(ContentCompressors.builtin());
        assertTrue(ContentCompressors.builtin().containsKey("gzip"));
        assertTrue(ContentCompressors.builtin().containsKey("deflate"));
    }

    @Test
    @DisplayName("ContentCompressor default decompress throws UnsupportedOperationException")
    void testDefaultDecompressThrows() {
        ContentCompressor compressor = new ContentCompressor() {
            @Override
            public String encoding() {
                return "custom";
            }

            @Override
            public byte[] compress(byte[] uncompressed) {
                return uncompressed;
            }
        };

        assertEquals("custom", compressor.encoding());
        assertThrows(UnsupportedOperationException.class, () -> compressor.decompress(new byte[]{1, 2, 3}));
        assertThrows(UnsupportedOperationException.class, () -> compressor.decompress(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test
    @DisplayName("GzipContentCompressor and DeflateContentCompressor decompress streaming InputStream correctly")
    void testStreamingDecompression() throws IOException {
        byte[] original = "Streaming compression payload roundtrip verification text.".repeat(10).getBytes(StandardCharsets.UTF_8);

        // GZIP stream
        byte[] gzipCompressed = GzipContentCompressor.INSTANCE.compress(original);
        try (java.io.InputStream stream = GzipContentCompressor.INSTANCE.decompress(new java.io.ByteArrayInputStream(gzipCompressed))) {
            assertArrayEquals(original, stream.readAllBytes());
        }

        // DEFLATE stream
        byte[] deflateCompressed = DeflateContentCompressor.INSTANCE.compress(original);
        try (java.io.InputStream stream = DeflateContentCompressor.INSTANCE.decompress(new java.io.ByteArrayInputStream(deflateCompressed))) {
            assertArrayEquals(original, stream.readAllBytes());
        }
    }
}
