package ru.jrestly.http;

import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorating {@link BodyHandler} that notifies a {@link DownloadProgressListener}
 * as byte buffers are received from the underlying transport.
 *
 * @param <T> the body response type
 */
public final class ProgressBodyHandler<T> implements BodyHandler<T> {

    private final BodyHandler<T> delegate;
    private final DownloadProgressListener listener;

    /**
     * Constructs a progress tracking body handler wrapping the given delegate.
     *
     * @param delegate the underlying body handler, must not be {@code null}
     * @param listener the progress listener, must not be {@code null}
     */
    public ProgressBodyHandler(BodyHandler<T> delegate, DownloadProgressListener listener) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public BodySubscriber<T> apply(ResponseInfo responseInfo) {
        Objects.requireNonNull(responseInfo, "responseInfo");
        BodySubscriber<T> downstreamSubscriber = delegate.apply(responseInfo);
        long totalBytes = responseInfo.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
        return new ProgressBodySubscriber<>(downstreamSubscriber, listener, totalBytes);
    }

    /**
     * Decorating {@link BodySubscriber} calculating monotonic progress without mutating buffers.
     *
     * @param <T> the body response type
     */
    public static final class ProgressBodySubscriber<T> implements BodySubscriber<T> {

        private final BodySubscriber<T> delegate;
        private final DownloadProgressListener listener;
        private final long totalBytes;
        private final AtomicLong bytesRead = new AtomicLong();

        /**
         * Constructs a progress tracking body subscriber.
         *
         * @param delegate the downstream subscriber, must not be {@code null}
         * @param listener the progress listener, must not be {@code null}
         * @param totalBytes expected total bytes, or {@code -1} if unknown
         */
        public ProgressBodySubscriber(BodySubscriber<T> delegate, DownloadProgressListener listener, long totalBytes) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.listener = Objects.requireNonNull(listener, "listener");
            this.totalBytes = totalBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (item != null) {
                long chunk = 0;
                for (ByteBuffer buf : item) {
                    if (buf != null) {
                        chunk += buf.remaining();
                    }
                }
                if (chunk > 0) {
                    long current = bytesRead.addAndGet(chunk);
                    listener.onProgress(current, totalBytes);
                }
            }
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (bytesRead.get() == 0 && totalBytes == 0) {
                listener.onProgress(0, totalBytes);
            }
            delegate.onComplete();
        }

        /**
         * Returns the number of bytes read so far.
         *
         * @return bytes read
         */
        public long bytesRead() {
            return bytesRead.get();
        }

        /**
         * Returns the total expected bytes or {@code -1} if unknown.
         *
         * @return total bytes
         */
        public long totalBytes() {
            return totalBytes;
        }
    }
}
