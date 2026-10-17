package ru.jrestly.engine;

import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.UploadProgressListener;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Options and execution parameters passed to {@link HttpEngine} for a single wire exchange attempt.
 *
 * @param timeout the effective request deadline, or {@code null} when the call has no timeout
 * @param version the requested HTTP protocol version, or {@code null} to negotiate/use engine default
 * @param streamResponse {@code true} if the response body should be delivered as a live {@link java.io.InputStream}
 *                       instead of materializing all bytes in memory
 * @param uploadProgressListener optional progress listener for tracking outbound request body transmission
 * @param downloadProgressListener optional progress listener for tracking inbound response body download
 */
public record HttpEngineOptions(
        Duration timeout,
        HttpClient.Version version,
        boolean streamResponse,
        UploadProgressListener uploadProgressListener,
        DownloadProgressListener downloadProgressListener
) {

    /**
     * Creates a default {@link HttpEngineOptions} with no custom timeout, version or progress listeners,
     * and byte-buffering response mode.
     *
     * @return default options
     */
    public static HttpEngineOptions defaults() {
        return new HttpEngineOptions(null, null, false, null, null);
    }

    /**
     * Returns a new {@link Builder} to construct custom engine options.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link HttpEngineOptions}.
     */
    public static final class Builder {
        private Duration timeout;
        private HttpClient.Version version;
        private boolean streamResponse;
        private UploadProgressListener uploadProgressListener;
        private DownloadProgressListener downloadProgressListener;

        private Builder() {
        }

        /**
         * Sets the effective request timeout.
         *
         * @param timeout the timeout, or {@code null} for engine default
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the HTTP protocol version.
         *
         * @param version the HTTP version, or {@code null} for engine default
         * @return this builder
         */
        public Builder version(HttpClient.Version version) {
            this.version = version;
            return this;
        }

        /**
         * Sets whether the response body should be streamed.
         *
         * @param streamResponse {@code true} to stream as {@link java.io.InputStream}
         * @return this builder
         */
        public Builder streamResponse(boolean streamResponse) {
            this.streamResponse = streamResponse;
            return this;
        }

        /**
         * Sets the upload progress listener.
         *
         * @param uploadProgressListener the upload progress listener, or {@code null}
         * @return this builder
         */
        public Builder uploadProgressListener(UploadProgressListener uploadProgressListener) {
            this.uploadProgressListener = uploadProgressListener;
            return this;
        }

        /**
         * Sets the download progress listener.
         *
         * @param downloadProgressListener the download progress listener, or {@code null}
         * @return this builder
         */
        public Builder downloadProgressListener(DownloadProgressListener downloadProgressListener) {
            this.downloadProgressListener = downloadProgressListener;
            return this;
        }

        /**
         * Builds an immutable {@link HttpEngineOptions} instance.
         *
         * @return the constructed options
         */
        public HttpEngineOptions build() {
            return new HttpEngineOptions(timeout, version, streamResponse, uploadProgressListener, downloadProgressListener);
        }
    }
}
