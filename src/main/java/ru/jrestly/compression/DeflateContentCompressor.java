package ru.jrestly.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Standard Zero-Dependency {@link ContentCompressor} implementation for {@code deflate} encoding (ADR-0041).
 * Supports standard RFC 1950 zlib format with automatic fallback to RFC 1951 raw deflate format during decompression.
 */
public final class DeflateContentCompressor implements ContentCompressor {

    /**
     * Singleton instance of {@link DeflateContentCompressor}.
     */
    public static final DeflateContentCompressor INSTANCE = new DeflateContentCompressor();

    /**
     * Creates a new instance of {@link DeflateContentCompressor}.
     */
    public DeflateContentCompressor() {
    }

    @Override
    public String encoding() {
        return "deflate";
    }

    @Override
    public byte[] compress(byte[] uncompressed) throws IOException {
        if (uncompressed == null || uncompressed.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(uncompressed);
        }
        return baos.toByteArray();
    }

    @Override
    public byte[] decompress(byte[] compressed) throws IOException {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }
        try {
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                return iis.readAllBytes();
            }
        } catch (ZipException e) {
            Inflater rawInflater = new Inflater(true);
            try (InflaterInputStream rawIisIn = new InflaterInputStream(new ByteArrayInputStream(compressed), rawInflater)) {
                return rawIisIn.readAllBytes();
            } finally {
                rawInflater.end();
            }
        }
    }

    @Override
    public InputStream decompress(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream must not be null");
        return new InflaterInputStream(stream);
    }
}
