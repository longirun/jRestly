package ru.jrestly.compression;

import java.util.Locale;

/**
 * Standard HTTP content encoding algorithms supported by jRestly for request
 * compression and response decompression negotiation (ADR-0041).
 */
public enum CompressionAlgorithm {

    /**
     * GZIP compression format (RFC 1952) with content coding {@code "gzip"}.
     */
    GZIP("gzip"),

    /**
     * Deflate compression format (RFC 1950 zlib / RFC 1951 raw) with content coding {@code "deflate"}.
     */
    DEFLATE("deflate"),

    /**
     * No compression applied (content coding {@code "identity"}).
     */
    NONE("identity");

    private final String encoding;

    CompressionAlgorithm(String encoding) {
        this.encoding = encoding;
    }

    /**
     * Returns the standard HTTP {@code Content-Encoding} token for this algorithm.
     *
     * @return the content encoding token (e.g. {@code "gzip"}, {@code "deflate"}, {@code "identity"})
     */
    public String encoding() {
        return encoding;
    }

    /**
     * Finds the matching {@link CompressionAlgorithm} for the given content encoding token,
     * ignoring case.
     *
     * @param encoding the content coding token (e.g. {@code "gzip"}, {@code "x-gzip"}, {@code "deflate"})
     * @return the matching algorithm, or {@code null} if not recognized
     */
    public static CompressionAlgorithm fromEncoding(String encoding) {
        if (encoding == null) {
            return null;
        }
        String normalized = encoding.trim().toLowerCase(Locale.ROOT);
        if ("gzip".equals(normalized) || "x-gzip".equals(normalized)) {
            return GZIP;
        }
        if ("deflate".equals(normalized)) {
            return DEFLATE;
        }
        if ("identity".equals(normalized) || "none".equals(normalized)) {
            return NONE;
        }
        return null;
    }
}
