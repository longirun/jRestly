package ru.jrestly.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Retry;
import ru.jrestly.http.Header;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for the {@code HttpEngine} SPI: a custom engine participates in sync and async
 * calls, retries, streaming, metrics and interceptors.
 */
@DisplayName("Custom HttpEngine Integration Tests")
class CustomHttpEngineIntegrationTest {

    interface UserApi {
        @Get(path = "/users/${id}")
        UserDto getUser(@PathVariable(name = "id") long id);

        @Get(path = "/users/${id}/async")
        CompletableFuture<UserDto> getUserAsync(@PathVariable(name = "id") long id);

        @Get(path = "/flaky")
        @Retry(times = 3, initialBackoffMs = 10)
        String getFlaky();
    }

    record UserDto(long id, String name) {}

    @Test
    @DisplayName("Custom HttpEngine executes synchronous requests and populates DTO via JSON codec")
    void customEngineExecutesSync() {
        AtomicInteger sendCount = new AtomicInteger();
        List<WireRequest> capturedRequests = new ArrayList<>();

        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                sendCount.incrementAndGet();
                capturedRequests.add(request);
                return WireResponse.of(200, List.of(new Header("Content-Type", "application/json")), "{\"id\":42,\"name\":\"Alice\"}");
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .build();

        assertSame(customEngine, client.getHttpEngine());

        UserApi api = client.get(UserApi.class);
        UserDto user = api.getUser(42L);

        assertNotNull(user);
        assertEquals(42L, user.id());
        assertEquals("Alice", user.name());
        assertEquals(1, sendCount.get());
        assertEquals("https://mock.example.com/users/42", capturedRequests.get(0).uri().toString());

        client.close();
    }

    @Test
    @DisplayName("Custom HttpEngine executes asynchronous requests via sendAsync")
    void customEngineExecutesAsync() {
        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                WireResponse response = WireResponse.of(200, List.of(new Header("Content-Type", "application/json")), "{\"id\":99,\"name\":\"Bob\"}");
                return CompletableFuture.completedFuture(response);
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .build();

        UserApi api = client.get(UserApi.class);
        UserDto user = api.getUserAsync(99L).join();

        assertNotNull(user);
        assertEquals(99L, user.id());
        assertEquals("Bob", user.name());

        client.close();
    }

    @Test
    @DisplayName("Transparent decompression works seamlessly on top of custom HttpEngine")
    void transparentDecompressionOnCustomEngine() throws Exception {
        byte[] jsonBytes = "{\"id\":777,\"name\":\"GzipUser\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(jsonBytes);
        }
        byte[] gzippedPayload = baos.toByteArray();

        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                return WireResponse.of(200, List.of(
                        new Header("Content-Type", "application/json"),
                        new Header("Content-Encoding", "gzip")
                ), gzippedPayload);
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .build();

        UserApi api = client.get(UserApi.class);
        UserDto user = api.getUser(777L);

        assertNotNull(user);
        assertEquals(777L, user.id());
        assertEquals("GzipUser", user.name());

        client.close();
    }

    @Test
    @DisplayName("Retry policy operates correctly across multiple custom HttpEngine attempts")
    void retryPolicyWorksOverCustomEngine() {
        AtomicInteger attempts = new AtomicInteger();

        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                int count = attempts.incrementAndGet();
                if (count < 3) {
                    return WireResponse.of(503, "temporarily unavailable");
                }
                return WireResponse.of(200, "success on 3rd attempt");
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .build();

        UserApi api = client.get(UserApi.class);
        String result = api.getFlaky();

        assertEquals("success on 3rd attempt", result);
        assertEquals(3, attempts.get());

        client.close();
    }

    @Test
    @DisplayName("RequestInterceptor and ClientMetricsListener integrate with custom HttpEngine")
    void interceptorsAndMetricsWithCustomEngine() {
        List<String> events = new ArrayList<>();

        RequestInterceptor interceptor = (req, ctx) -> {
            events.add("intercept:" + req.method());
            return req.withHeader("X-Intercepted", "true");
        };

        ClientMetricsListener listener = new ClientMetricsListener() {
            @Override
            public void onCallStart(ru.jrestly.interceptor.RequestContext context, WireRequest request) {
                events.add("callStart");
            }

            @Override
            public void onCallComplete(ru.jrestly.metrics.CallMetrics metrics) {
                events.add("callComplete:" + metrics.attempts());
            }
        };

        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                events.add("engineSend");
                return WireResponse.of(200, List.of(new Header("Content-Type", "application/json")), "{\"id\":1,\"name\":\"Intercepted\"}");
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .addRequestInterceptor(interceptor)
                .metricsListener(listener)
                .build();

        UserApi api = client.get(UserApi.class);
        UserDto user = api.getUser(1L);

        assertEquals(1L, user.id());
        assertEquals(List.of("intercept:GET", "callStart", "engineSend", "callComplete:1"), events);

        client.close();
    }

    @Test
    @DisplayName("client.close() invokes HttpEngine.close()")
    void clientCloseClosesEngine() {
        AtomicBoolean closed = new AtomicBoolean(false);

        HttpEngine customEngine = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                return WireResponse.of(200);
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                return CompletableFuture.completedFuture(WireResponse.of(200));
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(customEngine)
                .build();

        assertFalse(closed.get());
        client.close();
        assertTrue(closed.get());
    }
}
