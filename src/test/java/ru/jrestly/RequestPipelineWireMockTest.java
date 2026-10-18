package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock integration suite for ADR-0018: Request pipeline interceptors and hooks.
 * Validates real HTTP network execution, bearer authentication token injection, distributed tracing,
 * latency audits, error status observation, network fault tolerance, and async twin symmetry.
 */
class RequestPipelineWireMockTest extends BaseWireMockTest {

    private final List<AbstractHttpClient> createdClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDownCreatedClients() {
        for (AbstractHttpClient clientToClose : createdClients) {
            clientToClose.close();
        }
        createdClients.clear();
    }

    private JRestlyClient createClient(UnaryOperator<JRestlyClient.Builder> configurator) {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl(baseUrl());
        JRestlyClient newClient = configurator.apply(builder).build();
        createdClients.add(newClient);
        return newClient;
    }

    interface PipelineWireMockApi {

        @Get("/api/secure/data")
        String getSecureData();

        @Get("/api/trace")
        String traceRequest();

        @Get("/api/users/${id}")
        String getUserById(@PathVariable(name = "id") String id);

        @Get("/api/resource-with-error")
        @OnError(statuses = {400}, errorObject = TestErrorDto.class)
        String getResourceWithError();

        @Get("/api/unhandled-crash")
        String getUnhandledCrash();

        @Get("/api/faulty-network")
        String getFaultyNetwork();

        @Get("/api/async-data")
        CompletableFuture<String> asyncGetData(@RequestParam(name = "q") String query);

        @Get("/api/chain")
        String getChainedData();
    }

    @Test
    @DisplayName("bearer auth token injection: request interceptor decorates Authorization header")
    void bearerAuthTokenInjectionAndPropagation() {
        stubFor(get(urlEqualTo("/api/secure/data"))
                .withHeader("Authorization", equalTo("Bearer sec-tok-999"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("secure-content")));

        JRestlyClient customClient = createClient(b ->
                b.addRequestInterceptor((req, ctx) -> req.withHeader("Authorization", "Bearer sec-tok-999")));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        String result = api.getSecureData();

        assertEquals("secure-content", result);
        verify(getRequestedFor(urlEqualTo("/api/secure/data"))
                .withHeader("Authorization", equalTo("Bearer sec-tok-999")));
    }

    @Test
    @DisplayName("distributed tracing: correlation ID generated in request phase and verified in response interceptor")
    void distributedTracingCorrelationIdPropagationAndContextAttribute() {
        String correlationId = "corr-" + UUID.randomUUID();

        stubFor(get(urlEqualTo("/api/trace"))
                .withHeader("X-Correlation-ID", equalTo(correlationId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("X-Server-Trace-Ack", "ack-1")
                        .withBody("trace-ok")));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        RequestInterceptor tracingInjector = (req, ctx) -> {
            ctx.setAttribute("cid", correlationId);
            return req.withHeader("X-Correlation-ID", correlationId);
        };

        ResponseInterceptor auditHook = capturedContext::set;

        JRestlyClient customClient = createClient(b -> b
                .addRequestInterceptor(tracingInjector)
                .addResponseInterceptor(auditHook));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        String responseBody = api.traceRequest();

        assertEquals("trace-ok", responseBody);

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals(correlationId, ctx.getAttribute("cid"));
        assertEquals(Optional.of(correlationId), ctx.getAttribute("cid", String.class));
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.isSuccess());
        assertTrue(ctx.response().isPresent());
        assertTrue(ctx.response().get().headers().stream()
                .anyMatch(h -> "X-Server-Trace-Ack".equalsIgnoreCase(h.name()) && "ack-1".equals(h.value())));
        assertFalse(ctx.duration().isNegative());
    }

    @Test
    @DisplayName("latency audit: response interceptor collects invocation metadata and timing")
    void latencyAuditAndInvocationMetadataCollection() {
        stubFor(get(urlEqualTo("/api/users/usr-77"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("user-details-77")));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        JRestlyClient customClient = createClient(b -> b
                .name("audit-module")
                .addResponseInterceptor(capturedContext::set));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        String user = api.getUserById("usr-77");

        assertEquals("user-details-77", user);

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals("audit-module", ctx.moduleName());
        assertEquals(PipelineWireMockApi.class, ctx.controllerClass());
        assertEquals("getUserById", ctx.method().getName());
        assertEquals(1, ctx.args().length);
        assertEquals("usr-77", ctx.args()[0]);
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.isSuccess());
        assertFalse(ctx.failure().isPresent());
        assertFalse(ctx.duration().isNegative());
    }

    @Test
    @DisplayName("handled error: response interceptor captures @OnError HandledException with deserialized body")
    void handledErrorCapturedByResponseInterceptorWithDecodedErrorObject() {
        stubFor(get(urlEqualTo("/api/resource-with-error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"ERR_INVALID_INPUT\",\"message\":\"value must be positive\"}")));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        JRestlyClient customClient = createClient(b -> b
                .addResponseInterceptor(capturedContext::set));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);

        HandledException ex = assertThrows(HandledException.class, api::getResourceWithError);
        assertEquals(400, ex.getStatusCode());
        Object details = ex.getDetails();
        assertInstanceOf(TestErrorDto.class, details);
        TestErrorDto errorDto = (TestErrorDto) details;
        assertEquals("ERR_INVALID_INPUT", errorDto.code());
        assertEquals("value must be positive", errorDto.message());

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals(400, ctx.statusCode());
        assertFalse(ctx.isSuccess());
        assertTrue(ctx.failure().isPresent());
        assertSame(ex, ctx.failure().get());
        assertTrue(ctx.response().isPresent());
        assertEquals(400, ctx.response().get().statusCode());
        assertFalse(ctx.duration().isNegative());
    }

    @Test
    @DisplayName("unexpected error: response interceptor captures 500 UnexpectedStatusException with raw payload")
    void unexpectedErrorCapturedByResponseInterceptorWithRawPayload() {
        stubFor(get(urlEqualTo("/api/unhandled-crash"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Internal database failure")));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        JRestlyClient customClient = createClient(b -> b
                .addResponseInterceptor(capturedContext::set));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, api::getUnhandledCrash);
        assertEquals(500, ex.getStatusCode());
        assertEquals("Internal database failure", ex.getRawBody());

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals(500, ctx.statusCode());
        assertFalse(ctx.isSuccess());
        assertTrue(ctx.failure().isPresent());
        assertSame(ex, ctx.failure().get());
        assertTrue(ctx.response().isPresent());
        assertEquals(500, ctx.response().get().statusCode());
    }

    @Test
    @DisplayName("network fault: response interceptor captures transport failure with negative status code")
    void networkFaultCapturedByResponseInterceptor() {
        stubFor(get(urlEqualTo("/api/faulty-network"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        JRestlyClient customClient = createClient(b -> b
                .addResponseInterceptor(capturedContext::set));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, api::getFaultyNetwork);
        assertTrue(thrown.getCause() instanceof IOException);

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals(-1, ctx.statusCode());
        assertFalse(ctx.isSuccess());
        assertTrue(ctx.response().isEmpty());
        assertTrue(ctx.failure().isPresent());
        assertSame(thrown.getCause(), ctx.failure().get());
    }

    @Test
    @DisplayName("async twin: interceptors execute symmetrically for CompletableFuture calls")
    void asyncTwinInterceptorsExecuteSymmetrically() throws Exception {
        stubFor(get(urlPathEqualTo("/api/async-data"))
                .withQueryParam("q", equalTo("fast"))
                .withHeader("X-Async-Token", equalTo("tok-async-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("async-payload")));

        AtomicReference<ResponseContext> capturedContext = new AtomicReference<>();

        RequestInterceptor requestInterceptor = (req, ctx) -> {
            ctx.setAttribute("asyncStart", true);
            return req.withHeader("X-Async-Token", "tok-async-123");
        };

        JRestlyClient customClient = createClient(b -> b
                .addRequestInterceptor(requestInterceptor)
                .addResponseInterceptor(capturedContext::set));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        CompletableFuture<String> future = api.asyncGetData("fast");

        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("async-payload", result);

        ResponseContext ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals(Boolean.TRUE, ctx.getAttribute("asyncStart"));
        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.isSuccess());
        assertFalse(ctx.failure().isPresent());
        assertTrue(ctx.response().isPresent());
        assertEquals("asyncGetData", ctx.method().getName());
    }

    @Test
    @DisplayName("isolation: failing response interceptor does not abort call or subsequent interceptors")
    void responseInterceptorFailureIsolationDuringRealHttpCall() {
        stubFor(get(urlEqualTo("/api/chain"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("chain-ok")));

        List<String> auditLog = new CopyOnWriteArrayList<>();

        ResponseInterceptor faultyHook = ctx -> {
            auditLog.add("faulty-invoked");
            throw new RuntimeException("Simulated metrics logging failure");
        };

        ResponseInterceptor healthyHook = ctx -> {
            auditLog.add("healthy-invoked:" + ctx.statusCode());
        };

        JRestlyClient customClient = createClient(b -> b
                .addResponseInterceptor(faultyHook)
                .addResponseInterceptor(healthyHook));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        String result = api.getChainedData();

        assertEquals("chain-ok", result);
        assertEquals(List.of("faulty-invoked", "healthy-invoked:200"), auditLog);
    }

    @Test
    @DisplayName("chained interceptors: UnaryOperator and contextual RequestInterceptor both apply in order")
    void requestInterceptorChainWithUnaryOperatorAndContextualInterceptor() {
        stubFor(get(urlEqualTo("/api/chain"))
                .withHeader("X-Op-Header", equalTo("val-1"))
                .withHeader("X-Ctx-Header", equalTo("val-2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("chain-ok")));

        UnaryOperator<WireRequest> operator = req -> req.withHeader("X-Op-Header", "val-1");
        RequestInterceptor contextual = (req, ctx) -> req.withHeader("X-Ctx-Header", "val-2");

        JRestlyClient customClient = createClient(b -> b
                .addRequestInterceptor(operator)
                .addRequestInterceptor(contextual));

        PipelineWireMockApi api = customClient.get(PipelineWireMockApi.class);
        String result = api.getChainedData();

        assertEquals("chain-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/chain"))
                .withHeader("X-Op-Header", equalTo("val-1"))
                .withHeader("X-Ctx-Header", equalTo("val-2")));
    }
}
