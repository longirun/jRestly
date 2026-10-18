package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.metrics.SimpleCallMetrics;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit gate for the extracted metric-notification collaborator
 * ({@link MetricsNotifier}, ADR-0066 step 6): each of the four
 * listener-dispatch points ({@code callStart}/{@code attemptComplete}
 * /{@code retry}/{@code callComplete}, ADR-0036) is verified against its
 * full branch matrix — no-listener no-op, {@code null} context skip,
 * argument pass-through, and listener failure swallowed with and without a
 * logger. No network.
 */
@DisplayName("MetricsNotifierTest")
class MetricsNotifierTest {

    private static final RequestContext CONTEXT = context();

    private static WireRequest request(byte[] body) {
        return new WireRequest(
                HttpMethod.GET,
                URI.create("https://example.com/api"),
                List.of(),
                "application/json",
                body,
                null,
                null,
                null
        );
    }

    private static WireResponse response(byte[] body) {
        return new WireResponse(
                200,
                List.of(),
                body,
                null,
                HttpClient.Version.HTTP_1_1,
                List.of()
        );
    }

    private static RequestContext context() {
        try {
            Method method = MetricsNotifierTest.class.getDeclaredMethod("context");
            return new SimpleRequestContext("mod", MetricsNotifierTest.class, method, new Object[0]);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final byte[] BODY = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Listener that records every hook invocation.
     */
    private static final class RecordingListener implements ClientMetricsListener {
        final List<Object[]> starts = new ArrayList<>();
        RequestContext attemptContext;
        WireRequest attemptRequest;
        int attemptIndex;
        Optional<WireResponse> attemptResponse = Optional.empty();
        Optional<Throwable> attemptFailure = Optional.empty();
        Duration attemptDuration;
        int retries;
        int retryIndex;
        Throwable retryReason;
        Duration retryDelay;
        final List<SimpleCallMetrics> completions = new ArrayList<>();

        @Override
        public void onCallStart(RequestContext context, WireRequest request) {
            starts.add(new Object[]{context, request});
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
            this.attemptContext = context;
            this.attemptRequest = request;
            this.attemptIndex = attempt;
            this.attemptResponse = response;
            this.attemptFailure = failure;
            this.attemptDuration = duration;
        }

        @Override
        public void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
            this.retries++;
            this.retryIndex = attempt;
            this.retryReason = reason;
            this.retryDelay = delay;
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
            completions.add((SimpleCallMetrics) metrics);
        }
    }

    /**
     * Listener that throws from every hook — the dispatch must swallow it.
     */
    private static final class ThrowingListener implements ClientMetricsListener {
        static final IllegalStateException BOOM = new IllegalStateException("listener boom");

        @Override
        public void onCallComplete(CallMetrics metrics) {
            throw BOOM;
        }

        @Override
        public void onCallStart(RequestContext context, WireRequest request) {
            throw BOOM;
        }

        @Override
        public void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
            throw BOOM;
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
            throw BOOM;
        }
    }

    private static final class RecordingLogger implements System.Logger {
        int warnings;

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
            // abstract implementation — not counted as a recorded warning
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Object... params) {
            // abstract implementation — not counted as a recorded warning
        }

        @Override
        public void log(Level level, String msg, Throwable thrown) {
            warnings++;
        }
    }

    // callStart ------------------------------------------------------------

    @Test
    @DisplayName("callStart: no listener is a no-op")
    void callStartWithoutListenerIsNoOp() {
        MetricsNotifier.callStart(null, new RecordingLogger(), CONTEXT, request(null));
    }

    @Test
    @DisplayName("callStart: null context never touches the listener")
    void callStartWithNullContextDoesNotTouchListener() {
        RecordingListener listener = new RecordingListener();
        MetricsNotifier.callStart(listener, null, null, request(null));
        assertEquals(0, listener.starts.size());
    }

    @Test
    @DisplayName("callStart: passes context and request through as-is")
    void callStartPassesContextAndRequestToListener() {
        RecordingListener listener = new RecordingListener();
        WireRequest request = request(BODY);
        MetricsNotifier.callStart(listener, null, CONTEXT, request);
        assertEquals(1, listener.starts.size());
        assertSame(CONTEXT, listener.starts.get(0)[0]);
        assertSame(request, listener.starts.get(0)[1]);
    }

    @Test
    @DisplayName("callStart: listener failure is swallowed and logged with a logger")
    void callStartListenerFailureIsLoggedWhenLoggerPresent() {
        RecordingLogger logger = new RecordingLogger();
        MetricsNotifier.callStart(new ThrowingListener(), logger, CONTEXT, request(null));
        assertEquals(1, logger.warnings);
    }

    @Test
    @DisplayName("callStart: listener failure is swallowed without a logger")
    void callStartListenerFailureIsSilentWithoutLogger() {
        MetricsNotifier.callStart(new ThrowingListener(), null, CONTEXT, request(null));
    }

    // attemptComplete ------------------------------------------------------

    @Test
    @DisplayName("attemptComplete: no listener is a no-op")
    void attemptCompleteWithoutListenerIsNoOp() {
        MetricsNotifier.attemptComplete(null, new RecordingLogger(), CONTEXT, request(null), 1, null, null, System.nanoTime());
    }

    @Test
    @DisplayName("attemptComplete: null context never touches the listener")
    void attemptCompleteWithNullContextDoesNotTouchListener() {
        RecordingListener listener = new RecordingListener();
        MetricsNotifier.attemptComplete(listener, null, null, request(null), 1, null, null, System.nanoTime());
        assertEquals(0, listener.attemptIndex);
    }

    @Test
    @DisplayName("attemptComplete: success attempt wraps response and empty failure")
    void attemptCompletePassesWrappedResponse() {
        RecordingListener listener = new RecordingListener();
        WireRequest request = request(BODY);
        WireResponse response = response(BODY);
        MetricsNotifier.attemptComplete(listener, null, CONTEXT, request, 3, response, null, System.nanoTime());
        assertEquals(3, listener.attemptIndex);
        assertSame(request, listener.attemptRequest);
        assertSame(CONTEXT, listener.attemptContext);
        assertSame(response, listener.attemptResponse.orElse(null));
        assertEquals(Optional.empty(), listener.attemptFailure);
        assertNotNull(listener.attemptDuration);
    }

    @Test
    @DisplayName("attemptComplete: transport failure wraps empty response and the failure")
    void attemptCompletePassesWrappedFailure() {
        RecordingListener listener = new RecordingListener();
        Throwable failure = new IllegalStateException("io boom");
        MetricsNotifier.attemptComplete(listener, null, CONTEXT, request(null), 2, null, failure, System.nanoTime());
        assertEquals(Optional.empty(), listener.attemptResponse);
        assertSame(failure, listener.attemptFailure.orElse(null));
        assertNotNull(listener.attemptDuration);
    }

    @Test
    @DisplayName("attemptComplete: listener failure is swallowed and logged with a logger")
    void attemptCompleteListenerFailureIsLoggedWhenLoggerPresent() {
        RecordingLogger logger = new RecordingLogger();
        MetricsNotifier.attemptComplete(
                new ThrowingListener(), logger, CONTEXT, request(null), 1, response(BODY), null, System.nanoTime());
        assertEquals(1, logger.warnings);
    }

    @Test
    @DisplayName("attemptComplete: listener failure is swallowed without a logger")
    void attemptCompleteListenerFailureIsSilentWithoutLogger() {
        MetricsNotifier.attemptComplete(
                new ThrowingListener(), null, CONTEXT, request(null), 1, response(BODY), null, System.nanoTime());
    }

    // retry ----------------------------------------------------------------

    @Test
    @DisplayName("retry: no listener is a no-op")
    void retryWithoutListenerIsNoOp() {
        MetricsNotifier.retry(null, new RecordingLogger(), CONTEXT, request(null), 1, new RuntimeException("x"), 10);
    }

    @Test
    @DisplayName("retry: null context never touches the listener")
    void retryWithNullContextDoesNotTouchListener() {
        RecordingListener listener = new RecordingListener();
        MetricsNotifier.retry(listener, null, null, request(null), 1, new RuntimeException("x"), 10);
        assertEquals(0, listener.retries);
    }

    @Test
    @DisplayName("retry: positive delay is preserved in the delay duration")
    void retryPassesPositiveDelay() {
        RecordingListener listener = new RecordingListener();
        Throwable reason = new IllegalStateException("retry because");
        MetricsNotifier.retry(listener, null, CONTEXT, request(null), 4, reason, 250);
        assertEquals(1, listener.retries);
        assertEquals(4, listener.retryIndex);
        assertSame(reason, listener.retryReason);
        assertEquals(Duration.ofMillis(250), listener.retryDelay);
    }

    @Test
    @DisplayName("retry: negative delay is clamped to zero")
    void retryClampsNegativeDelayToZero() {
        RecordingListener listener = new RecordingListener();
        Throwable reason = new IllegalStateException("retry because");
        MetricsNotifier.retry(listener, null, CONTEXT, request(null), 4, reason, -100);
        assertEquals(1, listener.retries);
        assertSame(reason, listener.retryReason);
        assertEquals(Duration.ZERO, listener.retryDelay);
    }

    @Test
    @DisplayName("retry: listener failure is swallowed and logged with a logger")
    void retryListenerFailureIsLoggedWhenLoggerPresent() {
        RecordingLogger logger = new RecordingLogger();
        MetricsNotifier.retry(new ThrowingListener(), logger, CONTEXT, request(null), 1, new RuntimeException("x"), 5);
        assertEquals(1, logger.warnings);
    }

    @Test
    @DisplayName("retry: listener failure is swallowed without a logger")
    void retryListenerFailureIsSilentWithoutLogger() {
        MetricsNotifier.retry(new ThrowingListener(), null, CONTEXT, request(null), 1, new RuntimeException("x"), 5);
    }

    // callComplete ---------------------------------------------------------

    @Test
    @DisplayName("callComplete: no listener is a no-op, not even metrics allocation")
    void callCompleteWithoutListenerIsNoOp() {
        MetricsNotifier.callComplete(null, new RecordingLogger(), CONTEXT, request(null), null, null, System.nanoTime(), 0);
    }

    @Test
    @DisplayName("callComplete: null context never builds metrics")
    void callCompleteWithNullContextDoesNotBuildMetrics() {
        RecordingListener listener = new RecordingListener();
        MetricsNotifier.callComplete(listener, null, null, request(null), null, null, System.nanoTime(), 0);
        assertEquals(0, listener.completions.size());
    }

    @Test
    @DisplayName("callComplete: builds metrics with the true attempt count and byte accounting")
    void callCompleteBuildsMetricsWithTrueAttemptCount() {
        RecordingListener listener = new RecordingListener();
        WireRequest request = request(BODY);
        WireResponse wireResponse = response(BODY);
        MetricsNotifier.callComplete(listener, null, CONTEXT, request, wireResponse, null, System.nanoTime(), 3);
        assertEquals(1, listener.completions.size());
        SimpleCallMetrics metrics = listener.completions.get(0);
        assertSame(CONTEXT, metrics.context());
        assertSame(request, metrics.request());
        assertSame(wireResponse, metrics.response().orElse(null));
        assertEquals(Optional.empty(), metrics.failure());
        assertEquals(3, metrics.attempts());
        assertEquals(BODY.length, metrics.requestBytes());
        assertEquals(BODY.length, metrics.responseBytes());
        assertTrue(metrics.duration().toNanos() >= 0);
    }

    @Test
    @DisplayName("callComplete: listener failure is swallowed and logged with a logger")
    void callCompleteListenerFailureIsLoggedWhenLoggerPresent() {
        RecordingLogger logger = new RecordingLogger();
        MetricsNotifier.callComplete(
                new ThrowingListener(), logger, CONTEXT, request(BODY), response(BODY), null, System.nanoTime(), 1);
        assertEquals(1, logger.warnings);
    }

    @Test
    @DisplayName("callComplete: listener failure is swallowed without a logger")
    void callCompleteListenerFailureIsSilentWithoutLogger() {
        MetricsNotifier.callComplete(
                new ThrowingListener(), null, CONTEXT, request(BODY), response(BODY), null, System.nanoTime(), 1);
    }
}
