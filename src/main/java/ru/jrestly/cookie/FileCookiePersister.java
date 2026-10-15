package ru.jrestly.cookie;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.HttpCookie;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * File-backed implementation of {@link CookiePersister} (ADR-0039).
 * <p>
 * Stores cookies in RFC 6265 compliant text format. File writes use atomic rename
 * where supported by the underlying filesystem to prevent partial writes during unexpected termination.
 */
public final class FileCookiePersister implements CookiePersister {

    private final Path path;
    private final Charset charset;
    private final Object lock = new Object();

    /**
     * Constructs a new file persister with the specified path and UTF-8 charset.
     *
     * @param path target file path, not {@code null}
     */
    public FileCookiePersister(Path path) {
        this(path, StandardCharsets.UTF_8);
    }

    /**
     * Constructs a new file persister with the specified path and charset.
     *
     * @param path target file path, not {@code null}
     * @param charset character encoding, not {@code null}
     */
    public FileCookiePersister(Path path, Charset charset) {
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
    }

    /**
     * Creates a persister targeting the given file path with UTF-8 encoding.
     *
     * @param path target file path, not {@code null}
     * @return a new file persister
     */
    public static FileCookiePersister of(Path path) {
        return new FileCookiePersister(path);
    }

    /**
     * Creates a persister targeting the given file with UTF-8 encoding.
     *
     * @param file target file, not {@code null}
     * @return a new file persister
     */
    public static FileCookiePersister of(File file) {
        Objects.requireNonNull(file, "file must not be null");
        return new FileCookiePersister(file.toPath());
    }

    /**
     * Returns the target file path.
     *
     * @return the file path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Returns the configured charset.
     *
     * @return the charset
     */
    public Charset getCharset() {
        return charset;
    }

    @Override
    public List<HttpCookie> loadAll() {
        synchronized (lock) {
            if (!Files.exists(path)) {
                return List.of();
            }
            try (var reader = Files.newBufferedReader(path, charset)) {
                return CookieCodec.read(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read cookies from " + path, e);
            }
        }
    }

    @Override
    public void saveAll(Collection<HttpCookie> cookies) {
        Objects.requireNonNull(cookies, "cookies must not be null");
        synchronized (lock) {
            Path parent = path.getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tempDir = parent != null ? parent : Path.of(".");
                Path tempFile = Files.createTempFile(tempDir, "jrestly-cookies-", ".tmp");
                try {
                    try (Writer writer = Files.newBufferedWriter(tempFile, charset)) {
                        CookieCodec.write(writer, cookies);
                    }
                    try {
                        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save cookies to " + path, e);
            }
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clear cookie file " + path, e);
            }
        }
    }
}
