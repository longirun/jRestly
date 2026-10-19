package ru.jrestly.engine.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.LastHttpContent;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.Header;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push-to-pull streaming {@link InputStream} bridge from Netty reactive chunks
 * with credit-based backpressure and guaranteed off-heap buffer release (ADR-0070).
 */
final class NettyChunkedInputStream extends InputStream {

    private static final Object EOF_MARKER = new Object();
    private static final int HIGH_WATERMARK = 8;
    private static final int LOW_WATERMARK = 2;

    private final Channel channel;
    private final ChannelPool pool;
    private final long declaredLength;
    private final DownloadProgressListener progressListener;

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean eof = false;
    private ByteBuf currentChunk = null;
    private long bytesRead = 0;
    private volatile List<Header> trailers = List.of();

    NettyChunkedInputStream(Channel channel, ChannelPool pool, long declaredLength, DownloadProgressListener progressListener) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.declaredLength = declaredLength;
        this.progressListener = progressListener;
    }

    void offerChunk(ByteBuf buf) {
        if (closed.get()) {
            buf.release();
            return;
        }
        queue.offer(buf);
        if (queue.size() >= HIGH_WATERMARK && channel.config().isAutoRead()) {
            channel.config().setAutoRead(false);
        }
    }

    void offerLastContent(LastHttpContent last) {
        if (closed.get()) {
            return;
        }
        if (last.content().isReadable()) {
            offerChunk(last.content().retain());
        }
        if (!last.trailingHeaders().isEmpty()) {
            List<Header> list = new ArrayList<>(last.trailingHeaders().size());
            for (Map.Entry<String, String> entry : last.trailingHeaders()) {
                list.add(new Header(entry.getKey(), entry.getValue()));
            }
            this.trailers = List.copyOf(list);
        }
        queue.offer(EOF_MARKER);
    }

    void offerError(Throwable t) {
        if (closed.get()) {
            return;
        }
        queue.offer(t != null ? t : new IOException("Connection terminated prematurely"));
    }

    List<Header> getTrailers() {
        return eof ? trailers : List.of();
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n == -1 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        if (closed.get()) {
            throw new IOException("Stream closed");
        }
        if (eof) {
            return -1;
        }

        int totalRead = 0;
        while (totalRead < length) {
            if (currentChunk == null || !currentChunk.isReadable()) {
                if (currentChunk != null) {
                    currentChunk.release();
                    currentChunk = null;
                }
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                if (totalRead > 0 && queue.isEmpty()) {
                    return totalRead;
                }
                if (eof) {
                    break;
                }
                Object item;
                try {
                    item = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while reading from stream");
                }
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                if (item == null) {
                    if (!channel.isActive() && queue.isEmpty()) {
                        eof = true;
                        throw new EOFException("Connection closed prematurely");
                    }
                    if (totalRead > 0) {
                        return totalRead;
                    }
                    continue;
                }
                if (item instanceof Throwable t) {
                    eof = true;
                    throw toIOException(t);
                }
                if (item == EOF_MARKER) {
                    if (closed.get()) {
                        throw new IOException("Stream closed");
                    }
                    eof = true;
                    if (progressListener != null) {
                        progressListener.onProgress(bytesRead, declaredLength);
                    }
                    break;
                }
                if (item instanceof ByteBuf buf) {
                    currentChunk = buf;
                    if (queue.size() <= LOW_WATERMARK && !channel.config().isAutoRead()) {
                        channel.config().setAutoRead(true);
                    }
                }
            }

            int toRead = Math.min(length - totalRead, currentChunk.readableBytes());
            currentChunk.readBytes(buffer, offset + totalRead, toRead);
            totalRead += toRead;
            bytesRead += toRead;
            if (progressListener != null) {
                progressListener.onProgress(bytesRead, declaredLength);
            }
        }

        if (closed.get()) {
            throw new IOException("Stream closed");
        }
        return (totalRead == 0 && eof) ? -1 : totalRead;
    }

    @Override
    public int available() {
        return currentChunk != null ? currentChunk.readableBytes() : 0;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (currentChunk != null) {
            currentChunk.release();
            currentChunk = null;
        }
        Object item;
        while ((item = queue.poll()) != null) {
            if (item instanceof ByteBuf buf) {
                buf.release();
            }
        }
        // Wake up any thread blocked in read() immediately
        queue.offer(new IOException("Stream closed"));

        try {
            if (channel.pipeline().get("exchange") != null) {
                channel.pipeline().remove("exchange");
            }
        } catch (Exception ignored) {}

        if (eof && channel.isActive()) {
            pool.release(channel);
        } else {
            channel.close();
            pool.release(channel);
        }
    }

    private static IOException toIOException(Throwable error) {
        return error instanceof IOException ioe ? ioe : new IOException(error);
    }
}
