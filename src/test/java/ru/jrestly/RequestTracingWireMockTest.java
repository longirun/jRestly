package ru.jrestly;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.Trace;
import ru.jrestly.http.CapturedResponse;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.tracing.TraceContext;
import ru.jrestly.tracing.TracePropagators;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request tracing end-to-end (ADR-0042): {@code @Trace} injects correlation IDs
 * (custom header, W3C {@code traceparent}, UUID v4), respects user-supplied headers and
 * {@code forceNew}, is disabled by {@code enabled = false}, and falls back to the
 * client-level propagator. WireMock e2e.
 */
@DisplayName("Request Tracing & Correlation ID Propagation Integration Tests (ADR-0042)")
class RequestTracingWireMockTest extends BaseWireMockTest {

    @Trace
    public interface InterfaceLevelTraceApi {
        @Get("/api/trace/default")
        String defaultTrace();

        @Get("/api/trace/custom")
        @Trace(header = "X-Correlation-ID")
        String customHeaderTrace();

        @Get("/api/trace/w3c")
        @Trace(w3c = true)
        String w3cTrace();

        @Get("/api/trace/disabled")
        @Trace(enabled = false)
        String disabledTrace();

        @Get("/api/trace/preserve")
        String preserveHeader(@RequestHeader(name = "X-Request-ID") String id);

        @Get("/api/trace/force-new")
        @Trace(forceNew = true)
        String forceNewTrace(@RequestHeader(name = "X-Request-ID") String id);

        @Get("/api/trace/force-new-w3c")
        @Trace(w3c = true, forceNew = true)
        String forceNewW3cTrace(@RequestHeader(name = "traceparent") String traceparent);

        @Get("/api/trace/async")
        CompletableFuture<String> asyncTrace();
    }

    public interface UntracedApi {
        @Get("/api/untraced/ping")
        String ping();
    }

    @Test
    @DisplayName("1. Interface-level @Trace injects X-Request-ID with valid UUID v4")
    void shouldInjectDefaultUuidTrace() {
        stubFor(get(urlEqualTo("/api/trace/default"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.defaultTrace();
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/default")));
        assertEquals(1, requests.size());
        String reqId = requests.get(0).getHeader("X-Request-ID");
        assertNotNull(reqId);
        // Valid UUID check
        assertNotNull(UUID.fromString(reqId));
    }

    @Test
    @DisplayName("2. Method-level @Trace(header) overrides header name to X-Correlation-ID")
    void shouldInjectCustomHeaderTrace() {
        stubFor(get(urlEqualTo("/api/trace/custom"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.customHeaderTrace();
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/custom")));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).containsHeader("X-Request-ID"));
        String corrId = requests.get(0).getHeader("X-Correlation-ID");
        assertNotNull(corrId);
        assertNotNull(UUID.fromString(corrId));
    }

    @Test
    @DisplayName("3. Method-level @Trace(w3c = true) injects standard W3C traceparent")
    void shouldInjectW3cTraceparent() {
        stubFor(get(urlEqualTo("/api/trace/w3c"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.w3cTrace();
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/w3c")));
        assertEquals(1, requests.size());
        String traceparent = requests.get(0).getHeader("traceparent");
        assertNotNull(traceparent);
        assertTrue(TraceContext.parse(traceparent).isPresent());
        assertTrue(traceparent.startsWith("00-"));
    }

    @Test
    @DisplayName("4. Method-level @Trace(enabled = false) disables tracing on specific method")
    void shouldDisableTraceOnMethod() {
        stubFor(get(urlEqualTo("/api/trace/disabled"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.disabledTrace();
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/disabled")));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).containsHeader("X-Request-ID"));
        assertFalse(requests.get(0).containsHeader("traceparent"));
    }

    @Test
    @DisplayName("5. User-supplied @RequestHeader preserves explicit ID without overwrite")
    void shouldPreserveExplicitHeader() {
        stubFor(get(urlEqualTo("/api/trace/preserve"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.preserveHeader("manual-req-id-777");
        assertEquals("ok", res);

        verify(getRequestedFor(urlEqualTo("/api/trace/preserve"))
                .withHeader("X-Request-ID", equalTo("manual-req-id-777")));
    }

    @Test
    @DisplayName("6. @Trace(forceNew = true) generates new ID replacing user-supplied header")
    void shouldForceNewTraceWhenRequested() {
        stubFor(get(urlEqualTo("/api/trace/force-new"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.forceNewTrace("manual-req-id-777");
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/force-new")));
        assertEquals(1, requests.size());
        String header = requests.get(0).getHeader("X-Request-ID");
        assertNotNull(header);
        assertFalse(header.equals("manual-req-id-777"));
        assertNotNull(UUID.fromString(header));
    }

    @Test
    @DisplayName("@Trace(w3c = true, forceNew = true) removes user-supplied traceparent and generates new W3C context")
    void shouldForceNewW3cTraceWhenRequested() {
        stubFor(get(urlEqualTo("/api/trace/force-new-w3c"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.forceNewW3cTrace("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertEquals("ok", res);

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/force-new-w3c")));
        assertEquals(1, requests.size());
        String header = requests.get(0).getHeader("traceparent");
        assertNotNull(header);
        assertFalse(header.equals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertTrue(header.startsWith("00-"));
    }

    @Test
    @DisplayName("7. Client builder correlationId(headerName) injects header into untraced interface")
    void shouldInjectClientLevelCorrelationId() {
        stubFor(get(urlEqualTo("/api/untraced/ping"))
                .willReturn(aResponse().withStatus(200).withBody("pong")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .correlationId("X-App-Trace-ID", () -> "app-12345")
                .build();
        try {
            UntracedApi api = customClient.get(UntracedApi.class);
            assertEquals("pong", api.ping());

            verify(getRequestedFor(urlEqualTo("/api/untraced/ping"))
                    .withHeader("X-App-Trace-ID", equalTo("app-12345")));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("8. Client builder correlationId(ThreadLocal) propagates context dynamically")
    void shouldPropagateFromThreadLocal() {
        stubFor(get(urlEqualTo("/api/untraced/ping"))
                .willReturn(aResponse().withStatus(200).withBody("pong")));

        ThreadLocal<String> holder = new ThreadLocal<>();
        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .correlationId(holder)
                .build();
        try {
            UntracedApi api = customClient.get(UntracedApi.class);

            // Inactive thread local -> generates UUID
            api.ping();
            List<LoggedRequest> reqs1 = findAll(getRequestedFor(urlEqualTo("/api/untraced/ping")));
            assertEquals(1, reqs1.size());
            assertNotNull(UUID.fromString(reqs1.get(0).getHeader("X-Request-ID")));

            // Active thread local -> uses value
            holder.set("active-thread-trace-888");
            api.ping();
            List<LoggedRequest> reqs2 = findAll(getRequestedFor(urlEqualTo("/api/untraced/ping")));
            assertEquals(2, reqs2.size());
            assertEquals("active-thread-trace-888", reqs2.get(1).getHeader("X-Request-ID"));
        } finally {
            holder.remove();
            customClient.close();
        }
    }

    @Test
    @DisplayName("9. Client builder w3cTracing() injects standard traceparent across calls")
    void shouldInjectW3cClientTracing() {
        stubFor(get(urlEqualTo("/api/untraced/ping"))
                .willReturn(aResponse().withStatus(200).withBody("pong")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .w3cTracing()
                .build();
        try {
            UntracedApi api = customClient.get(UntracedApi.class);
            assertEquals("pong", api.ping());

            List<LoggedRequest> reqs = findAll(getRequestedFor(urlEqualTo("/api/untraced/ping")));
            assertEquals(1, reqs.size());
            String traceparent = reqs.get(0).getHeader("traceparent");
            assertNotNull(traceparent);
            assertTrue(TraceContext.parse(traceparent).isPresent());
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("10. ResponseHeaders provides requestId(), traceparent() and traceContext() access")
    void shouldExtractMetadataFromResponseHeaders() {
        stubFor(get(urlEqualTo("/api/trace/default"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Request-ID", "server-echo-99")
                        .withHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .withHeader("tracestate", "congo=t61rcWkgMzE")
                        .withBody("ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        CapturedResponse<String> captured = ResponseHeaders.capture(api::defaultTrace);

        assertEquals("ok", captured.result());
        ResponseHeaders headers = captured.headers();
        assertEquals("server-echo-99", headers.requestId().orElse(null));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", headers.traceparent().orElse(null));

        TraceContext tc = headers.traceContext().orElse(null);
        assertNotNull(tc);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", tc.traceId());
        assertEquals("00f067aa0ba902b7", tc.spanId());
        assertEquals("congo=t61rcWkgMzE", tc.traceState());
        assertTrue(tc.isSampled());
    }

    @Test
    @DisplayName("12. @Trace(enabled = false) falls back to the client-level propagator (ADR-0042 §6.4)")
    void shouldFallBackToClientLevelPropagatorWhenTraceDisabled() {
        stubFor(get(urlEqualTo("/api/trace/disabled"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .correlationId("X-App-Trace-ID", () -> "app-12345")
                .build();
        try {
            InterfaceLevelTraceApi api = customClient.get(InterfaceLevelTraceApi.class);
            assertEquals("ok", api.disabledTrace());

            // The disabled annotation is inactive, so the client-level propagator applies
            verify(getRequestedFor(urlEqualTo("/api/trace/disabled"))
                    .withHeader("X-App-Trace-ID", equalTo("app-12345")));
            List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/disabled")));
            assertEquals(1, requests.size());
            // ...while the interface-level @Trace (X-Request-ID) stays disabled on this method
            assertFalse(requests.get(0).containsHeader("X-Request-ID"));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("13. @Trace(enabled = false) falls back to the client-level W3C propagator (ADR-0042 §6.4)")
    void shouldFallBackToClientLevelW3cWhenTraceDisabled() {
        stubFor(get(urlEqualTo("/api/trace/disabled"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .w3cTracing()
                .build();
        try {
            InterfaceLevelTraceApi api = customClient.get(InterfaceLevelTraceApi.class);
            assertEquals("ok", api.disabledTrace());

            List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/disabled")));
            assertEquals(1, requests.size());
            String traceparent = requests.get(0).getHeader("traceparent");
            assertNotNull(traceparent);
            assertTrue(TraceContext.parse(traceparent).isPresent());
            assertFalse(requests.get(0).containsHeader("X-Request-ID"));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("14. Active @Trace still overrides the client-level propagator (no double injection)")
    void shouldPreferActiveAnnotationOverClientLevelPropagator() {
        stubFor(get(urlEqualTo("/api/trace/default"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .correlationId("X-App-Trace-ID", () -> "app-12345")
                .build();
        try {
            InterfaceLevelTraceApi api = customClient.get(InterfaceLevelTraceApi.class);
            assertEquals("ok", api.defaultTrace());

            List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/trace/default")));
            assertEquals(1, requests.size());
            // The active interface-level @Trace wins
            assertNotNull(requests.get(0).getHeader("X-Request-ID"));
            // ...and the client-level propagator does not add a second ID
            assertFalse(requests.get(0).containsHeader("X-App-Trace-ID"));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("11. CompletableFuture async twin propagates @Trace header")
    void shouldPropagateTraceInAsyncCall() {
        stubFor(get(urlEqualTo("/api/trace/async"))
                .willReturn(aResponse().withStatus(200).withBody("async-ok")));

        InterfaceLevelTraceApi api = client.get(InterfaceLevelTraceApi.class);
        String res = api.asyncTrace().join();
        assertEquals("async-ok", res);

        verify(getRequestedFor(urlEqualTo("/api/trace/async"))
                .withHeader("X-Request-ID", matching("^[0-9a-fA-F\\-]{36}$")));
    }
}
