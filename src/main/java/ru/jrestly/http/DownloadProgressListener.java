package ru.jrestly.http;

/**
 * Functional listener for tracking response body download progress as bytes are received
 * from the network transport.
 * <p>
 * May be declared as an unannotated method parameter on controller interfaces:
 * <pre>{@code
 * @Get("/files/{id}")
 * InputStream download(@PathVariable(name = "id") String id,
 *                      DownloadProgressListener progress);
 * }</pre>
 */
@FunctionalInterface
public interface DownloadProgressListener {

    /**
     * Invoked periodically as bytes are received from the network transport.
     *
     * @param bytesRead the total number of bytes received so far for the current attempt
     * @param totalBytes the total length in bytes of the response body as reported by
     *                   the {@code Content-Length} header, or {@code -1} if unknown (e.g. chunked transfer)
     */
    void onProgress(long bytesRead, long totalBytes);
}
