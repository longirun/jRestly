package ru.jrestly.http;

/**
 * Functional listener for tracking request body upload progress as bytes are transmitted
 * over the network transport.
 * <p>
 * May be declared as an unannotated method parameter on controller interfaces:
 * <pre>{@code
 * @Post(value = "/files", requestType = RequestType.MULTIPART_FORM_DATA)
 * UploadResult upload(@MultipartFormFile(partName = "file") FileContent file,
 *                     UploadProgressListener progress);
 * }</pre>
 */
@FunctionalInterface
public interface UploadProgressListener {

    /**
     * Invoked periodically as bytes are written to the network transport.
     *
     * @param bytesSent the total number of bytes sent so far for the current attempt
     * @param totalBytes the total length in bytes of the request body, or {@code -1} if unknown (e.g. chunked transfer)
     */
    void onProgress(long bytesSent, long totalBytes);
}
