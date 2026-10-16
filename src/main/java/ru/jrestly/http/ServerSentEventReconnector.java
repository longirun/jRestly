package ru.jrestly.http;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Lazy EventSource-style reconnection over {@link ServerSentEvent} streams
 * (ADR-0059, phase 1).
 *
 * <p>Concatenates the events of successive connections opened by the
 * user-supplied opener: a mid-stream {@link UncheckedIOException}, a failing
 * opener, or a clean EOF triggers a bounded reconnect with the last seen
 * event id replayed as {@code Last-Event-ID}; waits and the {@code [DONE]}
 * termination follow the {@link ReconnectPolicy}. Reconnection runs on the
 * consumer's thread — no hidden executors.
 */
final class ServerSentEventReconnector implements Iterator<ServerSentEvent> {

    private final Function<String, Stream<ServerSentEvent>> opener;
    private final ReconnectPolicy policy;
    private final Random random = new Random();

    private final Duration stallTimeout;
    private final long stallTimeoutNanos;
    private final Object monitor = new Object();
    private volatile boolean waitingForData;
    private volatile long waitStartNanos;
    private volatile boolean stalled;
    private volatile Stream<ServerSentEvent> currentStreamRef;

    private Stream<ServerSentEvent> current;
    private Iterator<ServerSentEvent> currentIterator;
    private ServerSentEvent nextEvent;
    private String lastEventId;
    private Long serverRetryMillis;
    private long previousDelayMillis;
    private int reconnectAttempt;
    private boolean doneSeen;
    private volatile boolean finished;
    private volatile boolean closed;

    private ServerSentEventReconnector(Function<String, Stream<ServerSentEvent>> opener, ReconnectPolicy policy) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy.hasStallTimeout()) {
            this.stallTimeout = policy.stallTimeout();
            this.stallTimeoutNanos = this.stallTimeout.toNanos();
            Thread.ofVirtual().name("jrestly-sse-stall-guard").start(this::watchdogLoop);
        } else {
            this.stallTimeout = null;
            this.stallTimeoutNanos = 0;
        }
    }

    /**
     * Creates a lazy reconnecting stream over the connections opened by
     * {@code opener}; closing the returned stream closes the current
     * connection and cancels reconnection.
     *
     * @param opener the connection factory receiving the nullable
     *               {@code Last-Event-ID} value
     * @param policy the reconnection policy
     * @return a lazy concatenation of the events of successive connections
     */
    static Stream<ServerSentEvent> reconnecting(
            Function<String, Stream<ServerSentEvent>> opener, ReconnectPolicy policy) {
        ServerSentEventReconnector reconnector = new ServerSentEventReconnector(opener, policy);
        Spliterator<ServerSentEvent> spliterator = Spliterators.spliteratorUnknownSize(
                reconnector, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false).onClose(reconnector::close);
    }

    @Override
    public boolean hasNext() {
        if (nextEvent != null) {
            return true;
        }
        if (finished || closed) {
            return false;
        }
        nextEvent = advance();
        if (nextEvent == null) {
            finished = true;
            notifyMonitor();
            return false;
        }
        return true;
    }

    @Override
    public ServerSentEvent next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        ServerSentEvent event = nextEvent;
        nextEvent = null;
        return event;
    }

    private ServerSentEvent advance() {
        while (!finished && !closed) {
            if (doneSeen && policy.isStopOnDone()) {
                return null;
            }

            Throwable failure = null;
            try {
                if (currentIterator == null) {
                    current = Objects.requireNonNull(opener.apply(lastEventId), "opener must not return null");
                    currentStreamRef = current;
                    currentIterator = current.iterator();
                }
                markWaitStart();
                boolean hasNext;
                try {
                    hasNext = currentIterator.hasNext();
                } finally {
                    markWaitEnd();
                }
                if (hasNext) {
                    ServerSentEvent event = currentIterator.next();
                    observe(event);
                    return event;
                }
            } catch (UncheckedIOException e) {
                if (stalled) {
                    stalled = false;
                    failure = new UncheckedIOException(
                            "SSE stream stalled: no data received for " + stallTimeout,
                            new SocketTimeoutException("Read timed out after " + stallTimeout.toMillis() + " ms"));
                } else {
                    failure = e;
                }
            } catch (RuntimeException e) {
                closeCurrent(false);
                throw e;
            }

            closeCurrent(false);
            if (!reconnect(failure)) {
                return null;
            }
        }
        return null;
    }

    private void observe(ServerSentEvent event) {
        if (event.id() != null) {
            lastEventId = event.id();
        }
        if (event.retry() != null) {
            serverRetryMillis = event.retry();
        }
        if (event.isDone()) {
            doneSeen = true;
            if (policy.isStopOnDone()) {
                closeCurrent(false);
            }
        }
    }

    /**
     * Decides and performs the wait before the next connection.
     *
     * @param failure the failure that ended the previous connection, or
     *                {@code null} after a clean EOF
     * @return {@code true} when a new connection should be opened
     * @throws UncheckedIOException when the attempts are exhausted and the
     *                              last ending carried a failure
     */
    private boolean reconnect(Throwable failure) {
        if (closed) {
            finished = true;
            notifyMonitor();
            return false;
        }
        if (reconnectAttempt >= policy.maxAttempts()) {
            finished = true;
            notifyMonitor();
            if (failure != null) {
                throw (UncheckedIOException) failure;
            }
            return false;
        }
        reconnectAttempt++;
        long delayMillis = policy.nextDelayMillis(reconnectAttempt, serverRetryMillis, previousDelayMillis, random);
        previousDelayMillis = delayMillis;
        policy.getOnReconnect().accept(
                new ReconnectInfo(reconnectAttempt, lastEventId, Duration.ofMillis(delayMillis), failure));
        sleep(delayMillis);
        if (closed) {
            finished = true;
            notifyMonitor();
            return false;
        }
        return true;
    }

    private static void sleep(long delayMillis) {
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(
                    "Interrupted while waiting to reconnect the SSE stream",
                    new InterruptedIOException("interrupted while waiting to reconnect"));
        }
    }

    private void watchdogLoop() {
        while (!closed && !finished) {
            Stream<ServerSentEvent> toAbort = null;
            synchronized (monitor) {
                while (!waitingForData && !closed && !finished) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed || finished) {
                    return;
                }

                long remainingNanos = stallTimeoutNanos - (System.nanoTime() - waitStartNanos);
                if (remainingNanos > 0) {
                    try {
                        monitor.wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                stalled = true;
                toAbort = currentStreamRef;
            }
            // Close outside the monitor: a foreign close may block briefly, and
            // the consumer must be free to unblock (markWaitEnd locks nothing
            // durable) the moment its read is released.
            abortStalled(toAbort);
            waitForConsumerToUnblock();
        }
    }

    private void waitForConsumerToUnblock() {
        synchronized (monitor) {
            while (waitingForData && !closed && !finished) {
                try {
                    monitor.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void abortStalled(Stream<ServerSentEvent> stream) {
        if (stream == null) {
            return;
        }
        try {
            // Unblocks a consumer parked in the stream's blocking read.
            stream.close();
        } catch (RuntimeException ignored) {
            // A dead connection's close failure must not kill the watchdog.
        }
    }

    private void markWaitStart() {
        if (stallTimeout != null) {
            synchronized (monitor) {
                stalled = false;
                waitStartNanos = System.nanoTime();
                waitingForData = true;
                monitor.notifyAll();
            }
        }
    }

    private void markWaitEnd() {
        if (stallTimeout != null) {
            synchronized (monitor) {
                waitingForData = false;
                monitor.notifyAll();
            }
        }
    }

    private void notifyMonitor() {
        if (stallTimeout != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    private void closeCurrent(boolean propagate) {
        Stream<ServerSentEvent> stream = current;
        current = null;
        currentStreamRef = null;
        currentIterator = null;
        if (stream == null) {
            return;
        }
        if (propagate) {
            stream.close();
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // A failing close of an already dead connection must not mask
            // the reconnection decision.
        }
    }

    void close() {
        closed = true;
        finished = true;
        notifyMonitor();
        closeCurrent(true);
    }
}
