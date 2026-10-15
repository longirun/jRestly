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
}
