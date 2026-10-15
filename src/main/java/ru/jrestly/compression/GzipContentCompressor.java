package ru.jrestly.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Standard Zero-Dependency {@link ContentCompressor} implementation for {@code gzip} encoding (ADR-0041).
 */
public final class GzipContentCompressor implements ContentCompressor {

    /**
     * Singleton instance of {@link GzipContentCompressor}.
     */
    public static final GzipContentCompressor INSTANCE = new GzipContentCompressor();

    /**
     * Creates a new instance of {@link GzipContentCompressor}.
     */
    public GzipContentCompressor() {
    }

    @Override
    public String encoding() {
        return "gzip";
    }

    @Override
    public byte[] compress(byte[] uncompressed) throws IOException {
        if (uncompressed == null || uncompressed.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(uncompressed);
        }
        return baos.toByteArray();
    }

    @Override
    public byte[] decompress(byte[] compressed) throws IOException {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gis.readAllBytes();
        }
    }

    @Override
    public InputStream decompress(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream must not be null");
        return new GZIPInputStream(stream);
    }
}
