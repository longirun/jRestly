package ru.jrestly.compression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry and utility methods for standard {@link ContentCompressor} implementations (ADR-0041).
 */
public final class ContentCompressors {

    private static final Map<String, ContentCompressor> BUILTIN;

    static {
        Map<String, ContentCompressor> map = new LinkedHashMap<>();
        map.put("gzip", GzipContentCompressor.INSTANCE);
        map.put("x-gzip", GzipContentCompressor.INSTANCE);
        map.put("deflate", DeflateContentCompressor.INSTANCE);
        BUILTIN = Collections.unmodifiableMap(map);
    }

    private ContentCompressors() {
        // utility class
    }

    /**
     * Resolves the standard {@link ContentCompressor} for the given {@link CompressionAlgorithm}.
     *
     * @param algorithm the compression algorithm
     * @return the compressor instance, or {@code null} if {@link CompressionAlgorithm#NONE} or unknown
     */
    public static ContentCompressor get(CompressionAlgorithm algorithm) {
        if (algorithm == null || algorithm == CompressionAlgorithm.NONE) {
            return null;
        }
        return get(algorithm.encoding());
    }

    /**
     * Resolves the standard {@link ContentCompressor} for the given encoding token.
     *
     * @param encoding content encoding token (e.g. {@code "gzip"}, {@code "deflate"})
     * @return the compressor instance, or {@code null} if not a built-in encoding
     */
    public static ContentCompressor get(String encoding) {
        if (encoding == null) {
            return null;
        }
        return BUILTIN.get(encoding.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns an unmodifiable view of built-in compressors.
     *
     * @return map of encoding name to compressor
     */
    public static Map<String, ContentCompressor> builtin() {
        return BUILTIN;
    }
}
