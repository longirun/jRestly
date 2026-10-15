package ru.jrestly.compression;

import java.io.IOException;

/**
 * Service Provider Interface (SPI) for HTTP content compression and decompression (ADR-0041).
 * Implementations provide encoding algorithms (such as gzip, deflate, or custom formats like brotli/zstd)
 * to compress outbound request payloads and optionally decompress inbound responses.
 */
public interface ContentCompressor {

    /**
     * Returns the HTTP {@code Content-Encoding} token handled by this compressor (e.g. {@code "gzip"}).
     *
     * @return the content encoding name
     */
    String encoding();

    /**
     * Compresses the given uncompressed payload bytes.
     *
     * @param uncompressed raw uncompressed payload bytes
     * @return compressed payload bytes
     * @throws IOException on compression failure
     */
    byte[] compress(byte[] uncompressed) throws IOException;

    /**
     * Decompresses the given compressed payload bytes.
     *
     * @param compressed compressed payload bytes
     * @return decompressed payload bytes
     * @throws IOException on decompression failure
     */
    default byte[] decompress(byte[] compressed) throws IOException {
        throw new UnsupportedOperationException("Decompression not supported by " + getClass().getName());
    }
}
