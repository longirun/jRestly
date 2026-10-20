package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.compression.ContentCompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Decompresses HTTP response bodies (ADR-0021, ADR-0076, ADR-0087) when transparent
 * decompression is enabled on the client.
 *
 * <p>Handles {@code gzip}, {@code x-gzip}, {@code deflate} (both RFC 1950 zlib
 * and RFC 1951 raw stream fallback), and custom {@link ContentCompressor}
 * implementations registered in {@link ModuleInfo}.
 *
 * <p>Stripped headers (Content-Encoding and Content-Length) are removed
 * case-insensitively, order is preserved, and the returned header list is
 * immutable (ADR-0076).
 */
final class ResponseDecompressor {

    private ResponseDecompressor() {
    }

    /**
     * Decompresses the response payload if transparent decompression is
     * enabled and {@code Content-Encoding} is present and recognized.
     *
     * @param response the raw wire response; if {@code null}, returns {@code null}
     * @param moduleInfo client module configuration; if {@code null} or decompression is disabled, response is returned as-is
     * @return the decompressed wire response with stripped encoding headers
     * @throws IOException on I/O or decompression failure
     */
    static WireResponse decompress(WireResponse response, ModuleInfo moduleInfo) throws IOException {
        if (moduleInfo == null || !moduleInfo.isDecompressionEnabled() || response == null) {
            return response;
        }

        String contentEncoding = null;
        for (Header header : response.headers()) {
            if (header.name() != null && header.name().equalsIgnoreCase("Content-Encoding")) {
                contentEncoding = header.value();
                break;
            }
        }

        if (contentEncoding == null) {
            return response;
        }

        String encoding = contentEncoding.trim().toLowerCase(Locale.ROOT);
        if ("identity".equals(encoding)) {
            return response;
        }

        ContentCompressor customCompressor = moduleInfo.getContentCompressor(encoding);
        boolean isGzip = "gzip".equals(encoding) || "x-gzip".equals(encoding);
        boolean isDeflate = "deflate".equals(encoding);

        if (!isGzip && !isDeflate && customCompressor == null) {
            return response;
        }

        List<Header> strippedHeaders = new ArrayList<>(response.headers().size());
        for (Header header : response.headers()) {
            if (header.name() != null
                    && (header.name().equalsIgnoreCase("Content-Encoding")
                    || header.name().equalsIgnoreCase("Content-Length"))) {
                continue;
            }
            strippedHeaders.add(header);
        }

        if (response.stream() != null) {
            InputStream decompressedStream;
            if (customCompressor != null) {
                decompressedStream = customCompressor.decompress(response.stream());
            } else if (isGzip) {
                decompressedStream = new GZIPInputStream(response.stream());
            } else {
                decompressedStream = new InflaterInputStream(response.stream());
            }
            return new WireResponse(response.statusCode(), strippedHeaders, null, decompressedStream, response.version(), response.trailers(), response.trailersSupplier());
        }

        byte[] rawBody = response.body();
        if (rawBody == null || rawBody.length == 0) {
            return new WireResponse(response.statusCode(), strippedHeaders, rawBody, null, response.version(), response.trailers(), response.trailersSupplier());
        }

        byte[] decompressedBytes;
        if (customCompressor != null) {
            decompressedBytes = customCompressor.decompress(rawBody);
        } else if (isGzip) {
            decompressedBytes = decompressGzip(rawBody);
        } else {
            decompressedBytes = decompressDeflate(rawBody);
        }
        return new WireResponse(response.statusCode(), strippedHeaders, decompressedBytes, null, response.version(), response.trailers(), response.trailersSupplier());
    }

    private static byte[] decompressGzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gis.readAllBytes();
        }
    }

    private static byte[] decompressDeflate(byte[] compressed) throws IOException {
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
}
