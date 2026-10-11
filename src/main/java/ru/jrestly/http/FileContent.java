package ru.jrestly.http;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents a file part in a multipart/form-data request.
 * <p>
 * Combines the filename sent on the wire with the underlying content source
 * (disk {@link Path}, in-memory {@code byte[]}, or an {@link InputStream} {@link Supplier}).
 */
public interface FileContent {

    String fileName();

    /**
     * Content source: {@link Path}, {@code byte[]}, or {@link Supplier} of {@link InputStream}.
     */
    Object content();

    /**
     * Size in bytes if known up front, or {@code null} to derive it from the source
     * or transfer via chunked encoding.
     */
    default Long contentLength() {
        return null;
    }

    /** Creates a file part read from disk on demand. */
    static FileContent of(Path file) {
        Objects.requireNonNull(file, "file");
        if (file.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + file);
        }
        return new PathFileContent(file);
    }

    static FileContent of(String fileName, byte[] bytes) {
        return new BytesFileContent(
                Objects.requireNonNull(fileName, "fileName"),
                Objects.requireNonNull(bytes, "bytes"));
    }

    static FileContent of(String fileName, Supplier<InputStream> source) {
        return new StreamFileContent(
                Objects.requireNonNull(fileName, "fileName"),
                Objects.requireNonNull(source, "source"),
                null);
    }

    /**
     * Creates a stream-backed file part with a known content length.
     */
    static FileContent of(String fileName, Supplier<InputStream> source, long contentLength) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(source, "source");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be non-negative: " + contentLength);
        }
        return new StreamFileContent(fileName, source, contentLength);
    }
}

record PathFileContent(Path file) implements FileContent {
    @Override
    public String fileName() {
        Path name = file.getFileName();
        return name != null ? name.toString() : file.toString();
    }

    @Override
    public Object content() {
        return file;
    }
}

record BytesFileContent(String fileName, byte[] bytes) implements FileContent {
    @Override
    public Object content() {
        return bytes;
    }
}

record StreamFileContent(String fileName, Supplier<InputStream> source, Long contentLength) implements FileContent {
    @Override
    public Object content() {
        return source;
    }
}
