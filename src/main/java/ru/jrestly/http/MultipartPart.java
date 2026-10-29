package ru.jrestly.http;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A single part of a multipart/form-data request.
 */
public record MultipartPart(String name, String filename, String contentType, Object content, Long contentLength) {

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

    public static MultipartPart text(String name, String value) {
        return text(name, value, StandardCharsets.UTF_8);
    }

    public static MultipartPart text(String name, String value, java.nio.charset.Charset charset) {
        java.util.Objects.requireNonNull(charset, "charset");
        byte[] bytes = value != null ? value.getBytes(charset) : new byte[0];
        return new MultipartPart(name, null, "text/plain; charset=" + charset.name(), bytes, null);
    }

    public static MultipartPart part(String name, String contentType, byte[] content) {
        return new MultipartPart(name, null, contentType, content != null ? content : new byte[0], null);
    }

    public static MultipartPart part(String name, String filename, String contentType, byte[] content) {
        return new MultipartPart(name, filename, contentType, content != null ? content : new byte[0], null);
    }

    public static MultipartPart file(String name, Path file) {
        java.util.Objects.requireNonNull(file, "file");
        if (file.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + file);
        }
        return file(name, file.getFileName().toString(), null, file, null);
    }

    public static MultipartPart file(String name, String filename, byte[] content) {
        return file(name, filename, null, content, null);
    }

    public static MultipartPart file(String name, String filename, Supplier<InputStream> content) {
        return file(name, filename, null, content, null);
    }

    public static MultipartPart file(String name, String filename, Supplier<InputStream> content, long contentLength) {
        return file(name, filename, null, content, contentLength);
    }

    public static MultipartPart file(String name, String filename, String contentType, Object content, Long contentLength) {
        return new MultipartPart(name, filename,
                contentType != null ? contentType : contentTypeFor(filename),
                content, contentLength);
    }

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
