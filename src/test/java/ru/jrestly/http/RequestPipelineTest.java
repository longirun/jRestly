package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.fixtures.TestModuleInfo;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerConfig;
import ru.jrestly.resilience.CircuitBreakerMetrics;
import ru.jrestly.resilience.CircuitBreakerOpenException;
import ru.jrestly.resilience.RateLimitExceededException;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimiterConfig;
import ru.jrestly.resilience.RateLimiterMetrics;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * Unit tests for the request pipeline execution seam in {@link HttpTemplate} (ADR-0018, Step 4):
 * <ul>
 *     <li>Orderly sequential execution of {@link RequestInterceptor}s and request transformation.</li>
 *     <li>Invocation metadata access and attribute passing between request and response phases.</li>
 *     <li>Strict {@link NullPointerException} handling if a request interceptor returns {@code null}.</li>
 *     <li>Response interceptor notification on 2xx success with duration and HTTP status.</li>
 *     <li>Response interceptor notification on {@code @OnError} handled exceptions, unexpected status,
 *         and network transport failures.</li>
 *     <li>Failure isolation: response interceptor exceptions do not mask call results or abort subsequent interceptors.</li>
 *     <li>Sync and async twin symmetry: CompletableFuture calls invoke request/response interceptors non-blockingly.</li>
 *     <li>Stream safety: response interceptors inspect metadata without consuming the body stream.</li>
 *     <li>Zero-cost default path when no interceptors are configured.</li>
 * </ul>
 */
class RequestPipelineTest {

    @Test
    @DisplayName("request interceptors executed sequentially in order and transform the wire request")
    void requestInterceptorsExecutedInOrderAndModifyRequest() throws Exception {
        AtomicReference<HttpRequest> recorded = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8))
        )), recorded);

        HttpTemplate t = createTemplate("hello", new Object[]{"world"}, String.class);
        t.setHttpClient(client);

        List<String> executionOrder = new ArrayList<>();

        RequestInterceptor first = (req, ctx) -> {
            executionOrder.add("first");
            ctx.setAttribute("step", "firstDone");
            return req.withHeader("X-First", "1");
        };

        RequestInterceptor second = (req, ctx) -> {
            executionOrder.add("second");
            assertEquals("firstDone", ctx.getAttribute("step"));
            assertTrue(req.headers().stream().anyMatch(h -> "X-First".equalsIgnoreCase(h.name()) && "1".equals(h.value())));
            return req.withHeader("X-Second", "2");
        };

        t.setRequestInterceptors(List.of(first, second));

        String result = t.exchange();

        assertEquals("ok", result);
        assertEquals(List.of("first", "second"), executionOrder);
        assertNotNull(recorded.get());
        assertTrue(recorded.get().headers().firstValue("X-First").isPresent());
        assertEquals("1", recorded.get().headers().firstValue("X-First").get());
        assertTrue(recorded.get().headers().firstValue("X-Second").isPresent());
        assertEquals("2", recorded.get().headers().firstValue("X-Second").get());
    }

    @Test
    @DisplayName("request interceptor can inspect invocation metadata in RequestContext")
    void requestInterceptorCanInspectCallMetadata() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<String> module = new AtomicReference<>();
        AtomicReference<Class<?>> controller = new AtomicReference<>();
        AtomicReference<String> methodName = new AtomicReference<>();
        AtomicReference<Object[]> capturedArgs = new AtomicReference<>();

        t.setRequestInterceptors(List.of((req, ctx) -> {
            module.set(ctx.moduleName());
            controller.set(ctx.controllerClass());
            methodName.set(ctx.method().getName());
            capturedArgs.set(ctx.args());
            return req;
        }));

        t.exchange();

        assertEquals("test-module", module.get());
        assertSame(PipelineController.class, controller.get());
        assertEquals("hello", methodName.get());
        assertNotNull(capturedArgs.get());
        assertEquals(1, capturedArgs.get().length);
        assertEquals("Anton", capturedArgs.get()[0]);
    }

    @Test
    @DisplayName("request interceptor returning null throws NullPointerException and notifies response interceptor")
    void requestInterceptorReturningNullThrowsNullPointerException() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>());
        HttpTemplate t = createTemplate("hello", new Object[]{"world"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> responseContextRef = new AtomicReference<>();

        t.setRequestInterceptors(List.of((req, ctx) -> null));
        t.setResponseInterceptors(List.of(responseContextRef::set));

        NullPointerException npe = assertThrows(NullPointerException.class, t::exchange);
        assertTrue(npe.getMessage().contains("null WireRequest"));

        assertNotNull(responseContextRef.get());
        ResponseContext ctx = responseContextRef.get();
        assertFalse(ctx.isSuccess());
        assertEquals(-1, ctx.statusCode());
        assertTrue(ctx.response().isEmpty());
        assertTrue(ctx.failure().isPresent());
        assertSame(npe, ctx.failure().get());
    }

    @Test
    @DisplayName("response interceptor receives successful response, timing, status, and attributes")
    void responseInterceptorReceivesSuccessfulResponseAndTiming() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of("X-Res", List.of("custom")), "hello Anton".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        t.setRequestInterceptors(List.of((req, ctx) -> {
            ctx.setAttribute("traceId", "tr-1234");
            return req.withHeader("X-Trace-Id", "tr-1234");
        }));
        t.setResponseInterceptors(List.of(capturedContext::set));

        String result = t.exchange();

        assertEquals("hello Anton", result);
        assertNotNull(capturedContext.get());

        ResponseContext ctx = capturedContext.get();
        assertTrue(ctx.isSuccess());
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.response().isPresent());
        assertTrue(ctx.failure().isEmpty());
        assertTrue(ctx.duration().compareTo(Duration.ZERO) >= 0);
        assertEquals(Optional.of("tr-1234"), ctx.getAttribute("traceId", String.class));
        assertTrue(ctx.response().get().headers().stream().anyMatch(h -> "X-Res".equalsIgnoreCase(h.name())));
    }

    @Test
    @DisplayName("response interceptor receives HandledException on configured @OnError status")
    void responseInterceptorReceivesHandledExceptionOnConfiguredErrorStatus() throws Exception {
        String errorJson = "{\"error\":\"bad_input\"}";
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(400, Map.of("X-Error-Reason", List.of("validation")), errorJson.getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("errorCall", new Object[0], String.class);
        t.setHttpClient(client);

        StubCodec codec = new StubCodec();
        ErrorDto errorObject = new ErrorDto("bad_input");
        codec.result = errorObject;
        t.setJsonCodec(codec);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        t.setResponseInterceptors(List.of(capturedContext::set));

        HandledException ex = assertThrows(HandledException.class, t::exchange);
        assertSame(errorObject, ex.getDetails());

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertFalse(ctx.isSuccess());
        assertEquals(400, ctx.statusCode());
        assertTrue(ctx.response().isPresent());
        assertTrue(ctx.failure().isPresent());
        assertSame(ex, ctx.failure().get());
    }

    @Test
    @DisplayName("response interceptor receives UnexpectedStatusException on unconfigured error status")
    void responseInterceptorReceivesUnexpectedStatusExceptionOnUnconfiguredErrorStatus() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(500, Map.of(), "internal server error".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("unexpectedCall", new Object[0], String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        t.setResponseInterceptors(List.of(capturedContext::set));

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, t::exchange);
        assertEquals(500, ex.getStatusCode());
        assertEquals("internal server error", ex.getRawBody());

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertFalse(ctx.isSuccess());
        assertEquals(500, ctx.statusCode());
        assertTrue(ctx.response().isPresent());
        assertTrue(ctx.failure().isPresent());
        assertSame(ex, ctx.failure().get());
    }

    @Test
    @DisplayName("response interceptor receives transport failure on network IOException")
    void responseInterceptorReceivesTransportFailureOnNetworkError() throws Exception {
        IOException networkError = new IOException("Connection refused");
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>()) {
            @Override
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
                throw networkError;
            }
        };

        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        t.setResponseInterceptors(List.of(capturedContext::set));

        RuntimeException thrown = assertThrows(RuntimeException.class, t::exchange);
        assertSame(networkError, thrown.getCause());

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertFalse(ctx.isSuccess());
        assertEquals(-1, ctx.statusCode());
        assertTrue(ctx.response().isEmpty());
        assertTrue(ctx.failure().isPresent());
        assertSame(networkError, ctx.failure().get());
    }

    @Test
    @DisplayName("response interceptor exception is isolated and does not break call or subsequent interceptors")
    void responseInterceptorExceptionIsIsolatedAndDoesNotBreakCallOrOtherInterceptors() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicBoolean secondExecuted = new AtomicBoolean(false);

        ResponseInterceptor failingInterceptor = ctx -> {
            throw new RuntimeException("Metrics telemetry crashed!");
        };
        ResponseInterceptor workingInterceptor = ctx -> {
            secondExecuted.set(true);
        };

        t.setResponseInterceptors(List.of(failingInterceptor, workingInterceptor));

        String result = t.exchange();

        assertEquals("ok", result);
        assertTrue(secondExecuted.get(), "Subsequent response interceptors must still execute");
    }

    @Test
    @DisplayName("async twin: request and response interceptors execute symmetrically and non-blockingly")
    void asyncTwinRequestAndResponseInterceptorsExecuteSymmetrically() throws Exception {
        AtomicReference<HttpRequest> recorded = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of("X-Async", List.of("yes")), "async ok".getBytes(StandardCharsets.UTF_8))
        )), recorded);

        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        t.setRequestInterceptors(List.of((req, ctx) -> {
            ctx.setAttribute("asyncAttr", "val42");
            return req.withHeader("X-Trace-Async", "async-trace-1");
        }));
        t.setResponseInterceptors(List.of(capturedContext::set));

        CompletableFuture<String> future = t.exchange();
        String result = future.join();

        assertEquals("async ok", result);
        assertNotNull(recorded.get());
        assertEquals("async-trace-1", recorded.get().headers().firstValue("X-Trace-Async").orElse(null));

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertTrue(ctx.isSuccess());
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.response().isPresent());
        assertTrue(ctx.failure().isEmpty());
        assertEquals(Optional.of("val42"), ctx.getAttribute("asyncAttr", String.class));
    }

    @Test
    @DisplayName("async twin: response interceptor captures failure on unexpected 500 status")
    void asyncTwinResponseInterceptorCapturesFailure() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(500, Map.of(), "async server error".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        t.setResponseInterceptors(List.of(capturedContext::set));

        CompletableFuture<String> future = t.exchange();
        UnexpectedStatusException ex = causeOf(future::join);

        assertEquals(500, ex.getStatusCode());
        assertEquals("async server error", ex.getRawBody());

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertFalse(ctx.isSuccess());
        assertEquals(500, ctx.statusCode());
        assertTrue(ctx.failure().isPresent());
        assertSame(ex, ctx.failure().get());
    }

    @Test
    @DisplayName("async twin: request interceptor exception fails future and notifies response interceptor")
    void asyncTwinRequestInterceptorExceptionFailsFutureAndNotifiesResponseInterceptor() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>());
        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        IllegalStateException expectedError = new IllegalStateException("Async validation failed");

        t.setRequestInterceptors(List.of((req, ctx) -> {
            throw expectedError;
        }));
        t.setResponseInterceptors(List.of(capturedContext::set));

        CompletableFuture<String> future = t.exchange();
        IllegalStateException ex = causeOf(future::join);

        assertSame(expectedError, ex);

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertFalse(ctx.isSuccess());
        assertEquals(-1, ctx.statusCode());
        assertTrue(ctx.response().isEmpty());
        assertTrue(ctx.failure().isPresent());
        assertSame(expectedError, ctx.failure().get());
    }

    @Test
    @DisplayName("ADR-0036 #258: interceptor failure pairs onCallStart with onCallComplete and reports 0 attempts (sync)")
    void requestInterceptorFailurePairedMetricsLifecycle() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>());
        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);

        RuntimeException boom = new RuntimeException("interceptor boom");
        t.setRequestInterceptors(List.of((req, ctx) -> {
            throw boom;
        }));

        RuntimeException thrown = assertThrows(RuntimeException.class, t::exchange);
        assertSame(boom, thrown);

        // onCallStart must fire exactly once, before onCallComplete (1:1 pairing),
        // and attempts() must be 0 — the request never reached the wire.
        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
    }

    @Test
    @DisplayName("ADR-0036 #258: interceptor failure pairs onCallStart with onCallComplete and reports 0 attempts (async)")
    void asyncTwinRequestInterceptorFailurePairedMetricsLifecycle() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>());
        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);

        IllegalStateException boom = new IllegalStateException("async interceptor boom");
        t.setRequestInterceptors(List.of((req, ctx) -> {
            throw boom;
        }));

        CompletableFuture<String> future = t.exchange();
        IllegalStateException ex = causeOf(future::join);
        assertSame(boom, ex);

        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
    }

    @Test
    @DisplayName("ADR-0036 #259: rate-limiter rejection reports 0 attempts with paired start/complete (sync)")
    void rateLimiterRejectionPairedMetricsLifecycle() throws Exception {
        AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), lastRequest);
        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);
        t.setRateLimiter(RejectionRateLimiter.INSTANCE);

        assertThrows(RateLimitExceededException.class, t::exchange);

        // 1:1 pairing, no onAttemptComplete (nothing reached the wire), true count 0.
        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
        assertNull(lastRequest.get(), "the call never reached the wire");
    }

    @Test
    @DisplayName("ADR-0036 #259: open circuit-breaker rejection reports 0 attempts with paired start/complete (sync)")
    void circuitBreakerOpenRejectionPairedMetricsLifecycle() throws Exception {
        AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), lastRequest);
        HttpTemplate t = createTemplate("hello", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);
        t.setCircuitBreaker(OpenCircuitBreaker.INSTANCE);

        assertThrows(CircuitBreakerOpenException.class, t::exchange);

        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
        assertNull(lastRequest.get(), "the call never reached the wire");
    }

    @Test
    @DisplayName("ADR-0036 #259: rate-limiter rejection reports 0 attempts with paired start/complete (async)")
    void asyncTwinRateLimiterRejectionPairedMetricsLifecycle() throws Exception {
        AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), lastRequest);
        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);
        t.setRateLimiter(RejectionRateLimiter.INSTANCE);

        CompletableFuture<String> future = t.exchange();
        RateLimitExceededException ex = causeOf(future::join);
        assertNotNull(ex);

        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
        assertNull(lastRequest.get(), "the call never reached the wire");
    }

    @Test
    @DisplayName("ADR-0036 #259: open circuit-breaker rejection reports 0 attempts with paired start/complete (async)")
    void asyncTwinCircuitBreakerOpenRejectionPairedMetricsLifecycle() throws Exception {
        AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(), lastRequest);
        HttpTemplate t = createTemplate("helloAsync", new Object[]{"Anton"}, String.class);
        t.setHttpClient(client);

        RecordingMetrics metrics = new RecordingMetrics();
        t.setMetricsListener(metrics);
        t.setCircuitBreaker(OpenCircuitBreaker.INSTANCE);

        CompletableFuture<String> future = t.exchange();
        CircuitBreakerOpenException ex = causeOf(future::join);
        assertNotNull(ex);

        assertEquals(List.of("start", "complete"), metrics.events);
        assertEquals(List.of(0), metrics.attempts);
        assertNull(lastRequest.get(), "the call never reached the wire");
    }

    @Test
    @DisplayName("stream response interceptor inspects metadata without consuming the live body stream")
    void streamResponseInterceptorReceivesMetadataWithoutConsumingBody() throws Exception {
        byte[] payload = "streaming-pipe-data".getBytes(StandardCharsets.UTF_8);
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of("Content-Type", List.of("application/octet-stream")), new ByteArrayInputStream(payload))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("streamCall", new Object[0], InputStream.class);
        t.setHttpClient(client);

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();
        t.setResponseInterceptors(List.of(capturedContext::set));

        InputStream stream = t.exchange();
        assertNotNull(stream);

        assertNotNull(capturedContext.get());
        ResponseContext ctx = capturedContext.get();
        assertTrue(ctx.isSuccess());
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.response().isPresent());
        assertNotNull(ctx.response().get().stream(), "Stream in response must be non-null");

        // The body stream must remain untouched and fully readable by the caller
        byte[] readBytes = stream.readAllBytes();
        assertArrayEquals(payload, readBytes);
    }

    @Test
    @DisplayName("zero-cost path when no interceptors are configured executes normally")
    void zeroCostPathWhenNoInterceptorsConfigured() throws Exception {
        StubHttpClient client = new StubHttpClient(new ArrayDeque<>(List.of(
                stubResponse(200, Map.of(), "zero-cost".getBytes(StandardCharsets.UTF_8))
        )), new AtomicReference<>());

        HttpTemplate t = createTemplate("hello", new Object[]{"zero"}, String.class);
        t.setHttpClient(client);
        t.setRequestInterceptors(List.of());
        t.setResponseInterceptors(List.of());

        String result = t.exchange();
        assertEquals("zero-cost", result);
    }

    // ------------------------------------------------------------------
    // Helpers and stubs
    // ------------------------------------------------------------------

    private interface PipelineController {
        @Get(path = "/api/hello")
        String hello(@RequestParam(name = "name") String name);

        @Get(path = "/api/hello-async")
        CompletableFuture<String> helloAsync(@RequestParam(name = "name") String name);

        @Get(path = "/api/error")
        @OnError(statuses = {400}, errorObject = ErrorDto.class)
        String errorCall();

        @Get(path = "/api/unexpected")
        String unexpectedCall();

        @Get(path = "/api/stream")
        InputStream streamCall();
    }

    private record ErrorDto(String error) {
    }

    private HttpTemplate createTemplate(String methodName, Object[] args, Type returnType) throws NoSuchMethodException {
        Method method = findMethod(methodName);
        TestModuleInfo moduleInfo = new TestModuleInfo("http://localhost:1");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(
                moduleInfo,
                new StubHttpClient(new ArrayDeque<>(), new AtomicReference<>()),
                method,
                args,
                PipelineController.class
        );
        HttpTemplate template = builder.build();
        template.setJsonCodec(new StubCodec());
        return template;
    }

    private Method findMethod(String name) {
        for (Method m : PipelineController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private static <T> StubResponse<T> stubResponse(int status, Map<String, List<String>> headers, T body) {
        return new StubResponse<>(status, headers, body);
    }

    /**
     * Records the metrics lifecycle events in invocation order: "start" on
     * {@code onCallStart}, "attempt" on {@code onAttemptComplete}, "complete"
     * on {@code onCallComplete} (capturing the reported {@code attempts()}).
     */
    private static final class RecordingMetrics implements ClientMetricsListener {
        final List<String> events = new ArrayList<>();
        final List<Integer> attempts = new ArrayList<>();

        @Override
        public void onCallStart(RequestContext context, WireRequest request) {
            events.add("start");
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
            events.add("attempt");
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
            events.add("complete");
            attempts.add(metrics.attempts());
        }
    }

    /**
     * Rate limiter that always rejects, pinning the pre-wire rejection paths.
     */
    private static final class RejectionRateLimiter implements RateLimiter {
        static final RejectionRateLimiter INSTANCE = new RejectionRateLimiter();

        @Override
        public String name() {
            return "rejection";
        }

        @Override
        public boolean tryAcquire(Duration timeout) {
            return false;
        }

        @Override
        public RateLimiterConfig config() {
            return RateLimiterConfig.ofDefaults();
        }

        @Override
        public RateLimiterMetrics metrics() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Circuit breaker that is always open, pinning the pre-wire rejection paths.
     */
    private static final class OpenCircuitBreaker implements CircuitBreaker {
        static final OpenCircuitBreaker INSTANCE = new OpenCircuitBreaker();

        @Override
        public String name() {
            return "open-cb";
        }

        @Override
        public State getState() {
            return State.OPEN;
        }

        @Override
        public boolean tryAcquirePermission() {
            return false;
        }

        @Override
        public void recordSuccess(long durationNanos) {
        }

        @Override
        public void recordFailure(long durationNanos, Throwable throwable) {
        }

        @Override
        public Duration remainingWaitDuration() {
            return Duration.ofSeconds(1);
        }

        @Override
        public CircuitBreakerConfig config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CircuitBreakerMetrics metrics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transitionToClosedState() {
        }

        @Override
        public void transitionToOpenState() {
        }

        @Override
        public void transitionToHalfOpenState() {
        }
    }

    private static final class StubCodec implements JsonCodec {
        Object result;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String payload, Type type) {
            return (T) result;
        }

        @Override
        public String serialize(Object value) {
            return "{}";
        }

        @Override
        public String prettyPrint(String payload) {
            return payload;
        }
    }

    private static class StubHttpClient extends HttpClient {
        private final Deque<HttpResponse<?>> responses;
        private final AtomicReference<HttpRequest> lastRequest;

        StubHttpClient(Deque<HttpResponse<?>> responses, AtomicReference<HttpRequest> lastRequest) {
            this.responses = responses;
            this.lastRequest = lastRequest;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            lastRequest.set(request);
            if (responses.isEmpty()) {
                throw new IOException("No stub response queued");
            }
            return (HttpResponse<T>) responses.poll();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest.set(request);
            if (responses.isEmpty()) {
                return CompletableFuture.failedFuture(new IOException("No stub response queued"));
            }
            return CompletableFuture.completedFuture((HttpResponse<T>) responses.poll());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }
    }

    private record StubResponse<T>(int status, Map<String, List<String>> rawHeaders, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(rawHeaders, (name, value) -> true);
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost:1/api/test");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
