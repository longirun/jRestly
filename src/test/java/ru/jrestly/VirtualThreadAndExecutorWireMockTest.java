package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.ServerSentEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Virtual threads and custom executor integration tests (ADR-0026)")
class VirtualThreadAndExecutorWireMockTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":42,\"name\":\"Virtual Item\",\"description\":\"Loom enabled\"}";

    @Test
    @DisplayName("Async GET with useVirtualThreads() executes completions on Java 21 virtual threads")
    void asyncGetWithVirtualThreads() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        AtomicBoolean interceptorVirtual = new AtomicBoolean();
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .useVirtualThreads()
                .addResponseInterceptor(context -> {
                    interceptorVirtual.set(Thread.currentThread().isVirtual());
                })
                .build();

        try {
            TestController controller = client.get(TestController.class);

            AtomicBoolean thenApplyVirtual = new AtomicBoolean();
            CompletableFuture<TestDto> future = controller.getItemAsync(42L)
                    .thenApply(dto -> {
                        thenApplyVirtual.set(Thread.currentThread().isVirtual());
                        return dto;
                    });

            TestDto result = future.join();
            assertNotNull(result);
            assertEquals(42L, result.id());
            assertEquals("Virtual Item", result.name());

            assertTrue(thenApplyVirtual.get(), "thenApply stage should execute on a virtual thread");
            assertTrue(interceptorVirtual.get(), "ResponseInterceptor should execute on a virtual thread");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async GET with custom Executor executes completions on dedicated custom threads")
    void asyncGetWithCustomExecutor() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        ExecutorService customExecutor = Executors.newSingleThreadExecutor(r ->
                new Thread(r, "custom-http-worker-pool-1"));

        try {
            AtomicReference<String> threadNameRef = new AtomicReference<>();
            AtomicBoolean isVirtualRef = new AtomicBoolean(true);

            JRestlyClient client = JRestlyClient.builder()
                    .baseUrl(baseUrl())
                    .executor(customExecutor)
                    .addResponseInterceptor(context -> {
                        threadNameRef.set(Thread.currentThread().getName());
                        isVirtualRef.set(Thread.currentThread().isVirtual());
                    })
                    .build();

            try {
                TestController controller = client.get(TestController.class);

                TestDto result = controller.getItemAsync(42L).join();
                assertNotNull(result);
                assertEquals(42L, result.id());

                assertNotNull(threadNameRef.get());
                assertTrue(threadNameRef.get().startsWith("custom-http-worker-pool-"),
                        "Thread name should match custom executor: " + threadNameRef.get());
                assertFalse(isVirtualRef.get(), "Custom OS platform thread must not be virtual");
            } finally {
                client.close();
            }
        } finally {
            customExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Negative test: default client does not execute on virtual threads")
    void negativeTestDefaultClientNotVirtual() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        AtomicBoolean interceptorVirtual = new AtomicBoolean(true);
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .addResponseInterceptor(context -> {
                    interceptorVirtual.set(Thread.currentThread().isVirtual());
                })
                .build();

        try {
            TestController controller = client.get(TestController.class);
            TestDto result = controller.getItemAsync(42L).join();
            assertNotNull(result);

            assertFalse(interceptorVirtual.get(), "Default client must not use virtual threads by default");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async retry backoff with useVirtualThreads() schedules and completes on virtual threads")
    void asyncRetryWithVirtualThreads() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("vt-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("vt-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(30))
                .maxAttempts(3)
                .build();

        AtomicBoolean completionVirtual = new AtomicBoolean();
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .useVirtualThreads()
                .retry(retryPolicy)
                .build();

        try {
            TestController controller = client.get(TestController.class);

            TestDto dto = controller.getServerErrorAsync()
                    .thenApply(item -> {
                        completionVirtual.set(Thread.currentThread().isVirtual());
                        return item;
                    })
                    .join();

            assertNotNull(dto);
            assertEquals(42L, dto.id());
            assertTrue(completionVirtual.get(), "Async retry completion should run on a virtual thread");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async SSE stream with useVirtualThreads() reads event stream on virtual threads")
    void asyncSseStreamWithVirtualThreads() {
        String ssePayload = "event: message\ndata: hello virtual world\n\ndata: [DONE]\n\n";

        stubFor(get(urlEqualTo("/api/stream/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(ssePayload)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .useVirtualThreads()
                .build();

        try {
            TestController controller = client.get(TestController.class);

            CompletableFuture<Stream<ServerSentEvent>> future = controller.getEventsAsync();
            try (Stream<ServerSentEvent> stream = future.join()) {
                List<ServerSentEvent> events = stream.toList();
                assertEquals(2, events.size());
                assertEquals("hello virtual world", events.get(0).data());
                assertTrue(events.get(1).isDone());
            }
        } finally {
            client.close();
        }
    }
}
