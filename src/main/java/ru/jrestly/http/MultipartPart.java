package ru.jrestly.http;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A single part of a multipart/form-data request.
 *
 * @param name the part's form field name
 * @param filename the filename sent on the wire, or {@code null} for non-file (text) parts
 * @param contentType the part's MIME type, or {@code null} to derive it from {@code filename}'s extension
 * @param content the part content: {@code byte[]}, or a {@link Supplier} of {@link InputStream}
 * @param contentLength the exact content length in bytes, or {@code null} to derive it
 */
public record MultipartPart(String name, String filename, String contentType, Object content, Long contentLength) {

    /**
     * Canonical constructor: validates the part's framing invariants —
     * CR/LF in {@code name}, {@code filename} or {@code contentType} would
     * smuggle extra header lines into the part framing, and a non-{@code null}
     * {@code contentLength} must be non-negative.
     *
     * @throws IllegalArgumentException on CR/LF in a framing string, or a negative known length
     */
    public MultipartPart {
        java.util.Objects.requireNonNull(name, "name");
        // CR/LF in name, filename or contentType would smuggle extra header lines into the part framing
        if (containsCrLf(name) || containsCrLf(filename) || containsCrLf(contentType)) {
            throw new IllegalArgumentException(
                    "CR/LF is not allowed in a multipart part name, filename, or contentType: name="
                            + name + ", filename=" + filename + ", contentType=" + contentType);
        }
        if (contentLength != null && contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be non-negative: " + contentLength);
        }
    }

    private static boolean containsCrLf(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    private static final String DEFAULT_FILE_CONTENT_TYPE = "application/octet-stream";

    // Default MIME types by file extension
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /**
     * Creates a plain-text part (no filename, {@code text/plain; charset=UTF-8}).
     *
     * @param name the part's form field name
     * @param value the part content
     * @return a text part carrying the given value
     */
    public static MultipartPart text(String name, String value) {
        return new MultipartPart(name, null, "text/plain; charset=UTF-8", value.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Creates a file part read from disk on demand.
     *
     * @param name the part's form field name
     * @param file the file to upload; the wire filename is its last path component
     * @return a file part backed by the given file
     */
    public static MultipartPart file(String name, Path file) {
        java.util.Objects.requireNonNull(file, "file");
        if (file.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + file);
        }
        return file(name, file.getFileName().toString(), null, file, null);
    }

    /**
     * Creates a file part from an in-memory byte array.
     *
     * @param name the part's form field name
     * @param filename the filename sent on the wire; the MIME type is derived from its extension
     * @param content the part content
     * @return a file part backed by the given bytes
     */
    public static MultipartPart file(String name, String filename, byte[] content) {
        return file(name, filename, null, content, null);
    }

    /**
     * Creates a stream-backed file part of unknown length (chunked transfer
     * when the transport cannot derive it).
     *
     * @param name the part's form field name
     * @param filename the filename sent on the wire; the MIME type is derived from its extension
     * @param content the lazy stream source, opened per attempt
     * @return a file part backed by the given stream source
     */
    public static MultipartPart file(String name, String filename, Supplier<InputStream> content) {
        return file(name, filename, null, content, null);
    }

    /**
     * Creates a stream-backed file part with a known content length.
     *
     * @param name the part's form field name
     * @param filename the filename sent on the wire; the MIME type is derived from its extension
     * @param content the lazy stream source, opened per attempt
     * @param contentLength the exact content length in bytes, non-negative
     * @return a file part backed by the given stream source
     */
    public static MultipartPart file(String name, String filename, Supplier<InputStream> content, long contentLength) {
        return file(name, filename, null, content, contentLength);
    }

    /**
     * Creates a file part in full control of its framing fields.
     *
     * @param name the part's form field name
     * @param filename the filename sent on the wire, or {@code null} for a non-file part
     * @param contentType the part's MIME type, or {@code null} to derive it from {@code filename}'s extension
     * @param content the part content: {@code byte[]}, or a {@link Supplier} of {@link InputStream}
     * @param contentLength the exact content length in bytes, or {@code null} to derive it
     * @return a part with the given framing fields
     */
    public static MultipartPart file(String name, String filename, String contentType, Object content, Long contentLength) {
        return new MultipartPart(name, filename,
                contentType != null ? contentType : contentTypeFor(filename),
                content, contentLength);
    }

    /**
     * Resolves the MIME type for a filename by its extension (the built-in
     * extension table, {@code application/octet-stream} otherwise).
     *
     * @param fileName the filename to resolve; may be {@code null}
     * @return the MIME type for the filename's extension, or {@code application/octet-stream}
     */
    public static String contentTypeFor(String fileName) {
        if (fileName == null) {
            return DEFAULT_FILE_CONTENT_TYPE;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return DEFAULT_FILE_CONTENT_TYPE;
        }
        return MIME_TYPES.getOrDefault(fileName.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT_FILE_CONTENT_TYPE);
    }
}
