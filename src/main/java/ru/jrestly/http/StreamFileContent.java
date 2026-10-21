package ru.jrestly.http;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Package-private {@link FileContent} implementation backed by an {@link InputStream} {@link Supplier}.
 */
record StreamFileContent(String fileName, Supplier<InputStream> source, Long contentLength) implements FileContent {

    @Override
    public Object content() {
        return source;
    }
}
