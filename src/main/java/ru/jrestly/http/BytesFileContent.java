package ru.jrestly.http;

/**
 * Package-private {@link FileContent} implementation backed by an in-memory byte array.
 */
record BytesFileContent(String fileName, byte[] bytes) implements FileContent {

    @Override
    public Object content() {
        return bytes;
    }
}
