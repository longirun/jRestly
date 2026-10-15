package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.http.Header;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativeInterceptorWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient httpClient;

    public record ItemDto(long id, String name) {}

    // Interceptors for tests
    public static class InterfaceTagInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Interface-Tag", "applied");
        }
    }

    public static class MethodTagInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Method-Tag", "applied");
        }
    }

    public static class TraceSuperInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            String prev = request.headers().stream()
                    .filter(h -> "X-Trace-Order".equalsIgnoreCase(h.name()))
                    .map(Header::value)
                    .reduce((a, b) -> b)
                    .orElse("");
            return request.withoutHeader("X-Trace-Order")
                    .withHeader("X-Trace-Order", prev.isEmpty() ? "super" : prev + ",super");
        }
    }

    public static class TraceSubInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            String prev = request.headers().stream()
                    .filter(h -> "X-Trace-Order".equalsIgnoreCase(h.name()))
                    .map(Header::value)
                    .reduce((a, b) -> b)
                    .orElse("");
            return request.withoutHeader("X-Trace-Order")
                    .withHeader("X-Trace-Order", prev.isEmpty() ? "sub" : prev + ",sub");
        }
    }

    public static class TraceMethodInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            String prev = request.headers().stream()
                    .filter(h -> "X-Trace-Order".equalsIgnoreCase(h.name()))
                    .map(Header::value)
                    .reduce((a, b) -> b)
                    .orElse("");
            return request.withoutHeader("X-Trace-Order")
                    .withHeader("X-Trace-Order", prev.isEmpty() ? "method" : prev + ",method");
        }
    }

    public static class ArrayHeaderAInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Array-A", "1");
        }
    }

    public static class ArrayHeaderBInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Array-B", "2");
        }
    }

    public static class RepeatableHeaderAInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Repeat-A", "alpha");
        }
    }

    public static class RepeatableHeaderBInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Repeat-B", "beta");
        }
    }

    public static final List<String> AUDIT_LOGS = new CopyOnWriteArrayList<>();

    public static class AuditDualInterceptor implements RequestInterceptor, ResponseInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            context.setAttribute("audit.id", "trace-999");
            return request.withHeader("X-Audit-Id", "trace-999");
        }

        @Override
        public void onResponse(ResponseContext context) {
            String auditId = context.getAttribute("audit.id", String.class).orElse("none");
            AUDIT_LOGS.add("id=" + auditId + ",status=" + context.statusCode() + ",success=" + context.isSuccess());
        }
    }

    public static final List<Integer> OBSERVED_STATUSES = new CopyOnWriteArrayList<>();

    public static class MetricResponseInterceptor implements ResponseInterceptor {
        @Override
        public void onResponse(ResponseContext context) {
            OBSERVED_STATUSES.add(context.statusCode());
        }
    }

    public static class SequenceCounterInterceptor implements RequestInterceptor {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            int seq = counter.incrementAndGet();
            return request.withHeader("X-Seq", String.valueOf(seq));
        }
    }

    // Controller interfaces
    @WithInterceptor(InterfaceTagInterceptor.class)
    public interface InterfaceLevelApi {
        @Get(path = "/first")
        ItemDto getFirst();

        @Get(path = "/second")
        ItemDto getSecond();
    }

    public interface MethodLevelApi {
        @WithInterceptor(MethodTagInterceptor.class)
        @Get(path = "/tagged")
        ItemDto tagged();

        @Get(path = "/untagged")
        ItemDto untagged();
    }

    @WithInterceptor(TraceSuperInterceptor.class)
    public interface SuperTraceApi {
        @Get(path = "/trace")
        ItemDto trace();
    }

    @WithInterceptor(TraceSubInterceptor.class)
    public interface SubTraceApi extends SuperTraceApi {
        @WithInterceptor(TraceMethodInterceptor.class)
        @Get(path = "/trace")
        ItemDto trace();
    }

    public interface MultiArrayApi {
        @WithInterceptor({ArrayHeaderAInterceptor.class, ArrayHeaderBInterceptor.class})
        @Get(path = "/multi")
        ItemDto multi();
    }

    public interface RepeatableApi {
        @WithInterceptor(RepeatableHeaderAInterceptor.class)
        @WithInterceptor(RepeatableHeaderBInterceptor.class)
        @Get(path = "/repeatable")
        ItemDto repeatable();
    }

    public interface AuditApi {
        @WithInterceptor(AuditDualInterceptor.class)
        @Get(path = "/audit")
        ItemDto callAudit();
    }

    public interface MetricApi {
        @WithInterceptor(MetricResponseInterceptor.class)
        @Get(path = "/metric")
        ItemDto callMetric();
    }

    public interface AsyncStreamApi {
        @WithInterceptor(MethodTagInterceptor.class)
        @WithInterceptor(MetricResponseInterceptor.class)
        @Get(path = "/async")
        CompletableFuture<ItemDto> getAsync();

        @WithInterceptor(MethodTagInterceptor.class)
        @Get(path = "/stream")
        InputStream getStream();
    }

    public interface StatefulApi {
        @WithInterceptor(SequenceCounterInterceptor.class)
        @Get(path = "/seq")
        ItemDto nextSeq();
    }

    @BeforeEach
    void setUp() {
        AUDIT_LOGS.clear();
        OBSERVED_STATUSES.clear();
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        httpClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("Interface-level @WithInterceptor applies to all methods in interface")
    void interfaceLevelInterceptorAppliesToAllMethods() {
        wireMock.stubFor(get(urlEqualTo("/first"))
                .withHeader("X-Interface-Tag", equalTo("applied"))
                .willReturn(okJson("{\"id\":1,\"name\":\"Item 1\"}")));

        wireMock.stubFor(get(urlEqualTo("/second"))
                .withHeader("X-Interface-Tag", equalTo("applied"))
                .willReturn(okJson("{\"id\":2,\"name\":\"Item 2\"}")));

        InterfaceLevelApi api = httpClient.get(InterfaceLevelApi.class);
        ItemDto item1 = api.getFirst();
        ItemDto item2 = api.getSecond();

        assertEquals(1L, item1.id());
        assertEquals(2L, item2.id());

        wireMock.verify(getRequestedFor(urlEqualTo("/first")).withHeader("X-Interface-Tag", equalTo("applied")));
        wireMock.verify(getRequestedFor(urlEqualTo("/second")).withHeader("X-Interface-Tag", equalTo("applied")));
    }

    @Test
    @DisplayName("Method-level @WithInterceptor applies only to targeted method")
    void methodLevelInterceptorAppliesOnlyToTargetedMethod() {
        wireMock.stubFor(get(urlEqualTo("/tagged"))
                .withHeader("X-Method-Tag", equalTo("applied"))
                .willReturn(okJson("{\"id\":10,\"name\":\"Tagged\"}")));

        wireMock.stubFor(get(urlEqualTo("/untagged"))
                .willReturn(okJson("{\"id\":20,\"name\":\"Untagged\"}")));

        MethodLevelApi api = httpClient.get(MethodLevelApi.class);
        ItemDto tagged = api.tagged();
        ItemDto untagged = api.untagged();

        assertEquals(10L, tagged.id());
        assertEquals(20L, untagged.id());

        wireMock.verify(getRequestedFor(urlEqualTo("/tagged")).withHeader("X-Method-Tag", equalTo("applied")));
        wireMock.verify(getRequestedFor(urlEqualTo("/untagged")).withoutHeader("X-Method-Tag"));
    }

    @Test
    @DisplayName("Ordering: Global -> Super-interface -> Sub-interface -> Method")
    void orderingHierarchyAcrossGlobalAndDeclarative() {
        wireMock.stubFor(get(urlEqualTo("/trace"))
                .withHeader("X-Trace-Order", equalTo("global,super,sub,method"))
                .willReturn(okJson("{\"id\":42,\"name\":\"Traced\"}")));

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .addRequestInterceptor((req, ctx) -> req.withHeader("X-Trace-Order", "global"))
                .build();

        try {
            SubTraceApi api = customClient.get(SubTraceApi.class);
            ItemDto result = api.trace();
            assertEquals(42L, result.id());
            wireMock.verify(getRequestedFor(urlEqualTo("/trace"))
                    .withHeader("X-Trace-Order", equalTo("global,super,sub,method")));
        } finally {
            customClient.close();
        }
    }

    @Test
    @DisplayName("Multi-class array @WithInterceptor executes classes in declared order")
    void multiClassArrayExecution() {
        wireMock.stubFor(get(urlEqualTo("/multi"))
                .withHeader("X-Array-A", equalTo("1"))
                .withHeader("X-Array-B", equalTo("2"))
                .willReturn(okJson("{\"id\":3,\"name\":\"Multi\"}")));

        MultiArrayApi api = httpClient.get(MultiArrayApi.class);
        ItemDto result = api.multi();

        assertEquals(3L, result.id());
        wireMock.verify(getRequestedFor(urlEqualTo("/multi"))
                .withHeader("X-Array-A", equalTo("1"))
                .withHeader("X-Array-B", equalTo("2")));
    }

    @Test
    @DisplayName("Repeatable @WithInterceptor executes repeated annotations in declared order")
    void repeatableAnnotationExecution() {
        wireMock.stubFor(get(urlEqualTo("/repeatable"))
                .withHeader("X-Repeat-A", equalTo("alpha"))
                .withHeader("X-Repeat-B", equalTo("beta"))
                .willReturn(okJson("{\"id\":4,\"name\":\"Repeat\"}")));

        RepeatableApi api = httpClient.get(RepeatableApi.class);
        ItemDto result = api.repeatable();

        assertEquals(4L, result.id());
        wireMock.verify(getRequestedFor(urlEqualTo("/repeatable"))
                .withHeader("X-Repeat-A", equalTo("alpha"))
                .withHeader("X-Repeat-B", equalTo("beta")));
    }

    @Test
    @DisplayName("Dual Request and Response interceptor decorates request and observes response")
    void dualInterceptorDecoratesAndObserves() {
        wireMock.stubFor(get(urlEqualTo("/audit"))
                .withHeader("X-Audit-Id", equalTo("trace-999"))
                .willReturn(okJson("{\"id\":5,\"name\":\"Audit\"}")));

        AuditApi api = httpClient.get(AuditApi.class);
        ItemDto result = api.callAudit();

        assertEquals(5L, result.id());
        assertEquals(1, AUDIT_LOGS.size());
        assertEquals("id=trace-999,status=200,success=true", AUDIT_LOGS.get(0));
    }

    @Test
    @DisplayName("ResponseInterceptor observes status code")
    void responseInterceptorObservesStatusCode() {
        wireMock.stubFor(get(urlEqualTo("/metric"))
                .willReturn(okJson("{\"id\":6,\"name\":\"Metric\"}")));

        MetricApi api = httpClient.get(MetricApi.class);
        ItemDto result = api.callMetric();

        assertEquals(6L, result.id());
        assertEquals(List.of(200), OBSERVED_STATUSES);
    }

    @Test
    @DisplayName("Async CompletableFuture twin executes declarative interceptors")
    void asyncTwinExecutesDeclarativeInterceptors() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/async"))
                .withHeader("X-Method-Tag", equalTo("applied"))
                .willReturn(okJson("{\"id\":7,\"name\":\"Async\"}")));

        AsyncStreamApi api = httpClient.get(AsyncStreamApi.class);
        CompletableFuture<ItemDto> future = api.getAsync();
        ItemDto result = future.get();

        assertEquals(7L, result.id());
        assertEquals(List.of(200), OBSERVED_STATUSES);
        wireMock.verify(getRequestedFor(urlEqualTo("/async")).withHeader("X-Method-Tag", equalTo("applied")));
    }

    @Test
    @DisplayName("Streaming InputStream executes declarative interceptor")
    void streamingInputStreamExecutesDeclarativeInterceptor() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/stream"))
                .withHeader("X-Method-Tag", equalTo("applied"))
                .willReturn(ok("binary-payload-data")));

        AsyncStreamApi api = httpClient.get(AsyncStreamApi.class);
        try (InputStream stream = api.getStream()) {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("binary-payload-data", content);
        }
        wireMock.verify(getRequestedFor(urlEqualTo("/stream")).withHeader("X-Method-Tag", equalTo("applied")));
    }

    @Test
    @DisplayName("Stateful interceptor singleton reuses instance across calls (Hot-Path Zero Allocation)")
    void statefulInterceptorReusedAcrossCalls() {
        wireMock.stubFor(get(urlEqualTo("/seq"))
                .willReturn(okJson("{\"id\":8,\"name\":\"Seq\"}")));

        StatefulApi api = httpClient.get(StatefulApi.class);
        api.nextSeq();
        api.nextSeq();
        api.nextSeq();

        wireMock.verify(getRequestedFor(urlEqualTo("/seq")).withHeader("X-Seq", equalTo("1")));
        wireMock.verify(getRequestedFor(urlEqualTo("/seq")).withHeader("X-Seq", equalTo("2")));
        wireMock.verify(getRequestedFor(urlEqualTo("/seq")).withHeader("X-Seq", equalTo("3")));
    }
}
