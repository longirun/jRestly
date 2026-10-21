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

    /**
     * The filename sent on the wire for this part.
     *
     * @return the wire filename
     */
    String fileName();

    /**
     * Content source: {@link Path}, {@code byte[]}, or {@link Supplier} of {@link InputStream}.
     *
     * @return the content source in one of the three supported shapes
     */
    Object content();

    /**
     * Size in bytes if known up front, or {@code null} to derive it from the source
     * or transfer via chunked encoding.
     *
     * @return the content length in bytes, or {@code null} when not known up front
     */
    default Long contentLength() {
        return null;
    }

    /**
     * Creates a file part read from disk on demand.
     *
     * @param file the file to upload; the wire filename is its last path component
     * @return a file part backed by the given file
     */
    static FileContent of(Path file) {
        Objects.requireNonNull(file, "file");
        if (file.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + file);
        }
        return new PathFileContent(file);
    }

    /**
     * Creates a file part from an in-memory byte array.
     *
     * @param fileName the filename sent on the wire
     * @param bytes the part content
     * @return a file part backed by the given bytes
     */
    static FileContent of(String fileName, byte[] bytes) {
        return new BytesFileContent(
                Objects.requireNonNull(fileName, "fileName"),
                Objects.requireNonNull(bytes, "bytes"));
    }

    /**
     * Creates a stream-backed file part of unknown length (chunked transfer
     * when the transport cannot derive it).
     *
     * @param fileName the filename sent on the wire
     * @param source the lazy stream source, opened per attempt
     * @return a file part backed by the given stream source
     */
    static FileContent of(String fileName, Supplier<InputStream> source) {
        return new StreamFileContent(
                Objects.requireNonNull(fileName, "fileName"),
                Objects.requireNonNull(source, "source"),
                null);
    }

    /**
     * Creates a stream-backed file part with a known content length.
     *
     * @param fileName the filename sent on the wire
     * @param source the lazy stream source, opened per attempt
     * @param contentLength the exact content length in bytes, non-negative
     * @return a file part backed by the given stream source
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
