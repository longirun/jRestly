package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code ProgressBodyPublisher} (ADR-0030): upload progress
 * forwarding through {@code Flow.Subscriber}, unknown/empty content length and
 * the per-subscription counter reset relied on by retries. No network.
 */
@DisplayName("ProgressBodyPublisher Tests")
class ProgressBodyPublisherTest {

    @Test
    @DisplayName("contentLength matches delegate publisher")
    void contentLengthMatchesDelegate() {
        byte[] payload = "Hello world".getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.ofByteArray(payload);
        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> {});

        assertEquals(payload.length, publisher.contentLength());
    }

    @Test
    @DisplayName("Constructor and subscribe fail-fast on null arguments")
    void failFastOnNullArguments() {
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.noBody();
        assertThrows(NullPointerException.class, () -> new ProgressBodyPublisher(null, (sent, total) -> {}));
        assertThrows(NullPointerException.class, () -> new ProgressBodyPublisher(raw, null));

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> {});
        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    @Test
    @DisplayName("Tracks progress sequentially through Flow.Subscriber")
    void tracksProgressSequentially() {
        byte[] chunk1 = "12345".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "67890".getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(chunk1),
                HttpRequest.BodyPublishers.ofByteArray(chunk2));

        record ProgressSnapshot(long sent, long total) {}
        List<ProgressSnapshot> snapshots = new ArrayList<>();

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw,
                (sent, total) -> snapshots.add(new ProgressSnapshot(sent, total)));

        AtomicBoolean completed = new AtomicBoolean();
        List<byte[]> receivedChunks = new ArrayList<>();

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] data = new byte[item.remaining()];
                item.get(data);
                receivedChunks.add(data);
            }

            @Override
            public void onError(Throwable throwable) {
                fail("Unexpected error: " + throwable);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertTrue(completed.get());
        assertEquals(2, receivedChunks.size());
        assertEquals(2, snapshots.size());
        assertEquals(new ProgressSnapshot(5, 10), snapshots.get(0));
        assertEquals(new ProgressSnapshot(10, 10), snapshots.get(1));
    }

    @Test
    @DisplayName("Tracks unknown contentLength with totalBytes = -1")
    void tracksUnknownContentLength() {
        byte[] payload = "chunk".getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(payload));

        assertEquals(-1, raw.contentLength());

        List<Long> sentList = new ArrayList<>();
        List<Long> totalList = new ArrayList<>();

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> {
            sentList.add(sent);
            totalList.add(total);
        });

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });

        assertEquals(List.of(5L), sentList);
        assertEquals(List.of(-1L), totalList);
    }

    @Test
    @DisplayName("Resets progress counter on subsequent subscriptions (retry behavior)")
    void resetsProgressOnMultipleSubscriptions() {
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.ofByteArray(payload);

        List<Long> sentValues = new ArrayList<>();
        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> sentValues.add(sent));

        // Attempt 1
        publisher.subscribe(new SimpleSubscriber());
        assertEquals(List.of(3L), sentValues);

        // Attempt 2 (retry simulation)
        publisher.subscribe(new SimpleSubscriber());
        assertEquals(List.of(3L, 3L), sentValues);
    }

    @Test
    @DisplayName("Handles empty body with totalBytes = 0 and invokes onProgress(0, 0)")
    void handlesEmptyBodyWithTotalZero() {
        HttpRequest.BodyPublisher raw = HttpRequest.BodyPublishers.noBody();
        List<Long> sentList = new ArrayList<>();
        List<Long> totalList = new ArrayList<>();

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> {
            sentList.add(sent);
            totalList.add(total);
        });

        publisher.subscribe(new SimpleSubscriber());

        assertEquals(List.of(0L), sentList);
        assertEquals(List.of(0L), totalList);
    }

    private static class SimpleSubscriber implements Flow.Subscriber<ByteBuffer> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {}

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    @Test
    @DisplayName("ProgressSubscriber delegates onError to downstream subscriber")
    void delegatesOnErrorToDownstream() {
        HttpRequest.BodyPublisher raw = new HttpRequest.BodyPublisher() {
            @Override public long contentLength() { return 0; }
            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {
                        subscriber.onError(new java.io.IOException("test-error"));
                    }
                    @Override public void cancel() {}
                });
            }
        };

        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> {});
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
            @Override public void onNext(ByteBuffer item) {}
            @Override public void onError(Throwable throwable) { errorRef.set(throwable); }
            @Override public void onComplete() {}
        });

        assertNotNull(errorRef.get());
        assertEquals("test-error", errorRef.get().getMessage());
    }

    @Test
    @DisplayName("Edge cases: null and empty byte buffers on onNext, onComplete when totalBytes != 0, and notifyZero idempotence")
    void edgeCasesNullAndEmptyBuffers() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        HttpRequest.BodyPublisher raw = new HttpRequest.BodyPublisher() {
            @Override public long contentLength() { return 100; }
            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {
                        subscriber.onNext(null);
                        subscriber.onNext(ByteBuffer.allocate(0));
                        subscriber.onComplete();
                    }
                    @Override public void cancel() {}
                });
            }
        };

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(raw, (sent, total) -> callCount.incrementAndGet());
        publisher.subscribe(new SimpleSubscriber());
        assertEquals(0, callCount.get());

        // Zero-length notify idempotence
        ProgressBodyPublisher emptyPub = new ProgressBodyPublisher(HttpRequest.BodyPublishers.noBody(), (sent, total) -> callCount.incrementAndGet());
        emptyPub.notifyZeroLengthIfPending();
        assertEquals(1, callCount.get());
        emptyPub.notifyZeroLengthIfPending(); // second call is no-op
        assertEquals(1, callCount.get());
        emptyPub.subscribe(new SimpleSubscriber()); // onComplete detects already notified
        assertEquals(1, callCount.get());
    }
}
