package ru.jrestly.http;

import java.nio.charset.StandardCharsets;

/**
 * One part of a multipart/form-data body. A text part has no filename,
 * a file part carries binary content.
 */
public record MultipartPart(String name, String filename, String contentType, byte[] content) {

    public static MultipartPart text(String name, String value) {
        return new MultipartPart(name, null, "text/plain; charset=UTF-8", value.getBytes(StandardCharsets.UTF_8));
    }

    public static MultipartPart file(String name, String filename, byte[] content) {
        return new MultipartPart(name, filename, "application/octet-stream", content);
    }
}
