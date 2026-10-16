package ru.jrestly.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Terminal streaming hook (ADR-0059): intercepts stream consumption (EOF or close)
 * to request late-arriving HTTP trailers and populate {@link ResponseHeaders}.
 *
 * <p>Execution is idempotent, thread-safe, and triggered at most once via CAS.
 */
final class TrailerCompletingInputStream extends FilterInputStream {

    private final Supplier<List<Header>> trailersSupplier;
    private final Consumer<List<Header>> trailersConsumer;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    TrailerCompletingInputStream(InputStream in,
                                 Supplier<List<Header>> trailersSupplier,
                                 Consumer<List<Header>> trailersConsumer) {
        super(Objects.requireNonNull(in, "in"));
        this.trailersSupplier = trailersSupplier != null ? trailersSupplier : List::of;
        this.trailersConsumer = trailersConsumer != null ? trailersConsumer : trailers -> {};
    }

    private void completeTrailers() {
        if (completed.compareAndSet(false, true)) {
            try {
                List<Header> trailers = trailersSupplier.get();
                if (trailers != null && !trailers.isEmpty()) {
                    trailersConsumer.accept(trailers);
                }
            } catch (Throwable ignored) {
                // best-effort per ADR-0059
            }
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b == -1) {
            completeTrailers();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n == -1) {
            completeTrailers();
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        try {
            completeTrailers();
        } finally {
            super.close();
        }
    }
}
