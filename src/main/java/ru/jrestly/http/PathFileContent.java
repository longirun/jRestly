package ru.jrestly.http;

import java.nio.file.Path;

/**
 * Package-private {@link FileContent} implementation backed by a filesystem {@link Path}.
 */
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
