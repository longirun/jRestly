package ru.jrestly.interceptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.http.CapturedResponse;
import ru.jrestly.http.Header;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCircuitExecutionTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    public record UserDto(long id, String name) {}

    public static class UserNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UserNotFoundException(String message) {
            super(message);
        }
    }

    public interface MockService {

        @Get(path = "/users/${id}")
        UserDto getUser(@PathVariable(name = "id") long id);

        @Get(path = "/users/${id}/optional")
        Optional<UserDto> getOptionalUser(@PathVariable(name = "id") long id);

        @Get(path = "/greeting")
        String getGreeting();

        @Get(path = "/binary")
        byte[] getBinary();

        @Get(path = "/users/${id}/strict")
        @OnError(statuses = {404}, errorObject = UserNotFoundException.class)
        UserDto getStrictUser(@PathVariable(name = "id") long id);

        @Get(path = "/users/${id}/async")
        CompletableFuture<UserDto> getUserAsync(@PathVariable(name = "id") long id);

        @Get(path = "/users/${id}/with-headers")
        UserDto getUserWithHeaders(@PathVariable(name = "id") long id, ResponseHeaders headers);

        @Post(path = "/users")
        UserDto createUser(@RequestBody UserDto user);
    }

    @Test
    @DisplayName("Short-circuit returns mock DTO without physical network calls")
    void shortCircuitReturnsMockDto() {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534") // Non-existent host/port
                .mock(WireResponse.of(200, "{\"id\":101,\"name\":\"Alice Mock\"}"))
                .build();

        MockService service = client.get(MockService.class);
        UserDto user = service.getUser(101);

        assertNotNull(user);
        assertEquals(101, user.id());
        assertEquals("Alice Mock", user.name());
    }

    @Test
    @DisplayName("Short-circuit returns mock String and byte[] payloads")
    void shortCircuitReturnsStringAndBytes() {
        byte[] expectedBytes = new byte[] {0x10, 0x20, 0x30, 0x40};
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(req -> req.uri().getPath().endsWith("/greeting"), WireResponse.of(200, "Greetings from Mock!"))
                .mock(req -> req.uri().getPath().endsWith("/binary"), WireResponse.of(200, expectedBytes))
                .build();

        MockService service = client.get(MockService.class);
        assertEquals("Greetings from Mock!", service.getGreeting());
        assertArrayEquals(expectedBytes, service.getBinary());
    }

    @Test
    @DisplayName("Short-circuit handles Optional<T> return types (200 OK vs 404 empty)")
    void shortCircuitHandlesOptionalReturns() {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(req -> req.uri().getPath().contains("/1/"), WireResponse.of(200, "{\"id\":1,\"name\":\"Found\"}"))
                .mock(req -> req.uri().getPath().contains("/999/"), WireResponse.of(404, "{\"error\":\"not found\"}"))
                .build();

        MockService service = client.get(MockService.class);

        Optional<UserDto> found = service.getOptionalUser(1);
        assertTrue(found.isPresent());
        assertEquals("Found", found.get().name());

        Optional<UserDto> notFound = service.getOptionalUser(999);
        assertTrue(notFound.isEmpty());
    }

    @Test
    @DisplayName("Short-circuit triggers @OnError exception mapping on synthetic error responses")
    void shortCircuitTriggersOnError() {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(WireResponse.of(404, "{\"message\":\"User 404 not found\"}"))
                .build();

        MockService service = client.get(MockService.class);
        assertThrows(UserNotFoundException.class, () -> service.getStrictUser(404));
    }

    @Test
    @DisplayName("Short-circuit supports asynchronous CompletableFuture invocations")
    void shortCircuitSupportsAsync() throws Exception {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(WireResponse.of(200, "{\"id\":42,\"name\":\"Async Alice\"}"))
                .build();

        MockService service = client.get(MockService.class);
        CompletableFuture<UserDto> future = service.getUserAsync(42);

        UserDto user = future.get();
        assertEquals(42, user.id());
        assertEquals("Async Alice", user.name());
    }

    @Test
    @DisplayName("Short-circuit populates ResponseHeaders in method signature and capture pattern")
    void shortCircuitPopulatesResponseHeaders() {
        WireResponse synthetic = WireResponse.builder()
                .statusCode(200)
                .header("X-Mock-Engine", "jRestly-v0.10.0")
                .header("X-RateLimit-Remaining", "9999")
                .body("{\"id\":77,\"name\":\"HeaderUser\"}")
                .build();

        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(synthetic)
                .build();

        MockService service = client.get(MockService.class);

        // 1. Signature parameter
        ResponseHeaders holder = new ResponseHeaders();
        UserDto user1 = service.getUserWithHeaders(77, holder);
        assertEquals("HeaderUser", user1.name());
        assertEquals(200, holder.statusCode());
        assertEquals("jRestly-v0.10.0", holder.firstValue("X-Mock-Engine").orElse(null));
        assertEquals(9999L, holder.firstLongValue("X-RateLimit-Remaining").orElse(0L));

        // 2. ResponseHeaders.capture pattern
        CapturedResponse<UserDto> captured = ResponseHeaders.capture(() -> service.getUser(77));
        assertEquals("HeaderUser", captured.result().name());
        assertEquals(200, captured.headers().statusCode());
        assertEquals("jRestly-v0.10.0", captured.headers().firstValue("X-Mock-Engine").orElse(null));
    }

    @Test
    @DisplayName("Short-circuit stops subsequent request interceptors from executing")
    void shortCircuitStopsSubsequentInterceptors() {
        AtomicBoolean secondInterceptorRan = new AtomicBoolean(false);

        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .addRequestInterceptor(ShortCircuitInterceptor.of(WireResponse.of(200, "{\"id\":1,\"name\":\"Early\"}")))
                .addRequestInterceptor((req, ctx) -> {
                    secondInterceptorRan.set(true);
                    return req;
                })
                .build();

        MockService service = client.get(MockService.class);
        UserDto user = service.getUser(1);

        assertEquals("Early", user.name());
        assertFalse(secondInterceptorRan.get(), "Subsequent request interceptors must not be invoked after short-circuit");
    }

    @Test
    @DisplayName("Short-circuit invokes ResponseInterceptor and ClientMetricsListener with attempts=0")
    void shortCircuitInvokesResponseInterceptorAndMetrics() {
        AtomicReference<WireResponse> observedResponse = new AtomicReference<>();
        AtomicInteger observedAttempts = new AtomicInteger(-1);
        AtomicInteger observedStatus = new AtomicInteger(-1);

        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(WireResponse.builder().statusCode(200).header("X-Synthetic", "true").body("{\"id\":99,\"name\":\"Observed\"}").build())
                .addResponseInterceptor(ctx -> observedResponse.set(ctx.response().orElse(null)))
                .metricsListener(metrics -> {
                    observedAttempts.set(metrics.attempts());
                    observedStatus.set(metrics.statusCode());
                })
                .build();

        MockService service = client.get(MockService.class);
        UserDto user = service.getUser(99);

        assertEquals("Observed", user.name());
        assertNotNull(observedResponse.get());
        assertEquals("true", observedResponse.get().header("X-Synthetic").orElse(null));
        assertEquals(0, observedAttempts.get(), "Short-circuited call executed no transmissions (ADR-0047)");
        assertEquals(200, observedStatus.get(), "Metrics status code should be 200");
    }

    @Test
    @DisplayName("Short-circuit metrics lifecycle: onCallStart x1, onAttemptComplete x0, onCallComplete x1 per call (sync + async)")
    void shortCircuitMetricsLifecycle() throws Exception {
        AtomicInteger callStarts = new AtomicInteger();
        AtomicInteger attemptCompletes = new AtomicInteger();
        AtomicInteger callCompletes = new AtomicInteger();
        AtomicInteger observedAttempts = new AtomicInteger(-1);
        AtomicBoolean observedRetried = new AtomicBoolean(true);

        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(WireResponse.of(200, "{\"id\":5,\"name\":\"Lifecycle\"}"))
                .metricsListener(new ClientMetricsListener() {
                    @Override
                    public void onCallComplete(CallMetrics metrics) {
                        callCompletes.incrementAndGet();
                        observedAttempts.set(metrics.attempts());
                        observedRetried.set(metrics.isRetried());
                    }

                    @Override
                    public void onCallStart(RequestContext context, WireRequest request) {
                        callStarts.incrementAndGet();
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
                        attemptCompletes.incrementAndGet();
                    }
                })
                .build();

        MockService service = client.get(MockService.class);
        assertEquals("Lifecycle", service.getUser(5).name());
        assertEquals("Lifecycle", service.getUserAsync(5).get().name());

        assertEquals(2, callStarts.get(), "onCallStart fires once per logical call");
        assertEquals(0, attemptCompletes.get(), "no wire transmission, hence no onAttemptComplete");
        assertEquals(2, callCompletes.get(), "onCallComplete fires once per logical call");
        assertEquals(0, observedAttempts.get(), "attempts() reports 0 for short-circuited calls (ADR-0047)");
        assertFalse(observedRetried.get(), "0 attempts is not a retry");
    }

    public static class DeclarativeMockInterceptor implements ShortCircuitInterceptor {
        @Override
        public Optional<WireResponse> shortCircuit(WireRequest request, RequestContext context) {
            return Optional.of(WireResponse.of(200, "{\"id\":888,\"name\":\"Declarative Mock\"}"));
        }
    }

    @WithInterceptor(DeclarativeMockInterceptor.class)
    public interface DeclarativeMockService {
        @Get(path = "/mocked")
        UserDto getMockedUser();
    }

    @Test
    @DisplayName("Declarative @WithInterceptor supports ShortCircuitInterceptor implementations")
    void declarativeWithInterceptorSupportsShortCircuit() {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .build();

        DeclarativeMockService service = client.get(DeclarativeMockService.class);
        UserDto user = service.getMockedUser();

        assertEquals(888, user.id());
        assertEquals("Declarative Mock", user.name());
    }

    @Test
    @DisplayName("Conditional mock routes requests based on path and method")
    void conditionalMockRouting() {
        client = JRestlyClient.builder()
                .baseUrl("http://127.0.0.1:65534")
                .mock(req -> req.uri().getPath().endsWith("/users/1"), WireResponse.of(200, "{\"id\":1,\"name\":\"Alice\"}"))
                .mock(req -> req.uri().getPath().endsWith("/users/2"), WireResponse.of(200, "{\"id\":2,\"name\":\"Bob\"}"))
                .mock(req -> req.method().name().equals("POST"), req -> WireResponse.of(201, "{\"id\":3,\"name\":\"Created\"}"))
                .build();

        MockService service = client.get(MockService.class);

        assertEquals("Alice", service.getUser(1).name());
        assertEquals("Bob", service.getUser(2).name());
        assertEquals("Created", service.createUser(new UserDto(0, "New")).name());
    }
}
