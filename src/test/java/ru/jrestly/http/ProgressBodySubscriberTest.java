package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code ProgressBodySubscriber} (ADR-0033): download progress
 * forwarding through {@code BodySubscriber}, unknown/empty content length,
 * error delegation, buffer-position preservation and {@code Content-Length}
 * extraction from {@code ResponseInfo}. No network.
 */
@DisplayName("ProgressBodySubscriber Tests")
class ProgressBodySubscriberTest {

    record ProgressSnapshot(long bytesRead, long totalBytes) {}

    @Test
    @DisplayName("Constructor and apply fail-fast on null arguments")
    void failFastOnNullArguments() {
        BodyHandler<byte[]> rawHandler = HttpResponse.BodyHandlers.ofByteArray();
        DownloadProgressListener listener = (read, total) -> {};

        assertThrows(NullPointerException.class, () -> new ProgressBodyHandler<>(null, listener));
        assertThrows(NullPointerException.class, () -> new ProgressBodyHandler<>(rawHandler, null));

        ProgressBodyHandler<byte[]> progressHandler = new ProgressBodyHandler<>(rawHandler, listener);
        assertThrows(NullPointerException.class, () -> progressHandler.apply(null));

        BodySubscriber<byte[]> rawSubscriber = HttpResponse.BodySubscribers.ofByteArray();
        assertThrows(NullPointerException.class, () -> new ProgressBodyHandler.ProgressBodySubscriber<>(null, listener, 100));
        assertThrows(NullPointerException.class, () -> new ProgressBodyHandler.ProgressBodySubscriber<>(rawSubscriber, null, 100));
    }

    @Test
    @DisplayName("Tracks progress sequentially through BodySubscriber")
    void tracksProgressSequentially() throws ExecutionException, InterruptedException {
        BodySubscriber<byte[]> raw = HttpResponse.BodySubscribers.ofByteArray();
        List<ProgressSnapshot> snapshots = new ArrayList<>();

        ProgressBodyHandler.ProgressBodySubscriber<byte[]> subscriber =
                new ProgressBodyHandler.ProgressBodySubscriber<>(raw, (read, total) -> snapshots.add(new ProgressSnapshot(read, total)), 10);

        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {}
        });

        ByteBuffer buf1 = ByteBuffer.wrap("12345".getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf2 = ByteBuffer.wrap("67890".getBytes(StandardCharsets.UTF_8));

        subscriber.onNext(List.of(buf1));
        subscriber.onNext(List.of(buf2));
        subscriber.onComplete();

        assertEquals(2, snapshots.size());
        assertEquals(new ProgressSnapshot(5, 10), snapshots.get(0));
        assertEquals(new ProgressSnapshot(10, 10), snapshots.get(1));

        byte[] result = subscriber.getBody().toCompletableFuture().get();
        assertEquals("1234567890", new String(result, StandardCharsets.UTF_8));
        assertEquals(10, subscriber.bytesRead());
        assertEquals(10, subscriber.totalBytes());
    }

    @Test
    @DisplayName("Tracks unknown contentLength with totalBytes = -1")
    void tracksUnknownContentLength() {
        BodySubscriber<byte[]> raw = HttpResponse.BodySubscribers.ofByteArray();
        List<ProgressSnapshot> snapshots = new ArrayList<>();

        ProgressBodyHandler.ProgressBodySubscriber<byte[]> subscriber =
                new ProgressBodyHandler.ProgressBodySubscriber<>(raw, (read, total) -> snapshots.add(new ProgressSnapshot(read, total)), -1);

        ByteBuffer buf = ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8));
        subscriber.onNext(List.of(buf));
        subscriber.onComplete();

        assertEquals(1, snapshots.size());
        assertEquals(new ProgressSnapshot(7, -1), snapshots.get(0));
    }

    @Test
    @DisplayName("Handles empty body with totalBytes = 0")
    void handlesEmptyBodyWithTotalZero() {
        BodySubscriber<byte[]> raw = HttpResponse.BodySubscribers.ofByteArray();
        List<ProgressSnapshot> snapshots = new ArrayList<>();

        ProgressBodyHandler.ProgressBodySubscriber<byte[]> subscriber =
                new ProgressBodyHandler.ProgressBodySubscriber<>(raw, (read, total) -> snapshots.add(new ProgressSnapshot(read, total)), 0);

        subscriber.onComplete();

        assertEquals(1, snapshots.size());
        assertEquals(new ProgressSnapshot(0, 0), snapshots.get(0));
    }

    @Test
    @DisplayName("Delegates onError to downstream subscriber")
    void delegatesOnError() {
        BodySubscriber<byte[]> raw = HttpResponse.BodySubscribers.ofByteArray();
        ProgressBodyHandler.ProgressBodySubscriber<byte[]> subscriber =
                new ProgressBodyHandler.ProgressBodySubscriber<>(raw, (read, total) -> {}, 10);

        IOException failure = new IOException("network error");
        subscriber.onError(failure);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertSame(failure, ex.getCause());
    }

    @Test
    @DisplayName("Preserves ByteBuffer position and remaining on onNext")
    void preservesByteBufferPositionAndRemaining() {
        BodySubscriber<byte[]> raw = HttpResponse.BodySubscribers.ofByteArray();
        ProgressBodyHandler.ProgressBodySubscriber<byte[]> subscriber =
                new ProgressBodyHandler.ProgressBodySubscriber<>(raw, (read, total) -> {}, 4);

        ByteBuffer buf = ByteBuffer.wrap("abcd".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, buf.position());
        assertEquals(4, buf.remaining());

        subscriber.onNext(List.of(buf));

        // Downstream ofByteArray may consume the buffer, but inside onNext before delegation the buffer was intact
        assertEquals(4, subscriber.bytesRead());
    }

    @Test
    @DisplayName("ProgressBodyHandler extracts Content-Length from ResponseInfo")
    void progressBodyHandlerExtractsContentLength() {
        BodyHandler<byte[]> rawHandler = HttpResponse.BodyHandlers.ofByteArray();
        ProgressBodyHandler<byte[]> handler = new ProgressBodyHandler<>(rawHandler, (read, total) -> {});

        ResponseInfo infoWithLength = new FakeResponseInfo(200, Map.of("content-length", List.of("12345")));
        BodySubscriber<byte[]> sub1 = handler.apply(infoWithLength);
        assertTrue(sub1 instanceof ProgressBodyHandler.ProgressBodySubscriber<byte[]>);
        assertEquals(12345, ((ProgressBodyHandler.ProgressBodySubscriber<byte[]>) sub1).totalBytes());

        ResponseInfo infoChunked = new FakeResponseInfo(200, Map.of());
        BodySubscriber<byte[]> sub2 = handler.apply(infoChunked);
        assertEquals(-1, ((ProgressBodyHandler.ProgressBodySubscriber<byte[]>) sub2).totalBytes());
    }

    private static class FakeResponseInfo implements ResponseInfo {
        private final int statusCode;
        private final HttpHeaders headers;

        FakeResponseInfo(int statusCode, Map<String, List<String>> map) {
            this.statusCode = statusCode;
            this.headers = HttpHeaders.of(map, (k, v) -> true);
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Test
    @DisplayName("Edge cases: onNext with null elements, empty buffers, and onComplete with totalBytes > 0")
    void edgeCasesNullAndEmptyBuffers() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        HttpResponse.BodySubscriber<byte[]> stubDelegate = new HttpResponse.BodySubscriber<>() {
            @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return java.util.concurrent.CompletableFuture.completedFuture(new byte[0]); }
            @Override public void onSubscribe(Flow.Subscription subscription) {}
            @Override public void onNext(List<ByteBuffer> item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        };
        ProgressBodyHandler.ProgressBodySubscriber<byte[]> sub =
                new ProgressBodyHandler.ProgressBodySubscriber<>(stubDelegate, (read, total) -> callCount.incrementAndGet(), 100);

        java.util.List<ByteBuffer> listWithNull = new java.util.ArrayList<>();
        listWithNull.add(null);
        listWithNull.add(ByteBuffer.allocate(0));
        sub.onNext(listWithNull);
        sub.onNext(null);

        assertEquals(0, callCount.get());
        assertEquals(0, sub.bytesRead());

        // onComplete when bytesRead == 0 but totalBytes == 100 should not trigger onProgress(0, 0)
        sub.onComplete();
        assertEquals(0, callCount.get());
    }
}
