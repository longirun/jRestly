package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.auth.Token;
import ru.jrestly.auth.TokenManager;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClientMetricsWireMockTest")
class ClientMetricsWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient client;
    private MetricsTestClient api;
    private RecordingMetricsListener recordingListener;

    public record TestDto(String id, String name) {
    }

    public record ErrorDto(String message) {
    }

    public static class RecordingMetricsListener implements ClientMetricsListener {
        final List<RequestContext> callStarts = new CopyOnWriteArrayList<>();
        final List<AttemptRecord> attempts = new CopyOnWriteArrayList<>();
        final List<RetryRecord> retries = new CopyOnWriteArrayList<>();
        final List<CallMetrics> completedCalls = new CopyOnWriteArrayList<>();

        record AttemptRecord(int attempt, Optional<WireResponse> response, Optional<Throwable> failure, Duration duration) {
        }

        record RetryRecord(int attempt, Throwable reason, Duration delay) {
        }

        @Override
        public void onCallStart(RequestContext context, WireRequest request) {
            callStarts.add(context);
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
            attempts.add(new AttemptRecord(attempt, response, failure, duration));
        }

        @Override
        public void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay) {
            retries.add(new RetryRecord(attempt, reason, delay));
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
            completedCalls.add(metrics);
        }
    }

    public static class DeclarativeTestListener implements ClientMetricsListener {
        static final List<CallMetrics> recorded = new CopyOnWriteArrayList<>();

        public DeclarativeTestListener() {
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
            recorded.add(metrics);
        }
    }

    public static class FaultyMetricsListener implements ClientMetricsListener {
        public FaultyMetricsListener() {
        }

        @Override
        public void onCallStart(RequestContext context, WireRequest request) {
            throw new RuntimeException("faulty onCallStart");
        }

        @Override
        public void onAttemptComplete(RequestContext context, WireRequest request, int attempt, Optional<WireResponse> response, Optional<Throwable> failure, Duration duration) {
            throw new RuntimeException("faulty onAttemptComplete");
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
            throw new RuntimeException("faulty onCallComplete");
        }
    }

    public interface MetricsTestClient {

        @Get(path = "/api/item")
        TestDto getItem(@RequestParam(name = "id") String id);

        @Post(path = "/api/item")
        TestDto createItem(@RequestBody TestDto item);

        @Get(path = "/api/retryable")
        @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50)
        TestDto getRetryable();

        @Get(path = "/api/error")
        @OnError(statuses = 422, errorObject = ErrorDto.class)
        TestDto getWithError();

        @Get(path = "/api/delayed")
        TestDto getDelayed();

        @Get(path = "/api/async")
        CompletableFuture<TestDto> getAsync();

        @Get(path = "/api/declarative")
        @WithMetrics(DeclarativeTestListener.class)
        TestDto getDeclarative();

        @Get(path = "/api/faulty")
        @WithMetrics(FaultyMetricsListener.class)
        TestDto getFaulty();
    }

    public interface RefreshMetricsClient {

        @Get(path = "/api/secure")
        TestDto getSecure();

        @Get(path = "/api/secure-retry")
        @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50)
        TestDto getSecureRetryable();

        @Get(path = "/api/secure-async")
        CompletableFuture<TestDto> getSecureAsync();
    }

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        recordingListener = new RecordingMetricsListener();
        client = JRestlyClient.builder()
                .baseUrl(wireMock.baseUrl())
                .metricsListener(recordingListener)
                .build();
        api = (MetricsTestClient) client.createController(MetricsTestClient.class);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("Successful GET call records callStart, attemptComplete, and callComplete with timings and bytes")
    void testSuccessfulGet() {
        String json = "{\"id\":\"101\",\"name\":\"Apple\"}";
        wireMock.stubFor(get(urlEqualTo("/api/item?id=101"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Length", String.valueOf(json.getBytes().length))
                        .withBody(json)));

        TestDto result = api.getItem("101");
        assertNotNull(result);
        assertEquals("Apple", result.name());

        assertEquals(1, recordingListener.callStarts.size());
        assertEquals("getItem", recordingListener.callStarts.getFirst().method().getName());

        assertEquals(1, recordingListener.attempts.size());
        RecordingMetricsListener.AttemptRecord attempt = recordingListener.attempts.getFirst();
        assertEquals(1, attempt.attempt());
        assertTrue(attempt.response().isPresent());
        assertEquals(200, attempt.response().get().statusCode());
        assertTrue(attempt.failure().isEmpty());
        assertTrue(attempt.duration().toNanos() > 0);

        assertEquals(1, recordingListener.completedCalls.size());
        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.isSuccess());
        assertFalse(metrics.isRetried());
        assertEquals(1, metrics.attempts());
        assertTrue(metrics.duration().toNanos() > 0);
        assertTrue(metrics.responseBytes() > 0);
    }

    @Test
    @DisplayName("POST with request body records exact requestBytes")
    void testPostWithRequestBody() {
        String respJson = "{\"id\":\"202\",\"name\":\"Created\"}";
        wireMock.stubFor(post(urlEqualTo("/api/item"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(respJson)));

        TestDto input = new TestDto("202", "Orange");
        TestDto created = api.createItem(input);
        assertNotNull(created);

        assertEquals(1, recordingListener.completedCalls.size());
        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(201, metrics.statusCode());
        assertTrue(metrics.isSuccess());
        assertTrue(metrics.requestBytes() > 0, "requestBytes should be measured");
        assertTrue(metrics.responseBytes() > 0, "responseBytes should be measured");
    }

    @Test
    @DisplayName("Retry loop records intermediate attempts, onRetry events, and final call metrics")
    void testRetryLoopMetrics() {
        String scenario = "RetryMetricsScenario";
        wireMock.stubFor(get(urlEqualTo("/api/retryable"))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("FIRST_FAIL")
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        wireMock.stubFor(get(urlEqualTo("/api/retryable"))
                .inScenario(scenario)
                .whenScenarioStateIs("FIRST_FAIL")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"303\",\"name\":\"Recovered\"}")));

        TestDto result = api.getRetryable();
        assertNotNull(result);
        assertEquals("Recovered", result.name());

        assertEquals(1, recordingListener.callStarts.size(), "onCallStart should fire once per logical call");
        assertEquals(2, recordingListener.attempts.size(), "Should record 2 attempts");

        RecordingMetricsListener.AttemptRecord att1 = recordingListener.attempts.get(0);
        assertEquals(1, att1.attempt());
        assertEquals(503, att1.response().get().statusCode());

        RecordingMetricsListener.AttemptRecord att2 = recordingListener.attempts.get(1);
        assertEquals(2, att2.attempt());
        assertEquals(200, att2.response().get().statusCode());

        assertEquals(1, recordingListener.retries.size(), "onRetry should fire once");
        RecordingMetricsListener.RetryRecord retry = recordingListener.retries.getFirst();
        assertEquals(1, retry.attempt());
        assertNotNull(retry.reason());

        assertEquals(1, recordingListener.completedCalls.size());
        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.isSuccess());
        assertTrue(metrics.isRetried());
        assertEquals(2, metrics.attempts());
    }

    @Test
    @DisplayName("HTTP 500 error records failure in onCallComplete")
    void testServerErrorMetrics() {
        wireMock.stubFor(get(urlEqualTo("/api/item?id=999"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        assertThrows(UnexpectedStatusException.class, () -> api.getItem("999"));

        assertEquals(1, recordingListener.completedCalls.size());
        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(500, metrics.statusCode());
        assertFalse(metrics.isSuccess());
        assertTrue(metrics.failure().isPresent());
        assertTrue(metrics.failure().get() instanceof UnexpectedStatusException);
    }

    @Test
    @DisplayName("@OnError response records HandledException and error status")
    void testOnErrorMetrics() {
        wireMock.stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Unprocessable Entity\"}")));

        HandledException ex = assertThrows(HandledException.class, () -> api.getWithError());
        assertEquals(422, ex.getStatusCode());

        assertEquals(1, recordingListener.completedCalls.size());
        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(422, metrics.statusCode());
        assertFalse(metrics.isSuccess());
        assertTrue(metrics.failure().isPresent());
        assertTrue(metrics.failure().get() instanceof HandledException);
    }

    @Test
    @DisplayName("In-flight active requests tracking via onCallStart and onCallComplete")
    void testInFlightRequestsGauge() {
        wireMock.stubFor(get(urlEqualTo("/api/delayed"))
                .willReturn(aResponse()
                        .withFixedDelay(100)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"404\",\"name\":\"Delayed\"}")));

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);

        ClientMetricsListener inFlightListener = new ClientMetricsListener() {
            @Override
            public void onCallStart(RequestContext context, WireRequest request) {
                int count = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(count, Math::max);
            }

            @Override
            public void onCallComplete(CallMetrics metrics) {
                inFlight.decrementAndGet();
            }
        };

        JRestlyClient gaugeClient = JRestlyClient.builder()
                .baseUrl(wireMock.baseUrl())
                .metricsListener(inFlightListener)
                .build();
        try {
            MetricsTestClient gaugeApi = (MetricsTestClient) gaugeClient.createController(MetricsTestClient.class);
            TestDto dto = gaugeApi.getDelayed();
            assertNotNull(dto);

            assertEquals(1, maxInFlight.get(), "During call, in-flight should have reached 1");
            assertEquals(0, inFlight.get(), "After call completion, in-flight should return to 0");
        } finally {
            gaugeClient.close();
        }
    }

    @Test
    @DisplayName("Async CompletableFuture call records all metrics in non-blocking execution")
    void testAsyncCallMetrics() {
        wireMock.stubFor(get(urlEqualTo("/api/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"505\",\"name\":\"Async\"}")));

        CompletableFuture<TestDto> future = api.getAsync();
        TestDto dto = future.join();
        assertNotNull(dto);
        assertEquals("Async", dto.name());

        assertEquals(1, recordingListener.callStarts.size());
        assertEquals(1, recordingListener.attempts.size());
        assertEquals(1, recordingListener.completedCalls.size());

        CallMetrics metrics = recordingListener.completedCalls.getFirst();
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.isSuccess());
        assertTrue(metrics.duration().toNanos() > 0);
    }

    @Test
    @DisplayName("Declarative @WithMetrics binds and triggers listener on method")
    void testDeclarativeWithMetrics() {
        DeclarativeTestListener.recorded.clear();
        wireMock.stubFor(get(urlEqualTo("/api/declarative"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"606\",\"name\":\"Declarative\"}")));

        TestDto dto = api.getDeclarative();
        assertNotNull(dto);

        // Global recordingListener should record
        assertEquals(1, recordingListener.completedCalls.size());
        // Declarative listener should also record
        assertEquals(1, DeclarativeTestListener.recorded.size());
        assertEquals(200, DeclarativeTestListener.recorded.getFirst().statusCode());
    }

    @Test
    @DisplayName("Fault tolerance: exceptions in metrics listener do not break HTTP client execution")
    void testFaultTolerance() {
        wireMock.stubFor(get(urlEqualTo("/api/faulty"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"707\",\"name\":\"Faulty\"}")));

        TestDto dto = api.getFaulty();
        assertNotNull(dto);
        assertEquals("Faulty", dto.name());
    }

    @Test
    @DisplayName("Transparent 401 refresh: attempts() sums both transmission phases (sync)")
    void refresh401SumsAttemptsAcrossPhases() {
        wireMock.stubFor(get(urlEqualTo("/api/secure"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized: token expired")));
        wireMock.stubFor(get(urlEqualTo("/api/secure"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"404\",\"name\":\"Recovered\"}")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            int count = tokenFetchCounter.incrementAndGet();
            return Token.of(count == 1 ? "stale-token" : "fresh-token");
        });

        JRestlyClient authClient = JRestlyClient.builder()
                .baseUrl(wireMock.baseUrl())
                .bearerAuth(tokenManager)
                .metricsListener(recordingListener)
                .build();
        try {
            RefreshMetricsClient authApi = (RefreshMetricsClient) authClient.createController(RefreshMetricsClient.class);
            TestDto result = authApi.getSecure();
            assertEquals("Recovered", result.name());
            assertEquals(2, tokenFetchCounter.get());

            assertEquals(1, recordingListener.completedCalls.size());
            CallMetrics metrics = recordingListener.completedCalls.getFirst();
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.isSuccess());
            assertTrue(metrics.isRetried(), "401 + refresh is two transmissions");
            assertEquals(2, metrics.attempts(), "attempts() is the total across both phases");
        } finally {
            authClient.close();
        }
    }

    @Test
    @DisplayName("401 refresh after a @Retry loop: attempts() sums retry phase and refresh phase (sync)")
    void refresh401AfterRetriesSumsBothPhases() {
        wireMock.stubFor(get(urlEqualTo("/api/secure-retry"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .inScenario("secure-retry-401")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("GOT401")
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));
        wireMock.stubFor(get(urlEqualTo("/api/secure-retry"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .inScenario("secure-retry-401")
                .whenScenarioStateIs("GOT401")
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized: token expired")));
        wireMock.stubFor(get(urlEqualTo("/api/secure-retry"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"405\",\"name\":\"Recovered\"}")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            int count = tokenFetchCounter.incrementAndGet();
            return Token.of(count == 1 ? "stale-token" : "fresh-token");
        });

        JRestlyClient authClient = JRestlyClient.builder()
                .baseUrl(wireMock.baseUrl())
                .bearerAuth(tokenManager)
                .metricsListener(recordingListener)
                .build();
        try {
            RefreshMetricsClient authApi = (RefreshMetricsClient) authClient.createController(RefreshMetricsClient.class);
            TestDto result = authApi.getSecureRetryable();
            assertEquals("Recovered", result.name());

            assertEquals(3, recordingListener.attempts.size(), "one onAttemptComplete per transmission");
            assertEquals(1, recordingListener.retries.size(), "only the 503 schedules a retry");

            assertEquals(1, recordingListener.completedCalls.size());
            CallMetrics metrics = recordingListener.completedCalls.getFirst();
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.isSuccess());
            assertEquals(3, metrics.attempts(), "2 retry-loop attempts + 1 refresh attempt");
        } finally {
            authClient.close();
        }
    }

    @Test
    @DisplayName("Transparent 401 refresh: attempts() sums both transmission phases (async)")
    void refresh401SumsAttemptsAcrossPhasesAsync() {
        wireMock.stubFor(get(urlEqualTo("/api/secure-async"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized: token expired")));
        wireMock.stubFor(get(urlEqualTo("/api/secure-async"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"406\",\"name\":\"Recovered\"}")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            int count = tokenFetchCounter.incrementAndGet();
            return Token.of(count == 1 ? "stale-token" : "fresh-token");
        });

        JRestlyClient authClient = JRestlyClient.builder()
                .baseUrl(wireMock.baseUrl())
                .bearerAuth(tokenManager)
                .metricsListener(recordingListener)
                .build();
        try {
            RefreshMetricsClient authApi = (RefreshMetricsClient) authClient.createController(RefreshMetricsClient.class);
            TestDto result = authApi.getSecureAsync().join();
            assertEquals("Recovered", result.name());
            assertEquals(2, tokenFetchCounter.get());

            assertEquals(1, recordingListener.completedCalls.size());
            CallMetrics metrics = recordingListener.completedCalls.getFirst();
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.isSuccess());
            assertEquals(2, metrics.attempts(), "attempts() is the total across both phases");
        } finally {
            authClient.close();
        }
    }

    @Test
    @DisplayName("Transport failure (dead port) records -1 status and failure exception")
    void testTransportFailureMetrics() {
        int deadPort = 65530;
        JRestlyClient deadClient = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:" + deadPort)
                .connectTimeout(java.time.Duration.ofMillis(200))
                .metricsListener(recordingListener)
                .build();
        try {
            MetricsTestClient deadApi = (MetricsTestClient) deadClient.createController(MetricsTestClient.class);
            assertThrows(RuntimeException.class, () -> deadApi.getItem("1"));

            assertEquals(1, recordingListener.completedCalls.size());
            CallMetrics metrics = recordingListener.completedCalls.getFirst();
            assertEquals(-1, metrics.statusCode());
            assertFalse(metrics.isSuccess());
            assertTrue(metrics.failure().isPresent());
        } finally {
            deadClient.close();
        }
    }
}
