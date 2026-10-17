package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ServerSentEvent#reconnecting} and {@link ReconnectPolicy}
 * (ADR-0059, phase 1): EventSource-style reconnection with Last-Event-ID replay.
 */
class ServerSentEventReconnectTest {

    private static ReconnectPolicy quickPolicy(int maxAttempts) {
        return quickPolicyBuilder(maxAttempts).build();
    }

    private static ReconnectPolicy.Builder quickPolicyBuilder(int maxAttempts) {
        return ReconnectPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(10));
    }

    /** A stream that fails mid-body: the transport is dead before a clean EOF. */
    private static Stream<ServerSentEvent> failing(String message) {
        Iterator<ServerSentEvent> failing = new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new UncheckedIOException(new IOException(message));
            }

            @Override
            public ServerSentEvent next() {
                throw new NoSuchElementException();
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(failing, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /** A stream that delivers one event and then fails mid-body. */
    private static Stream<ServerSentEvent> oneThenFail(ServerSentEvent event, String message) {
        Iterator<ServerSentEvent> failing = new Iterator<>() {
            private boolean delivered;

            @Override
            public boolean hasNext() {
                if (!delivered) {
                    return true;
                }
                throw new UncheckedIOException(new IOException(message));
            }

            @Override
            public ServerSentEvent next() {
                if (delivered) {
                    throw new NoSuchElementException();
                }
                delivered = true;
                return event;
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(failing, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    private static List<String> dataOf(Stream<ServerSentEvent> stream) {
        try (stream) {
            return stream.map(ServerSentEvent::data).toList();
        }
    }

    @Test
    @DisplayName("single stream: events are forwarded in order, [DONE] stops without a reconnect")
    void singleStreamForwardedInOrder() {
        List<String> openedWith = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            openedWith.add(lastEventId);
            opens.incrementAndGet();
            return Stream.of(
                    new ServerSentEvent("1", "message", "one", null),
                    new ServerSentEvent("2", "message", "two", null),
                    ServerSentEvent.ofData("[DONE]"));
        };

        Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, quickPolicy(3));

        assertEquals(List.of("one", "two", "[DONE]"), dataOf(stream));
        assertEquals(1, opens.get());
        assertEquals(Arrays.asList((String) null), openedWith);
    }

    @Test
    @DisplayName("lazy: the opener is not invoked before the first pull")
    void lazyOpening() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return Stream.empty();
        };

        Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, quickPolicy(0));
        assertEquals(0, opens.get());

        assertEquals(List.of(), dataOf(stream));
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("clean EOF reconnects with Last-Event-ID and splices the events of both connections")
    void cleanEofReconnectsWithLastEventId() {
        List<String> openedWith = new ArrayList<>();
        List<ReconnectInfo> infos = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            openedWith.add(lastEventId);
            if (opens.getAndIncrement() == 0) {
                return Stream.of(
                        new ServerSentEvent("1", "message", "one", null),
                        new ServerSentEvent("2", "message", "two", null));
            }
            return Stream.of(ServerSentEvent.ofData("[DONE]"));
        };

        ReconnectPolicy policy = quickPolicyBuilder(2).onReconnect(infos::add).build();
        List<String> data = dataOf(ServerSentEvent.reconnecting(opener, policy));

        assertEquals(List.of("one", "two", "[DONE]"), data);
        assertEquals(Arrays.asList(null, "2"), openedWith);
        assertEquals(1, infos.size());
        ReconnectInfo info = infos.getFirst();
        assertEquals(1, info.attempt());
        assertEquals("2", info.lastEventId());
        assertEquals(Duration.ofMillis(1), info.delay());
        assertNull(info.cause());
    }

    @Test
    @DisplayName("mid-stream UncheckedIOException reconnects; the last event id is replayed")
    void midStreamFailureReconnects() {
        List<String> openedWith = new ArrayList<>();
        List<ReconnectInfo> infos = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            openedWith.add(lastEventId);
            if (opens.getAndIncrement() == 0) {
                return oneThenFail(new ServerSentEvent("5", "message", "half", null), "connection reset");
            }
            return Stream.of(ServerSentEvent.ofData("[DONE]"));
        };

        ReconnectPolicy policy = quickPolicyBuilder(1).onReconnect(infos::add).build();
        List<String> data = dataOf(ServerSentEvent.reconnecting(opener, policy));

        assertEquals(List.of("half", "[DONE]"), data);
        assertEquals(Arrays.asList(null, "5"), openedWith);
        assertEquals(1, infos.size());
        assertEquals("connection reset", infos.getFirst().cause().getCause().getMessage());
    }

    @Test
    @DisplayName("a failing opener is a reconnectable failure, not a terminal one")
    void failingOpenerReconnects() {
        List<ReconnectInfo> infos = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            if (opens.getAndIncrement() == 0) {
                throw new UncheckedIOException(new IOException("connect refused"));
            }
            return Stream.of(ServerSentEvent.ofData("[DONE]"));
        };

        ReconnectPolicy policy = quickPolicyBuilder(1).onReconnect(infos::add).build();
        assertEquals(List.of("[DONE]"), dataOf(ServerSentEvent.reconnecting(opener, policy)));
        assertEquals(2, opens.get());
        assertEquals("connect refused", infos.getFirst().cause().getCause().getMessage());
    }

    @Test
    @DisplayName("server retry: field wins over the policy backoff and is reported in ReconnectInfo")
    void serverRetryFieldWins() {
        List<ReconnectInfo> infos = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            if (opens.getAndIncrement() == 0) {
                return Stream.of(
                        new ServerSentEvent("1", "message", "one", 50L),
                        new ServerSentEvent("1", "message", "two", 50L));
            }
            return Stream.of(ServerSentEvent.ofData("[DONE]"));
        };

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofSeconds(1))
                .onReconnect(infos::add)
                .build();
        assertEquals(List.of("one", "two", "[DONE]"), dataOf(ServerSentEvent.reconnecting(opener, policy)));
        assertEquals(Duration.ofMillis(50), infos.getFirst().delay());
    }

    @Test
    @DisplayName("exponential growth with jitter is capped by maxBackoff")
    void growthIsCappedAtMaxBackoff() {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(10)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(500))
                .backoffMultiplier(2.0)
                .jitter(Jitter.NONE)
                .build();
        java.util.Random random = new java.util.Random(42);
        assertEquals(100L, policy.nextDelayMillis(1, null, 0L, random));
        assertEquals(200L, policy.nextDelayMillis(2, null, 0L, random));
        assertEquals(400L, policy.nextDelayMillis(3, null, 0L, random));
        assertEquals(500L, policy.nextDelayMillis(4, null, 0L, random));
        assertEquals(500L, policy.nextDelayMillis(6, null, 0L, random));
        assertEquals(500L, policy.nextDelayMillis(1, 900L, 0L, random));
    }

    @Test
    @DisplayName("jitter draws stay within the bound; NONE is exactly the bound")
    void jitterWithinBound() {
        ReconnectPolicy full = ReconnectPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .jitter(Jitter.FULL)
                .build();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 50; i++) {
            long delay = full.nextDelayMillis(1, null, 0L, random);
            assertTrue(delay >= 0 && delay <= 100, "delay out of bound: " + delay);
        }

        ReconnectPolicy decorrelated = ReconnectPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(500))
                .jitter(Jitter.DECORRELATED)
                .build();
        long delay = decorrelated.nextDelayMillis(1, null, 300L, random);
        assertTrue(delay >= 100 && delay <= 500, "delay out of bound: " + delay);
    }

    @Test
    @DisplayName("DECORRELATED jitter stays capped by maxBackoff when the server retry: exceeds it")
    void decorrelatedJitterCappedByMaxBackoff() {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(1000))
                .jitter(Jitter.DECORRELATED)
                .build();
        java.util.Random random = new java.util.Random(42);

        // A server retry: of 60 s is far beyond the 1 s cap: it must never uncap the wait.
        long first = policy.nextDelayMillis(1, 60_000L, 0L, random);
        assertTrue(first >= 100 && first <= 1000, "decorrelated wait out of [100, 1000]: " + first);

        // Even when the previous delay itself exceeded the cap, the result stays capped.
        long second = policy.nextDelayMillis(2, 60_000L, 60_000L, random);
        assertTrue(second >= 100 && second <= 1000, "decorrelated wait out of [100, 1000]: " + second);
    }

    @Test
    @DisplayName("a server retry: above maxBackoff never uncaps a DECORRELATED reconnect wait")
    void serverRetryAboveMaxBackoffDoesNotUncapReconnect() {
        List<ReconnectInfo> infos = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            if (opens.getAndIncrement() == 0) {
                // The server suggests 600 ms between reconnects, then the connection dies.
                return oneThenFail(new ServerSentEvent("1", "message", "one", 600L), "connection reset");
            }
            return Stream.of(ServerSentEvent.ofData("[DONE]"));
        };
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .jitter(Jitter.DECORRELATED)
                .onReconnect(infos::add)
                .build();

        assertEquals(List.of("one", "[DONE]"), dataOf(ServerSentEvent.reconnecting(opener, policy)));
        assertEquals(1, infos.size());
        assertTrue(infos.getFirst().delay().toMillis() <= 100L,
                "decorrelated reconnect wait must honour maxBackoff, got " + infos.getFirst().delay());
    }

    @Test
    @DisplayName("[DONE] ends the stream immediately when stopOnDone is on (default)")
    void doneStopsStreamAndReconnect() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return Stream.of(
                    new ServerSentEvent("9", "message", "last", null),
                    ServerSentEvent.ofData("[DONE]"),
                    ServerSentEvent.ofData("after-done"));
        };

        assertEquals(List.of("last", "[DONE]"), dataOf(ServerSentEvent.reconnecting(opener, quickPolicy(3))));
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("with stopOnDone off, events after [DONE] still flow")
    void doneDoesNotStopWhenDisabled() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return Stream.of(
                    ServerSentEvent.ofData("[DONE]"),
                    ServerSentEvent.ofData("after-done"));
        };

        ReconnectPolicy policy = quickPolicyBuilder(0).stopOnDone(false).build();
        assertEquals(List.of("[DONE]", "after-done"), dataOf(ServerSentEvent.reconnecting(opener, policy)));
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("maxAttempts bounds the reconnects; exhaustion rethrows the last failure")
    void exhaustionRethrowsLastFailure() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return failing("boom");
        };

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> dataOf(ServerSentEvent.reconnecting(opener, quickPolicy(2))));

        assertEquals("boom", thrown.getCause().getMessage());
        assertEquals(3, opens.get());
    }

    @Test
    @DisplayName("exhaustion after clean EOFs ends the stream quietly")
    void exhaustionAfterCleanEofEndsQuietly() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return Stream.empty();
        };

        assertEquals(List.of(), dataOf(ServerSentEvent.reconnecting(opener, quickPolicy(1))));
        assertEquals(2, opens.get());
    }

    @Test
    @DisplayName("non-IO runtime failures are programming errors: they propagate without reconnect")
    void nonIoFailurePropagates() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            Iterator<ServerSentEvent> failing = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    throw new IllegalStateException("user bug");
                }

                @Override
                public ServerSentEvent next() {
                    throw new NoSuchElementException();
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(failing, Spliterator.ORDERED | Spliterator.NONNULL), false);
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> dataOf(ServerSentEvent.reconnecting(opener, quickPolicy(3))));

        assertEquals("user bug", thrown.getMessage());
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("close() cancels the pending reconnect")
    void closeCancelsReconnect() {
        AtomicInteger opens = new AtomicInteger();
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            opens.incrementAndGet();
            return Stream.of(new ServerSentEvent("1", "message", "one", null));
        };

        Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, quickPolicy(3));
        try {
            Iterator<ServerSentEvent> iterator = stream.iterator();
            assertEquals("one", iterator.next().data());
            stream.close();
            assertFalse(iterator.hasNext());
        } finally {
            stream.close();
        }
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("closing the outer stream closes the underlying stream")
    void closePropagatesToInnerStream() {
        AtomicBoolean innerClosed = new AtomicBoolean();
        Function<String, Stream<ServerSentEvent>> opener =
                lastEventId -> Stream.of(new ServerSentEvent("1", "message", "one", null))
                        .onClose(() -> innerClosed.set(true));

        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, quickPolicy(0))) {
            Iterator<ServerSentEvent> iterator = stream.iterator();
            assertEquals("one", iterator.next().data());
        }
        assertTrue(innerClosed.get());
    }

    @Test
    @DisplayName("interrupt during the reconnect wait propagates and restores the flag")
    void interruptDuringReconnectWait() {
        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> Stream.of(
                new ServerSentEvent("1", "message", "one", null));

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofSeconds(30))
                .build();

        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, policy)) {
            Iterator<ServerSentEvent> iterator = stream.iterator();
            assertEquals("one", iterator.next().data());
            Thread.currentThread().interrupt();
            try {
                UncheckedIOException thrown = assertThrows(UncheckedIOException.class, iterator::hasNext);
                assertTrue(thrown.getCause() instanceof java.io.InterruptedIOException);
                assertTrue(Thread.interrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    @DisplayName("policy defaults follow WHATWG EventSource: 3 reconnect attempts, 3000 ms fallback, [DONE] stops")
    void policyDefaults() {
        ReconnectPolicy policy = ReconnectPolicy.defaults();

        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(3), policy.getInitialBackoff());
        assertEquals(Duration.ofSeconds(30), policy.getMaxBackoff());
        assertEquals(1.0, policy.getBackoffMultiplier());
        assertEquals(Jitter.NONE, policy.getJitter());
        assertTrue(policy.isStopOnDone());
        assertTrue(policy.isEnabled());
        assertFalse(ReconnectPolicy.builder().maxAttempts(0).build().isEnabled());
    }

    @Test
    @DisplayName("policy builder rejects invalid values")
    void policyValidation() {
        assertThrows(IllegalStateException.class, () -> ReconnectPolicy.builder().maxAttempts(-1).build());
        assertThrows(IllegalStateException.class,
                () -> ReconnectPolicy.builder().initialBackoff(Duration.ofMillis(-1)).build());
        assertThrows(IllegalStateException.class, () -> ReconnectPolicy.builder()
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofSeconds(1))
                .build());
        assertThrows(IllegalStateException.class,
                () -> ReconnectPolicy.builder().backoffMultiplier(0.5).build());
        assertThrows(NullPointerException.class, () -> ReconnectPolicy.builder().jitter(null));
        assertThrows(NullPointerException.class,
                () -> ServerSentEvent.reconnecting(null, ReconnectPolicy.defaults()));
        assertThrows(NullPointerException.class,
                () -> ServerSentEvent.reconnecting(lastEventId -> Stream.empty(), null));
    }

    @Test
    @DisplayName("policy stallTimeout getter, defaults, and validation")
    void policyStallTimeoutConfiguration() {
        ReconnectPolicy defaults = ReconnectPolicy.defaults();
        assertNull(defaults.stallTimeout());
        assertFalse(defaults.hasStallTimeout());

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .stallTimeout(Duration.ofMillis(250))
                .build();
        assertEquals(Duration.ofMillis(250), policy.stallTimeout());
        assertTrue(policy.hasStallTimeout());
        assertTrue(policy.toString().contains("stallTimeout=PT0.25S"));

        assertThrows(IllegalStateException.class, () -> ReconnectPolicy.builder().stallTimeout(Duration.ZERO).build());
        assertThrows(IllegalStateException.class, () -> ReconnectPolicy.builder().stallTimeout(Duration.ofMillis(-50)).build());
    }

    @Test
    @DisplayName("stalled SSE stream triggers stall guard and reconnects with SocketTimeoutException cause")
    void stalledStreamTriggersReconnect() {
        List<ReconnectInfo> infos = new ArrayList<>();
        List<String> openedWith = new ArrayList<>();

        BlockingInputStream blockingStream = new BlockingInputStream();
        Stream<ServerSentEvent> firstStream = Stream.concat(
                Stream.of(new ServerSentEvent("1", "message", "first", null)),
                blockingEventStream(blockingStream)
        );

        Function<String, Stream<ServerSentEvent>> opener = lastId -> {
            openedWith.add(lastId);
            if (openedWith.size() == 1) {
                return firstStream;
            }
            return Stream.of(new ServerSentEvent("2", "message", "second", null));
        };

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(5))
                .stallTimeout(Duration.ofMillis(80))
                .onReconnect(infos::add)
                .build();

        List<ServerSentEvent> events;
        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, policy)) {
            events = stream.toList();
        }

        assertEquals(2, events.size());
        assertEquals("first", events.get(0).data());
        assertEquals("second", events.get(1).data());
        assertTrue(blockingStream.closed, "blocking stream must be closed by watchdog");

        assertEquals(Arrays.asList(null, "1"), openedWith);
        assertEquals(1, infos.size());
        ReconnectInfo info = infos.getFirst();
        assertEquals(1, info.attempt());
        assertEquals("1", info.lastEventId());
        assertNotNull(info.cause());
        assertTrue(info.cause() instanceof UncheckedIOException);
        assertTrue(info.cause().getCause() instanceof java.net.SocketTimeoutException,
                "cause must be SocketTimeoutException, got: " + info.cause().getCause());
    }

    @Test
    @DisplayName("stalled SSE stream respects maxAttempts bound and rethrows UncheckedIOException(SocketTimeoutException)")
    void stalledStreamExhaustsAttemptsAndRethrows() {
        BlockingInputStream stream1 = new BlockingInputStream();
        BlockingInputStream stream2 = new BlockingInputStream();
        int[] calls = {0};

        Function<String, Stream<ServerSentEvent>> opener = lastId ->
                blockingEventStream(calls[0]++ == 0 ? stream1 : stream2);

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(5))
                .stallTimeout(Duration.ofMillis(60))
                .build();

        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, policy)) {
            Iterator<ServerSentEvent> iterator = stream.iterator();
            UncheckedIOException ex = assertThrows(UncheckedIOException.class, iterator::hasNext);
            assertTrue(ex.getCause() instanceof java.net.SocketTimeoutException,
                    "terminal cause must be SocketTimeoutException, got: " + ex.getCause());
        }
        assertTrue(stream1.closed);
        assertTrue(stream2.closed);
    }

    @Test
    @DisplayName("healthy SSE stream faster than stallTimeout is never interrupted")
    void healthyStreamDoesNotTriggerStallGuard() {
        List<ReconnectInfo> infos = new ArrayList<>();
        Function<String, Stream<ServerSentEvent>> opener = lastId -> Stream.of(
                new ServerSentEvent("1", "message", "a", null),
                new ServerSentEvent("2", "message", "b", null),
                new ServerSentEvent(null, null, "[DONE]", null)
        );

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(2)
                .stallTimeout(Duration.ofMillis(200))
                .onReconnect(infos::add)
                .build();

        List<ServerSentEvent> events;
        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, policy)) {
            events = stream.toList();
        }

        assertEquals(3, events.size());
        assertEquals("a", events.get(0).data());
        assertEquals("b", events.get(1).data());
        assertTrue(events.get(2).isDone());
        assertTrue(infos.isEmpty(), "no reconnects should happen for healthy stream");
    }

    @Test
    @DisplayName("ReconnectPolicy with Jitter.EQUAL calculates delay within [half, bound]")
    void equalJitterWithinBounds() {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100))
                .jitter(Jitter.EQUAL)
                .build();
        long delay = policy.nextDelayMillis(1, null, 0L, new java.util.Random(42L));
        assertTrue(delay >= 50 && delay <= 100, "delay must be in [50, 100], got " + delay);
    }

    @Test
    @DisplayName("ReconnectPolicy validates stallTimeout and maxBackoff")
    void reconnectPolicyValidation() {
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().stallTimeout(Duration.ZERO));
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().stallTimeout(Duration.ofMillis(-5)));
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().maxBackoff(Duration.ofMillis(-1)).build());

        // maxAttempts < 0
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().maxAttempts(-1).build());

        // negative initialBackoff
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().initialBackoff(Duration.ofMillis(-10)).build());

        // initialBackoff > maxBackoff
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().initialBackoff(Duration.ofSeconds(10)).maxBackoff(Duration.ofSeconds(1)).build());

        // backoffMultiplier < 1.0 or NaN
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().backoffMultiplier(0.5).build());
        assertThrows(IllegalStateException.class, () ->
                ReconnectPolicy.builder().backoffMultiplier(Double.NaN).build());

        // null arguments in builder
        assertThrows(NullPointerException.class, () -> ReconnectPolicy.builder().initialBackoff(null));
        assertThrows(NullPointerException.class, () -> ReconnectPolicy.builder().maxBackoff(null));
        assertThrows(NullPointerException.class, () -> ReconnectPolicy.builder().jitter(null));
        assertThrows(NullPointerException.class, () -> ReconnectPolicy.builder().onReconnect(null));
    }

    @Test
    @DisplayName("ReconnectPolicy returns 0 when bound is zero")
    void zeroBoundReturnsZero() {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .initialBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .build();
        assertEquals(0, policy.nextDelayMillis(1, 0L, 0L, new java.util.Random(42L)));
    }

    @Test
    @DisplayName("ServerSentEventReconnector next() throws NoSuchElementException on empty stream")
    void emptyReconnectorStreamThrowsNoSuchElement() {
        java.util.Iterator<ServerSentEvent> it = ServerSentEvent.reconnecting(
                id -> Stream.empty(),
                ReconnectPolicy.builder().maxAttempts(0).build()
        ).iterator();
        assertFalse(it.hasNext());
        assertThrows(java.util.NoSuchElementException.class, it::next);
    }


    /**

     * Mirrors the production SSE close contract (see {@code HttpTemplate}):
     * closing the returned stream closes the underlying input stream, not the
     * {@link java.io.BufferedReader} — closing a reader from another thread
     * while its owning thread is blocked in {@code read()} would deadlock on
     * the decoder lock.
     */
    private static Stream<ServerSentEvent> blockingEventStream(BlockingInputStream in) {
        return ServerSentEvent
                .parse(new java.io.BufferedReader(new java.io.InputStreamReader(in)).lines())
                .onClose(in::close);
    }

    private static class BlockingInputStream extends java.io.InputStream {
        final java.util.concurrent.CountDownLatch closedLatch = new java.util.concurrent.CountDownLatch(1);
        volatile boolean closed = false;

        @Override
        public int read() throws IOException {
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted");
            }
            throw new IOException("Stream closed by watchdog");
        }

        @Override
        public void close() {
            closed = true;
            closedLatch.countDown();
        }
    }
}
