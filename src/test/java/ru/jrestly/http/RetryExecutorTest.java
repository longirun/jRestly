package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RetryExecutor}.
 */
class RetryExecutorTest {

    private static final URI TARGET = URI.create("http://example.com/api/test");
    private static final WireRequest GET_REQ = new WireRequest(HttpMethod.GET, TARGET, List.of(), null, null, null, null);
    private static final WireRequest POST_REQ = new WireRequest(HttpMethod.POST, TARGET, List.of(), null, null, null, null);
    private static final WireRequest PATCH_REQ = new WireRequest(HttpMethod.PATCH, TARGET, List.of(), null, null, null, null);

    private static RequestContext testContext() {
        try {
            Method m = Object.class.getMethod("toString");
            return new SimpleRequestContext("test-module", Object.class, m, new Object[0]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static WireResponse response(int status, String body) {
        return new WireResponse(status, List.of(), body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    private static WireResponse responseWithHeaders(int status, String body, List<Header> headers) {
        return new WireResponse(status, headers, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    private static WireResponse streamResponse(int status, InputStream stream) {
        return new WireResponse(status, List.of(), null, stream);
    }

    private static final class CountingMetricsListener implements ClientMetricsListener {
        final List<Integer> attemptNumbers = new ArrayList<>();
        final List<Duration> retryDelays = new ArrayList<>();

        @Override
        public void onCallComplete(CallMetrics metrics) {
        }

        @Override
        public void onAttemptComplete(
                RequestContext context,
                WireRequest request,
                int attempt,
                Optional<WireResponse> response,
                Optional<Throwable> failure,
                Duration duration
        ) {
            attemptNumbers.add(attempt);
        }

        @Override
        public void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
            retryDelays.add(delay);
        }
    }

    private static final class StubCodec implements PayloadCodec {
        int deserializeCalls = 0;
        RuntimeException failure;

        @Override
        public boolean supports(String mediaType) {
            return true;
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return new byte[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] data, Type type, String mediaType) {
            deserializeCalls++;
            if (failure != null) {
                throw failure;
            }
            return (T) new TestDto(1L, "test", "desc");
        }
    }

    private static final class ClosableStream extends InputStream {
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    @DisplayName("null config throws NPE, null retryPolicy inside config defaults to off")
    void configNullGuards() {
        assertThrows(NullPointerException.class, () -> new RetryExecutor(null));

        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                null, HttpMethod.GET, System.getLogger("test"), null, null,
                null, false, false, String.class, headers -> null, null
        );
        assertFalse(config.retryPolicy().isEnabled());
    }

    @Test
    @DisplayName("isNonIdempotent identifies POST and PATCH correctly")
    void nonIdempotentMethodCheck() {
        assertTrue(RetryExecutor.isNonIdempotent(HttpMethod.POST));
        assertTrue(RetryExecutor.isNonIdempotent(HttpMethod.PATCH));
        assertFalse(RetryExecutor.isNonIdempotent(HttpMethod.GET));
        assertFalse(RetryExecutor.isNonIdempotent(HttpMethod.PUT));
        assertFalse(RetryExecutor.isNonIdempotent(HttpMethod.DELETE));
    }

    @Test
    @DisplayName("unwrapCause unwraps CompletionException and ExecutionException to real cause")
    void unwrapCauseVariants() {
        IOException root = new IOException("root-io");
        assertSame(root, RetryExecutor.unwrapCause(new CompletionException(root)));
        assertSame(root, RetryExecutor.unwrapCause(new ExecutionException(root)));

        CompletionException emptyCompletion = new CompletionException(null);
        assertSame(emptyCompletion, RetryExecutor.unwrapCause(emptyCompletion));

        ExecutionException emptyExecution = new ExecutionException(null);
        assertSame(emptyExecution, RetryExecutor.unwrapCause(emptyExecution));

        IllegalArgumentException iae = new IllegalArgumentException("arg");
        assertSame(iae, RetryExecutor.unwrapCause(iae));
    }

    @Test
    @DisplayName("sync: disabled retry policy sends once, reports attempt metrics, and propagates success")
    void syncDisabledPolicySuccess() throws Exception {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);
        int[] attemptsHolder = new int[1];

        WireResponse resp = executor.execute(GET_REQ, testContext(), attemptsHolder, 0, req -> response(200, "ok"));
        assertEquals(200, resp.statusCode());
        assertEquals(List.of(1), metrics.attemptNumbers);
    }

    @Test
    @DisplayName("sync: disabled retry policy sends once, reports failure metrics, and throws")
    void syncDisabledPolicyFailure() {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);
        IOException boom = new IOException("boom");

        assertThrows(IOException.class, () -> executor.execute(GET_REQ, testContext(), null, 2, req -> {
            throw boom;
        }));
        assertEquals(List.of(3), metrics.attemptNumbers); // 1 + attemptOffset (2)
    }

    @Test
    @DisplayName("async: disabled retry policy sends once and completes exceptionally on failure")
    void asyncDisabledPolicyFailure() {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, testContext(), new AtomicInteger(), 1,
                req -> CompletableFuture.failedFuture(new IOException("async-boom"))
        );

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
        assertEquals(List.of(2), metrics.attemptNumbers); // 1 + offset (1)
    }

    @Test
    @DisplayName("async: disabled policy success passes response through")
    void asyncDisabledPolicySuccess() throws Exception {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, testContext(), new AtomicInteger(), 0,
                req -> CompletableFuture.completedFuture(response(200, "async-ok"))
        );
        WireResponse resp = future.get();
        assertEquals(200, resp.statusCode());
        assertEquals(List.of(1), metrics.attemptNumbers);
    }

    @Test
    @DisplayName("sync: transport failure with retry succeeds on 2nd attempt and closes stream")
    void syncTransportFailureRetriesAndSucceeds() throws Exception {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        int[] attempts = new int[1];

        WireResponse resp = executor.execute(GET_REQ, testContext(), attempts, 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                throw new IOException("network timeout");
            }
            return response(200, "recovered");
        });

        assertEquals(200, resp.statusCode());
        assertEquals(2, attempts[0]);
        assertEquals(2, callCount.get());
        assertEquals(List.of(1, 2), metrics.attemptNumbers);
        assertEquals(1, metrics.retryDelays.size());
    }

    @Test
    @DisplayName("sync: transport failure throws when retryTransport is disabled")
    void syncTransportFailureWithoutRetryTransportThrows() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .retryTransport(false)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        assertThrows(IOException.class, () -> executor.execute(GET_REQ, null, null, 0, req -> {
            throw new IOException("unretried-transport");
        }));
    }

    @Test
    @DisplayName("sync: transport failure on all attempts throws IOException on the last attempt")
    void syncTransportFailureExhaustsAllAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger();
        assertThrows(IOException.class, () -> executor.execute(GET_REQ, testContext(), null, 0, req -> {
            callCount.incrementAndGet();
            throw new IOException("persistent network error");
        }));
        assertEquals(3, callCount.get());
    }

    @Test
    @DisplayName("sync: zero backoff skips sleep")
    void syncZeroBackoffSkipsSleep() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger();
        WireResponse resp = executor.execute(GET_REQ, testContext(), null, 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return response(503, "unavailable");
            }
            return response(200, "ok");
        });
        assertEquals(200, resp.statusCode());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("sync: InterruptedException is never retried and throws immediately")
    void syncInterruptedFailsFast() {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        assertThrows(InterruptedException.class, () -> executor.execute(GET_REQ, testContext(), null, 0, req -> {
            throw new InterruptedException("interrupted");
        }));
        assertEquals(List.of(1), metrics.attemptNumbers);
        assertEquals(0, metrics.retryDelays.size());
    }

    @Test
    @DisplayName("sync: retryable status (503) retries and succeeds, closing intermediate stream")
    void syncStatusRetryAndStreamHygiene() throws Exception {
        ClosableStream intermediateStream = new ClosableStream();
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .retryableStatuses(Set.of(503))
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        WireResponse resp = executor.execute(GET_REQ, testContext(), null, 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return streamResponse(503, intermediateStream);
            }
            return response(200, "recovered-503");
        });

        assertEquals(200, resp.statusCode());
        assertTrue(intermediateStream.closed.get(), "intermediate stream must be closed");
        assertEquals(List.of(1, 2), metrics.attemptNumbers);
        assertEquals(1, metrics.retryDelays.size());
    }

    @Test
    @DisplayName("sync: non-retryable status (400) is returned immediately without retry")
    void syncNonRetryableStatusReturnsImmediately() throws Exception {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        WireResponse resp = executor.execute(GET_REQ, null, null, 0, req -> {
            callCount.incrementAndGet();
            return response(400, "bad request");
        });

        assertEquals(400, resp.statusCode());
        assertEquals(1, callCount.get());
    }

    @Test
    @DisplayName("sync: @OnError status is terminal by default, but retried if retryOnErrorStatuses is true")
    void syncOnErrorStatusRules() throws Exception {
        OnErrorParser parser = new OnErrorParser();
        parser.register(500, RuntimeException.class);

        // 1. By default: terminal contract -> returned immediately without retry
        RetryPolicy defaultPolicy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor executor1 = new RetryExecutor(new RetryExecutor.RetryConfig(
                defaultPolicy, HttpMethod.GET, System.getLogger("test"), null, parser,
                List.of(200), false, false, String.class, headers -> null, null
        ));
        AtomicInteger count1 = new AtomicInteger();
        WireResponse resp1 = executor1.execute(GET_REQ, null, null, 0, req -> {
            count1.incrementAndGet();
            return response(500, "error");
        });
        assertEquals(500, resp1.statusCode());
        assertEquals(1, count1.get());

        // 2. With retryOnErrorStatuses(true) -> retried!
        RetryPolicy retryOnErrorPolicy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .retryOnErrorStatuses(true)
                .build();
        RetryExecutor executor2 = new RetryExecutor(new RetryExecutor.RetryConfig(
                retryOnErrorPolicy, HttpMethod.GET, System.getLogger("test"), null, parser,
                List.of(200), false, false, String.class, headers -> null, null
        ));
        AtomicInteger count2 = new AtomicInteger();
        WireResponse resp2 = executor2.execute(GET_REQ, null, null, 0, req -> {
            if (count2.incrementAndGet() == 1) {
                return response(500, "error");
            }
            return response(200, "ok");
        });
        assertEquals(200, resp2.statusCode());
        assertEquals(2, count2.get());
    }

    @Test
    @DisplayName("sync: non-idempotent method (POST) is not retried unless retryNonIdempotent is enabled")
    void syncNonIdempotentRules() throws Exception {
        // 1. Off by default: POST with 503 is not retried
        RetryPolicy defaultPolicy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor executor1 = new RetryExecutor(new RetryExecutor.RetryConfig(
                defaultPolicy, HttpMethod.POST, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        ));
        AtomicInteger count1 = new AtomicInteger();
        WireResponse resp1 = executor1.execute(POST_REQ, null, null, 0, req -> {
            count1.incrementAndGet();
            return response(503, "temp down");
        });
        assertEquals(503, resp1.statusCode());
        assertEquals(1, count1.get());

        // 2. Enabled: POST with 503 is retried
        RetryPolicy postPolicy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .retryNonIdempotent(true)
                .build();
        RetryExecutor executor2 = new RetryExecutor(new RetryExecutor.RetryConfig(
                postPolicy, HttpMethod.POST, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        ));
        AtomicInteger count2 = new AtomicInteger();
        WireResponse resp2 = executor2.execute(POST_REQ, null, null, 0, req -> {
            if (count2.incrementAndGet() == 1) {
                return response(503, "temp down");
            }
            return response(200, "ok");
        });
        assertEquals(200, resp2.statusCode());
        assertEquals(2, count2.get());
    }

    @Test
    @DisplayName("sync: payload decode failure probe triggers retry and recovers on 2nd attempt")
    void syncDecodeFailureProbeRetriesAndRecovers() throws Exception {
        StubCodec codec = new StubCodec();
        codec.failure = new RuntimeException("cannot parse JSON");

        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .retryDecodeFailures(true)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, TestDto.class, headers -> codec, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        WireResponse resp = executor.execute(GET_REQ, null, null, 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return response(200, "{broken json");
            }
            codec.failure = null; // 2nd attempt decodes cleanly
            return response(200, "{\"id\":1}");
        });

        assertEquals(200, resp.statusCode());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("async: transport failure retries via delayedExecutor and succeeds")
    void asyncTransportFailureRetriesAndRecovers() throws Exception {
        CountingMetricsListener metrics = new CountingMetricsListener();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), metrics, null,
                List.of(200), false, false, String.class, headers -> null, Runnable::run
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger attemptsRef = new AtomicInteger();

        CompletableFuture<WireResponse> future = executor.executeAsync(GET_REQ, testContext(), attemptsRef, 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new IOException("async-timeout"));
            }
            return CompletableFuture.completedFuture(response(200, "ok"));
        });

        WireResponse resp = future.get();
        assertEquals(200, resp.statusCode());
        assertEquals(2, attemptsRef.get());
        assertEquals(List.of(1, 2), metrics.attemptNumbers);
        assertEquals(1, metrics.retryDelays.size());
    }

    @Test
    @DisplayName("async: transport failure exhausts all attempts and fails future")
    void asyncTransportExhaustsAllAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, Runnable::run
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger();
        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, testContext(), null, 0,
                req -> {
                    callCount.incrementAndGet();
                    return CompletableFuture.failedFuture(new IOException("fail-" + callCount.get()));
                }
        );

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
        assertEquals(3, callCount.get());
    }

    @Test
    @DisplayName("async: status 503 failure retries and recovers")
    void asyncStatusRetryRecovers() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        CompletableFuture<WireResponse> future = executor.executeAsync(GET_REQ, null, new AtomicInteger(), 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(response(503, "unavailable"));
            }
            return CompletableFuture.completedFuture(response(200, "ok"));
        });

        WireResponse resp = future.get();
        assertEquals(200, resp.statusCode());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("async: status 503 exhausts all attempts and returns last response")
    void asyncStatusExhaustsAllAttempts() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, Runnable::run
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger();
        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, testContext(), new AtomicInteger(), 0,
                req -> {
                    callCount.incrementAndGet();
                    return CompletableFuture.completedFuture(response(503, "down-" + callCount.get()));
                }
        );

        WireResponse resp = future.get();
        assertEquals(503, resp.statusCode());
        assertEquals(3, callCount.get());
    }

    @Test
    @DisplayName("async: zero delay fast-path executes attemptAsync directly")
    void asyncZeroDelayFastPath() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .jitter(Jitter.NONE)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger();
        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, testContext(), new AtomicInteger(), 0,
                req -> {
                    if (callCount.incrementAndGet() == 1) {
                        return CompletableFuture.completedFuture(response(503, "down"));
                    }
                    return CompletableFuture.completedFuture(response(200, "ok"));
                }
        );

        WireResponse resp = future.get();
        assertEquals(200, resp.statusCode());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("async: decode failure probe retries and recovers")
    void asyncDecodeProbeRetryRecovers() throws Exception {
        StubCodec codec = new StubCodec();
        codec.failure = new RuntimeException("bad json");

        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .jitter(Jitter.NONE)
                .retryDecodeFailures(true)
                .build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, TestDto.class, headers -> codec, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        AtomicInteger callCount = new AtomicInteger(0);
        CompletableFuture<WireResponse> future = executor.executeAsync(GET_REQ, null, new AtomicInteger(), 0, req -> {
            if (callCount.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(response(200, "corrupted"));
            }
            codec.failure = null;
            return CompletableFuture.completedFuture(response(200, "ok"));
        });

        WireResponse resp = future.get();
        assertEquals(200, resp.statusCode());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("async: non-retryable transport failure fails future immediately")
    void asyncNonRetryableTransportFails() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).retryTransport(false).build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, null, new AtomicInteger(), 0,
                req -> CompletableFuture.failedFuture(new IOException("disabled-transport"))
        );

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    @DisplayName("probePayloadDecode covers all short-circuit and decode branches")
    void probePayloadDecodeBranches() {
        StubCodec codec = new StubCodec();
        OnErrorParser onError = new OnErrorParser();
        onError.register(500, RuntimeException.class);

        // 1. OnError status -> true
        RetryExecutor.RetryConfig cfgOnError = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, onError,
                List.of(200), false, false, TestDto.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgOnError).probePayloadDecode(response(500, "err")));

        // 2. Non-expected status -> true
        RetryExecutor.RetryConfig cfgUnexpected = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, TestDto.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(response(404, "err")));

        // 3. Streaming response -> true
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(streamResponse(200, new ByteArrayInputStream(new byte[0]))));

        // 4. Null / empty payload -> true
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(response(200, null)));
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(response(200, "")));

        // 5. Raw byte[] or String -> true
        RetryExecutor.RetryConfig cfgRaw = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, byte[].class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgRaw).probePayloadDecode(response(200, "bytes")));
        RetryExecutor.RetryConfig cfgStr = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgStr).probePayloadDecode(response(200, "str")));

        // 6. Void / void -> true
        RetryExecutor.RetryConfig cfgVoid = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, Void.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgVoid).probePayloadDecode(response(200, "ignored")));
        RetryExecutor.RetryConfig cfgPrimVoid = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, void.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgPrimVoid).probePayloadDecode(response(200, "ignored")));

        // 7. Optional branches
        RetryExecutor.RetryConfig cfgOpt = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200, 404), false, true, TestDto.class, headers -> codec, null
        );
        RetryExecutor execOpt = new RetryExecutor(cfgOpt);
        assertTrue(execOpt.probePayloadDecode(response(404, "missing"))); // missing resource
        assertTrue(execOpt.probePayloadDecode(response(200, null))); // empty body
        // Optional with raw type:
        RetryExecutor.RetryConfig cfgOptRaw = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, true, String.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgOptRaw).probePayloadDecode(response(200, "ok")));

        // Optional with DTO -> invokes deserialize
        assertTrue(execOpt.probePayloadDecode(response(200, "{\"id\":1}")));
        assertEquals(1, codec.deserializeCalls);

        // Standard DTO -> invokes deserialize
        RetryExecutor.RetryConfig cfgDto = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, TestDto.class, headers -> codec, null
        );
        assertTrue(new RetryExecutor(cfgDto).probePayloadDecode(response(200, "{\"id\":1}")));
        assertEquals(2, codec.deserializeCalls);

        // Optional with byte[], Void, void:
        RetryExecutor.RetryConfig cfgOptBytes = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, true, byte[].class, null, null
        );
        assertTrue(new RetryExecutor(cfgOptBytes).probePayloadDecode(response(200, "bytes")));

        RetryExecutor.RetryConfig cfgOptVoid = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, true, Void.class, null, null
        );
        assertTrue(new RetryExecutor(cfgOptVoid).probePayloadDecode(response(200, "void")));

        RetryExecutor.RetryConfig cfgOptPrimVoid = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, true, void.class, null, null
        );
        assertTrue(new RetryExecutor(cfgOptPrimVoid).probePayloadDecode(response(200, "void")));

        // probePayloadDecode when non-optional, non-raw, codecResolver is null
        RetryExecutor.RetryConfig cfgNullCodec = new RetryExecutor.RetryConfig(
                RetryPolicy.off(), HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, TestDto.class, null, null
        );
        assertThrows(NullPointerException.class, () -> new RetryExecutor(cfgNullCodec).probePayloadDecode(response(200, "{\"id\":1}")));

        // probePayloadDecode when onErrorParser is present but status does not match (200 OK)
        assertTrue(new RetryExecutor(cfgOnError).probePayloadDecode(response(200, "{\"id\":1}")));

        // probePayloadDecode with null body
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(new WireResponse(200, List.of(), (byte[]) null)));

        // probePayloadDecode with empty byte array (length == 0)
        assertTrue(new RetryExecutor(cfgUnexpected).probePayloadDecode(new WireResponse(200, List.of(), new byte[0])));
    }

    @Test
    @DisplayName("isRetryableStatus with onErrorParser that does not match current status")
    void isRetryableStatusWithNonMatchingOnErrorParser() {
        OnErrorParser parser = new OnErrorParser();
        parser.register(500, RuntimeException.class);
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, parser,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);
        // 503 is retryable, parser only has 500
        assertTrue(executor.isRetryableStatus(response(503, "unavailable")));
    }

    @Test
    @DisplayName("async: non-IOException failure is never retried and fails future immediately")
    void asyncNonIoExceptionFailsImmediately() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();
        RetryExecutor.RetryConfig config = new RetryExecutor.RetryConfig(
                policy, HttpMethod.GET, System.getLogger("test"), null, null,
                List.of(200), false, false, String.class, headers -> null, null
        );
        RetryExecutor executor = new RetryExecutor(config);

        CompletableFuture<WireResponse> future = executor.executeAsync(
                GET_REQ, null, new AtomicInteger(), 0,
                req -> CompletableFuture.failedFuture(new IllegalStateException("fatal"))
        );

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}
