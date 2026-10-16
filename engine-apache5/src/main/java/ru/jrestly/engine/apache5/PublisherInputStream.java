package ru.jrestly.engine.apache5;

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
 * Pulls a JDK {@link HttpRequest.BodyPublisher} into a blocking {@link InputStream} with
 * credit-based backpressure (at most {@link #MAX_IN_FLIGHT_CHUNKS} chunks queued) — the
 * Apache5 twin of the OkHttp engine's adapter (ADR-0058: the JDK publisher is the only
 * foreign type in the wire model, so every external engine drains it the same way).
 *
 * <p>The queue is an unbounded {@link LinkedBlockingQueue}: entries are bounded by the
 * credit scheme, and the non-blocking {@code offer} is what keeps request-driven
 * publishers (the JDK's {@code BodyPublishers.ofInputStream}/concat, the core's
 * multipart body) from deadlocking — those emit synchronously inside {@code request()},
 * so a blocking {@code put} on a full queue would wait for the reader that has not
 * started yet. The scheme: the subscriber starts with {@code MAX_IN_FLIGHT_CHUNKS}
 * credits; every chunk the reader consumes releases exactly one credit, so the queue
 * never holds more than the cap. Publishers that emit from another thread are served
 * the same way.
 *
 * <p>The end marker is <em>sticky</em>: the {@code END_OF_STREAM} chunk (or a failure) is
 * consumed exactly once and every subsequent read returns EOF immediately instead of
 * polling an empty queue.
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
    // sticky end-of-stream: the END marker (and any error) is consumed exactly once,
    // subsequent reads must keep returning EOF instead of polling an empty queue
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
        // drain what the publisher has already produced so it stops, then cancel so a
        // request-driven source (e.g. a file InputStream) is released
        while (queue.poll() != null) {
            // drain
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
                    // closed mid-stream without the publisher finishing: treat as EOF
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

    // one credit per consumed chunk: the publisher may keep at most
    // MAX_IN_FLIGHT_CHUNKS chunks ahead of the reader
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
            // the initial credits: a request-driven publisher emits these chunks
            // synchronously in the constructor; the queue stays capped by the
            // credit scheme (see the class javadoc)
            s.request(MAX_IN_FLIGHT_CHUNKS);
        }

        @Override
        public void onNext(ByteBuffer item) {
            if (closed.get()) {
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            // never blocks: the queue is unbounded, the credit cap bounds its size
            queue.offer(chunk);
        }

        @Override
        public void onError(Throwable error) {
            queue.offer(new FailureHolder(error));
        }

        @Override
        public void onComplete() {
            queue.offer(END_OF_STREAM);
        }
    }

    private record FailureHolder(Throwable error) {
    }
}
