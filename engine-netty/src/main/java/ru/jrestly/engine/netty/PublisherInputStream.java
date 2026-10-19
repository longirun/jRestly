package ru.jrestly.engine.netty;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts a JDK {@link HttpRequest.BodyPublisher} to an {@link InputStream}
 * with bounded credit-based backpressure (ADR-0070).
 */
final class PublisherInputStream extends InputStream {

    private static final Object END_OF_STREAM = new Object();
    private static final int MAX_IN_FLIGHT_CHUNKS = 4;
    private static final long POLL_TIMEOUT_MS = 100;

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private byte[] current = new byte[0];
    private int position;
    private volatile boolean eof;

    PublisherInputStream(HttpRequest.BodyPublisher publisher) {
        publisher.subscribe(new DrainingSubscriber());
    }

    @Override
    public int read() throws IOException {
        if (position >= current.length) {
            byte[] next = nextChunk();
            if (next == null) {
                return -1;
            }
            current = next;
            position = 0;
        }
        return current[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException("offset=" + offset + " length=" + length);
        }
        if (length == 0) {
            return 0;
        }
        int total = 0;
        while (total < length) {
            if (position >= current.length) {
                byte[] next = nextChunk();
                if (next == null) {
                    break;
                }
                current = next;
                position = 0;
            }
            int n = Math.min(length - total, current.length - position);
            System.arraycopy(current, position, buffer, offset + total, n);
            position += n;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public int available() {
        return current.length - position;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        while (queue.poll() != null) {
            // drain remaining items
        }
        Flow.Subscription sub = subscription.get();
        if (sub != null) {
            sub.cancel();
        }
    }

    private byte[] nextChunk() throws IOException {
        if (eof) {
            return null;
        }
        Throwable failed = failure.get();
        if (failed != null) {
            eof = true;
            throw toIOException(failed);
        }
        try {
            while (true) {
                Object item = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (item == END_OF_STREAM) {
                    eof = true;
                    return null;
                }
                if (item instanceof FailureHolder holder) {
                    eof = true;
                    throw toIOException(holder.error);
                }
                if (item != null) {
                    releaseCredit();
                    return (byte[]) item;
                }
                if (closed.get()) {
                    eof = true;
                    return null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private static IOException toIOException(Throwable error) {
        return error instanceof IOException ioe ? ioe : new IOException(error);
    }

    private void releaseCredit() {
        if (closed.get()) {
            return;
        }
        Flow.Subscription s = subscription.get();
        if (s != null) {
            s.request(1);
        }
    }

    private final class DrainingSubscriber implements Flow.Subscriber<ByteBuffer> {
        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription.set(s);
            s.request(MAX_IN_FLIGHT_CHUNKS);
        }

        @Override
        public void onNext(ByteBuffer item) {
            if (closed.get()) {
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            queue.offer(chunk);
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            queue.offer(new FailureHolder(throwable));
        }

        @Override
        public void onComplete() {
            queue.offer(END_OF_STREAM);
        }
    }

    private record FailureHolder(Throwable error) {}
}
