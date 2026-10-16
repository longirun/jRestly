package ru.jrestly.engine.apache5;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Progress wrapper over an {@link InputStream}, shared by the upload and download paths of
 * {@link Apache5HttpEngine}: counts the bytes passing through and reports
 * {@code (bytesRead, total)} after every read; {@code total} is the declared body length or
 * {@code -1} when unknown (chunked transfer). A zero-length body fires exactly one
 * {@code (0, 0)} event at EOF (ADR-0030/0033).
 */
final class CountingInputStream extends FilterInputStream {

    /**
     * The listener shape shared by {@link ru.jrestly.http.UploadProgressListener} and
     * {@link ru.jrestly.http.DownloadProgressListener}.
     */
    @FunctionalInterface
    interface ProgressSink {

        /**
         * Reports the number of bytes transferred so far.
         *
         * @param bytesSoFar the bytes transferred so far
         * @param total the declared total, or {@code -1} when unknown
         */
        void onProgress(long bytesSoFar, long total);
    }

    private final long total;
    private final ProgressSink sink;
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicBoolean zeroNotified = new AtomicBoolean();

    CountingInputStream(InputStream in, long total, ProgressSink sink) {
        super(in);
        this.total = total;
        this.sink = sink;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b != -1) {
            sink.onProgress(bytesRead.addAndGet(1), total);
        } else if (total == 0 && zeroNotified.compareAndSet(false, true)) {
            sink.onProgress(0, 0);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = in.read(buffer, offset, length);
        if (n > 0) {
            sink.onProgress(bytesRead.addAndGet(n), total);
        } else if (n == -1 && total == 0 && zeroNotified.compareAndSet(false, true)) {
            sink.onProgress(0, 0);
        }
        return n;
    }
}
