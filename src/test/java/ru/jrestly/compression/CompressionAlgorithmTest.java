package ru.jrestly.compression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@code CompressionAlgorithm} enum (ADR-0041): encoding
 * tokens and {@code fromEncoding} lookup. Pure unit tests.
 */
@DisplayName("CompressionAlgorithm Unit Tests (ADR-0041)")
class CompressionAlgorithmTest {

    @Test
    @DisplayName("encoding() returns correct standard HTTP token")
    void testEncodingTokens() {
        assertEquals("gzip", CompressionAlgorithm.GZIP.encoding());
        assertEquals("deflate", CompressionAlgorithm.DEFLATE.encoding());
        assertEquals("identity", CompressionAlgorithm.NONE.encoding());
    }

    @Test
    @DisplayName("fromEncoding resolves case-insensitively with aliases")
    void testFromEncoding() {
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.fromEncoding("gzip"));
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.fromEncoding("GZIP"));
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.fromEncoding("x-gzip"));
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.fromEncoding(" X-GZIP "));

        assertEquals(CompressionAlgorithm.DEFLATE, CompressionAlgorithm.fromEncoding("deflate"));
        assertEquals(CompressionAlgorithm.DEFLATE, CompressionAlgorithm.fromEncoding(" Deflate "));

        assertEquals(CompressionAlgorithm.NONE, CompressionAlgorithm.fromEncoding("identity"));
        assertEquals(CompressionAlgorithm.NONE, CompressionAlgorithm.fromEncoding("none"));

        assertNull(CompressionAlgorithm.fromEncoding(null));
        assertNull(CompressionAlgorithm.fromEncoding(""));
        assertNull(CompressionAlgorithm.fromEncoding("brotli"));
        assertNull(CompressionAlgorithm.fromEncoding("unknown"));
    }
}
