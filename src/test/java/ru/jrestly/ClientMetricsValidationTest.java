package ru.jrestly;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.UnexpectedStatusException;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.metrics.SimpleCallMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metrics SPI configuration and validation (ADR-0036 / ADR-0047): the default
 * {@code metricsListener} is null, builders reject null, {@code composite} flattens
 * nested listeners, {@code SimpleCallMetrics} enforces its invariants, and
 * {@code ControllerValidator} rejects malformed {@code @WithMetrics}. No network.
 */
class ClientMetricsValidationTest {

    public static class ValidTestMetricsListener implements ClientMetricsListener {
        public ValidTestMetricsListener() {
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
        }
    }

    public static abstract class AbstractMetricsListener implements ClientMetricsListener {
        public AbstractMetricsListener() {
        }
    }

    public static class NoDefaultConstructorListener implements ClientMetricsListener {
        public NoDefaultConstructorListener(String ignored) {
        }

        @Override
        public void onCallComplete(CallMetrics metrics) {
        }
    }

    public static class NonListenerClass {
        public NonListenerClass() {
        }
    }

    @WithMetrics(ValidTestMetricsListener.class)
    interface ValidController {
        @Get(path = "/api/test")
        String test();
    }

    @WithMetrics(AbstractMetricsListener.class)
    interface AbstractListenerController {
        @Get(path = "/api/test")
        String test();
    }

    interface MethodInvalidListenerController {
        @Get(path = "/api/test")
        @WithMetrics(NoDefaultConstructorListener.class)
        String test();
    }

    @WithMetrics(NonListenerClass.class)
    interface NonListenerController {
        @Get(path = "/api/test")
        String test();
    }

    @Test
    @DisplayName("ModuleInfo and SimpleModuleInfo default metricsListener is null")
    void testDefaults() {
        ModuleInfo defaultInfo = new ModuleInfo() {
            @Override
            public String getName() {
                return "test";
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> Environment<T> getEnvironment() {
                return null;
            }

            @Override
            public AuthProvider getAuthProvider() {
                return null;
            }
        };
        assertNull(defaultInfo.getMetricsListener());

        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        assertNull(info.getMetricsListener());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder metricsListener registers and enforces non-null")
    void testSimpleModuleInfoBuilderMetricsListener() {
        assertThrows(NullPointerException.class, () ->
                SimpleModuleInfo.builder().baseUrl("http://localhost:8080").metricsListener(null)
        );

        ClientMetricsListener listener = metrics -> {
        };
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .metricsListener(listener)
                .build();
        assertEquals(listener, info.getMetricsListener());
    }

    @Test
    @DisplayName("JRestlyClient.Builder metricsListener enforces non-null")
    void testJRestlyClientBuilderMetricsListener() {
        assertThrows(NullPointerException.class, () ->
                JRestlyClient.builder().baseUrl("http://localhost:8080").metricsListener(null)
        );
    }

    @Test
    @DisplayName("ClientMetricsListener.composite merges multiple listeners and flattens composites")
    void testCompositeListeners() throws NoSuchMethodException {
        assertThrows(NullPointerException.class, () -> ClientMetricsListener.composite((ClientMetricsListener[]) null));
        assertThrows(NullPointerException.class, () -> ClientMetricsListener.composite(new ClientMetricsListener[]{null}));

        int[] called = new int[]{0, 0, 0};
        ClientMetricsListener l1 = metrics -> called[0]++;
        ClientMetricsListener l2 = metrics -> called[1]++;
        ClientMetricsListener l3 = metrics -> called[2]++;

        ClientMetricsListener composite1 = ClientMetricsListener.composite(l1, l2);
        ClientMetricsListener combined = ClientMetricsListener.composite(composite1, l3);

        java.lang.reflect.Method testMethod = ValidController.class.getMethod("test");
        RequestContext context = new SimpleRequestContext("test-mod", ValidController.class, testMethod, new Object[0]);
        WireRequest wireReq = new WireRequest(HttpMethod.GET, URI.create("http://localhost/api"), List.of(), null, null, null, null);
        CallMetrics dummyMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.empty(),
                Optional.empty(),
                Duration.ofMillis(10),
                1,
                -1,
                -1
        );

        combined.onCallComplete(dummyMetrics);
        assertEquals(1, called[0]);
        assertEquals(1, called[1]);
        assertEquals(1, called[2]);

        // Verify onCallStart, onRetry, and onAttemptComplete delegation
        int[] lifecycleEvents = new int[]{0, 0, 0};
        ClientMetricsListener detailed = new ClientMetricsListener() {
            @Override
            public void onCallComplete(CallMetrics metrics) {}

            @Override
            public void onCallStart(RequestContext ctx, WireRequest req) {
                lifecycleEvents[0]++;
            }

            @Override
            public void onRetry(RequestContext ctx, WireRequest req, int attempt, Throwable reason, Duration delay) {
                lifecycleEvents[1]++;
            }

            @Override
            public void onAttemptComplete(RequestContext ctx, WireRequest req, int attempt,
                                          Optional<WireResponse> resp, Optional<Throwable> fail, Duration dur) {
                lifecycleEvents[2]++;
            }
        };

        // Two listeners ensure CompositeClientMetricsListener instance is created (size >= 2)
        ClientMetricsListener compositeLifecycle = ClientMetricsListener.composite(detailed, detailed);
        compositeLifecycle.onCallStart(context, wireReq);
        compositeLifecycle.onRetry(context, wireReq, 2, new IOException("retry-test"), Duration.ofMillis(50));
        compositeLifecycle.onAttemptComplete(context, wireReq, 1, Optional.empty(), Optional.empty(), Duration.ofMillis(20));

        assertEquals(2, lifecycleEvents[0]);
        assertEquals(2, lifecycleEvents[1]);
        assertEquals(2, lifecycleEvents[2]);

        // Empty composite listener onCallComplete is a safe no-op
        ClientMetricsListener emptyComposite = ClientMetricsListener.composite();
        emptyComposite.onCallComplete(dummyMetrics);

        // Default interface onRetry is a no-op
        ClientMetricsListener defaultListener = metrics -> {};
        defaultListener.onRetry(context, wireReq, 1, new IOException("default-retry"), Duration.ZERO);
    }

    @Test
    @DisplayName("SimpleCallMetrics constructor validates non-null invariants")
    void testSimpleCallMetricsValidation() throws NoSuchMethodException {
        java.lang.reflect.Method testMethod = ValidController.class.getMethod("test");
        RequestContext context = new SimpleRequestContext("test-mod", ValidController.class, testMethod, new Object[0]);
        WireRequest wireReq = new WireRequest(HttpMethod.GET, URI.create("http://localhost/api"), List.of(), null, null, null, null);

        assertThrows(NullPointerException.class, () -> new SimpleCallMetrics(
                null, wireReq, Optional.empty(), Optional.empty(), Duration.ofMillis(10), 1, 0, 0
        ));
        assertThrows(NullPointerException.class, () -> new SimpleCallMetrics(
                context, null, Optional.empty(), Optional.empty(), Duration.ofMillis(10), 1, 0, 0
        ));
        assertThrows(NullPointerException.class, () -> new SimpleCallMetrics(
                context, wireReq, null, Optional.empty(), Duration.ofMillis(10), 1, 0, 0
        ));
        assertThrows(NullPointerException.class, () -> new SimpleCallMetrics(
                context, wireReq, Optional.empty(), null, Duration.ofMillis(10), 1, 0, 0
        ));
        assertThrows(NullPointerException.class, () -> new SimpleCallMetrics(
                context, wireReq, Optional.empty(), Optional.empty(), null, 1, 0, 0
        ));
        // ADR-0036 Amendment #195: a short-circuited call (ADR-0047)
        // executed no transmissions and reports attempts() == 0
        CallMetrics shortCircuited = new SimpleCallMetrics(
                context, wireReq, Optional.empty(), Optional.empty(), Duration.ofMillis(10), 0, 0, 0
        );
        assertEquals(0, shortCircuited.attempts());
        assertThrows(IllegalArgumentException.class, () -> new SimpleCallMetrics(
                context, wireReq, Optional.empty(), Optional.empty(), Duration.ofMillis(10), -1, 0, 0
        ));
    }

    @Test
    @DisplayName("CallMetrics helper methods report status, success, and retried state")
    void testCallMetricsHelpers() throws NoSuchMethodException {
        java.lang.reflect.Method testMethod = ValidController.class.getMethod("test");
        RequestContext context = new SimpleRequestContext("test-mod", ValidController.class, testMethod, new Object[0]);
        WireRequest wireReq = new WireRequest(HttpMethod.POST, URI.create("http://localhost/api"), List.of(), null, new byte[16], null, null);
        WireResponse resp200 = new WireResponse(200, List.of(), new byte[32]);

        CallMetrics successMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.of(resp200),
                Optional.empty(),
                Duration.ofMillis(15),
                1,
                16,
                32
        );
        assertEquals(200, successMetrics.statusCode());
        assertTrue(successMetrics.isSuccess());
        assertFalse(successMetrics.isRetried());
        assertEquals(HttpMethod.POST, successMetrics.method());
        assertEquals(URI.create("http://localhost/api"), successMetrics.uri());

        CallMetrics retriedMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.of(resp200),
                Optional.empty(),
                Duration.ofMillis(150),
                3,
                16,
                32
        );
        assertTrue(retriedMetrics.isRetried());

        Throwable ex = new UnexpectedStatusException(500, "error", List.of());
        CallMetrics failedMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.empty(),
                Optional.of(ex),
                Duration.ofMillis(20),
                1,
                16,
                -1
        );
        assertEquals(500, failedMetrics.statusCode());
        assertFalse(failedMetrics.isSuccess());

        Throwable transportError = new IOException("connection refused");
        CallMetrics transportFailedMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.empty(),
                Optional.of(transportError),
                Duration.ofMillis(5),
                1,
                -1,
                -1
        );
        assertEquals(-1, transportFailedMetrics.statusCode());
        assertFalse(transportFailedMetrics.isSuccess());

        Throwable wrappedHandled = new RuntimeException("wrap", new UnexpectedStatusException(503, "unavailable", List.of()));
        CallMetrics causeMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.empty(),
                Optional.of(wrappedHandled),
                Duration.ofMillis(10),
                1,
                0,
                0
        );
        assertEquals(503, causeMetrics.statusCode());
        assertFalse(causeMetrics.isSuccess());

        WireResponse resp404 = WireResponse.builder().statusCode(404).build();
        CallMetrics notFoundMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.of(resp404),
                Optional.empty(),
                Duration.ofMillis(10),
                1,
                0,
                0
        );
        assertEquals(404, notFoundMetrics.statusCode());
        assertFalse(notFoundMetrics.isSuccess());

        CallMetrics emptyMetrics = new SimpleCallMetrics(
                context,
                wireReq,
                Optional.empty(),
                Optional.empty(),
                Duration.ofMillis(10),
                1,
                0,
                0
        );
        assertEquals(-1, emptyMetrics.statusCode());
        assertFalse(emptyMetrics.isSuccess());
    }

    @Test
    @DisplayName("ControllerValidator verifies @WithMetrics constraints upfront")
    void testControllerValidatorWithMetrics() {
        List<String> validProblems = ControllerValidator.validate(ValidController.class);
        assertTrue(validProblems.isEmpty(), "ValidController should have no problems: " + validProblems);

        List<String> abstractProblems = ControllerValidator.validate(AbstractListenerController.class);
        assertFalse(abstractProblems.isEmpty());
        assertTrue(abstractProblems.getFirst().contains("must not be abstract or an interface"));

        List<String> noCtorProblems = ControllerValidator.validate(MethodInvalidListenerController.class);
        assertFalse(noCtorProblems.isEmpty());
        assertTrue(noCtorProblems.getFirst().contains("must declare a no-arg constructor"));

        List<String> nonListenerProblems = ControllerValidator.validate(NonListenerController.class);
        assertFalse(nonListenerProblems.isEmpty());
        assertTrue(nonListenerProblems.getFirst().contains("must implement ClientMetricsListener"));
    }

    @Test
    @DisplayName("JRestlyClient successfully creates proxy for controller with valid @WithMetrics")
    void testClientCreationWithMetricsAnnotation() {
        ValidController proxy = (ValidController) JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .metricsListener(metrics -> {
                })
                .build()
                .createController(ValidController.class);
        assertNotNull(proxy);
    }
}
