package ru.jrestly.http;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorating {@link HttpRequest.BodyPublisher} that notifies an {@link UploadProgressListener}
 * as byte buffers are consumed and transmitted to the underlying transport.
 */
public final class ProgressBodyPublisher implements HttpRequest.BodyPublisher {

    private final HttpRequest.BodyPublisher delegate;
    private final UploadProgressListener listener;
    private final AtomicBoolean notifiedZero = new AtomicBoolean(false);

    /**
     * Constructs a progress tracking body publisher wrapping the given delegate.
     *
     * @param delegate the underlying body publisher, must not be {@code null}
     * @param listener the upload progress listener, must not be {@code null}
     */
    public ProgressBodyPublisher(HttpRequest.BodyPublisher delegate, UploadProgressListener listener) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public long contentLength() {
        return delegate.contentLength();
    }

    /**
     * Notifies the listener with {@code (0, 0)} if this publisher represents an empty payload (ADR-0030)
     * and has not yet notified progress (for HTTP transport engines that bypass subscription on zero-length bodies).
     */
    public void notifyZeroLengthIfPending() {
        if (contentLength() == 0 && notifiedZero.compareAndSet(false, true)) {
            listener.onProgress(0, 0);
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        delegate.subscribe(new ProgressSubscriber(subscriber, listener, contentLength(), notifiedZero));
    }

    private static final class ProgressSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final Flow.Subscriber<? super ByteBuffer> delegate;
        private final UploadProgressListener listener;
        private final long totalBytes;
        private final AtomicBoolean notifiedZero;
        private final AtomicLong bytesSent = new AtomicLong();

        ProgressSubscriber(Flow.Subscriber<? super ByteBuffer> delegate,
                           UploadProgressListener listener,
                           long totalBytes,
                           AtomicBoolean notifiedZero) {
            this.delegate = delegate;
            this.listener = listener;
            this.totalBytes = totalBytes;
            this.notifiedZero = notifiedZero;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(ByteBuffer item) {
            int len = item != null ? item.remaining() : 0;
            if (len > 0) {
                long current = bytesSent.addAndGet(len);
                listener.onProgress(current, totalBytes);
            }
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (bytesSent.get() == 0 && totalBytes == 0 && notifiedZero.compareAndSet(false, true)) {
                listener.onProgress(0, totalBytes);
            }
            delegate.onComplete();
        }
    }
}
