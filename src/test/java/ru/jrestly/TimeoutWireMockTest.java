package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.fixtures.TestDto;

import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-method and interface-level timeout enforcement (ADR-0027): method-level
 * {@code @Timeout} beats interface level, budgets succeed when met and throw
 * {@code HttpTimeoutException} when exceeded (sync, async and streaming), and a
 * retry policy can recover from a transport timeout. WireMock e2e.
 */
@DisplayName("Per-method and interface timeout WireMock integration tests (ADR-0027)")
class TimeoutWireMockTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":42,\"name\":\"Timeout Item\",\"description\":\"Per-method test\"}";

    interface TimeoutClientApi {

        @Timeout(100)
        @Get(path = "/api/fast-timeout")
        TestDto fastTimeout();

        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        @Get(path = "/api/slow-allowed")
        TestDto slowAllowed();

        @Timeout(100)
        @Get(path = "/api/async-timeout")
        CompletableFuture<TestDto> asyncTimeout();

        @Timeout(100)
        @Retry(times = 2, initialBackoffMs = 50, maxBackoffMs = 100, transport = true)
        @Get(path = "/api/retry-timeout")
        TestDto retryTimeout();

        @Timeout(1500)
        @Get(path = "/api/stream-timeout")
        InputStream streamTimeout();
    }

    @Timeout(100)
    interface InterfaceTimeoutApi {

        @Get(path = "/api/iface-inherited")
        TestDto inherited();

        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        @Get(path = "/api/iface-override")
        TestDto overridden();
    }

    @Test
    @DisplayName("Method with @Timeout(100) throws HttpTimeoutException when server delays 500ms")
    void timeoutMethodThrowsHttpTimeoutException() {
        stubFor(get(urlEqualTo("/api/fast-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)
                        .withFixedDelay(500)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .socketTimeout(10_000) // global client timeout is large, per-method @Timeout(100) should kick in
                .build();

        try {
            TimeoutClientApi api = client.get(TimeoutClientApi.class);

            RuntimeException ex = assertThrows(RuntimeException.class, api::fastTimeout);
            assertInstanceOf(HttpTimeoutException.class, ex.getCause());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Method with @Timeout(2s) succeeds within budget when server delays 100ms")
    void timeoutMethodSucceedsWithinBudget() {
        stubFor(get(urlEqualTo("/api/slow-allowed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)
                        .withFixedDelay(100)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .socketTimeout(100) // global client timeout is small (100ms), per-method @Timeout(2s) overrides it
                .build();

        try {
            TimeoutClientApi api = client.get(TimeoutClientApi.class);
            TestDto dto = api.slowAllowed();

            assertNotNull(dto);
            assertEquals(42L, dto.id());
            assertEquals("Timeout Item", dto.name());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Interface-level @Timeout(100) applies to all methods without explicit annotation")
    void interfaceLevelTimeoutAppliesToAllMethods() {
        stubFor(get(urlEqualTo("/api/iface-inherited"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)
                        .withFixedDelay(500)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .socketTimeout(10_000)
                .build();

        try {
            InterfaceTimeoutApi api = client.get(InterfaceTimeoutApi.class);

            RuntimeException ex = assertThrows(RuntimeException.class, api::inherited);
            assertInstanceOf(HttpTimeoutException.class, ex.getCause());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Method-level @Timeout overrides interface-level @Timeout")
    void methodLevelTimeoutOverridesInterfaceLevelTimeout() {
        stubFor(get(urlEqualTo("/api/iface-override"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)
                        .withFixedDelay(300)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .build();

        try {
            InterfaceTimeoutApi api = client.get(InterfaceTimeoutApi.class);
            TestDto dto = api.overridden();

            assertNotNull(dto);
            assertEquals(42L, dto.id());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async method with @Timeout(100) completes exceptionally with HttpTimeoutException")
    void asyncMethodTimeoutCompletesExceptionally() {
        stubFor(get(urlEqualTo("/api/async-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)
                        .withFixedDelay(500)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .socketTimeout(10_000)
                .build();

        try {
            TimeoutClientApi api = client.get(TimeoutClientApi.class);
            CompletableFuture<TestDto> future = api.asyncTimeout();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RuntimeException.class, ex.getCause());
            assertInstanceOf(HttpTimeoutException.class, ex.getCause().getCause());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Retry policy retries after transport timeout and succeeds on subsequent attempt")
    void retryRecoversFromTransportTimeout() {
        stubFor(get(urlEqualTo("/api/retry-timeout"))
                .inScenario("TimeoutRetry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500))
                .willSetStateTo("SUCCESS_STATE"));

        stubFor(get(urlEqualTo("/api/retry-timeout"))
                .inScenario("TimeoutRetry")
                .whenScenarioStateIs("SUCCESS_STATE")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .build();

        try {
            TimeoutClientApi api = client.get(TimeoutClientApi.class);
            TestDto dto = api.retryTimeout();

            assertNotNull(dto);
            assertEquals(42L, dto.id());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Streaming response with @Timeout(1500) successfully consumes stream within budget")
    void streamingResponseWithTimeout() throws Exception {
        byte[] payload = "Streaming binary timeout content".getBytes(StandardCharsets.UTF_8);
        stubFor(get(urlEqualTo("/api/stream-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(payload)
                        .withFixedDelay(100)));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .build();

        try {
            TimeoutClientApi api = client.get(TimeoutClientApi.class);
            try (InputStream stream = api.streamTimeout()) {
                byte[] read = stream.readAllBytes();
                assertArrayEquals(payload, read);
            }
        } finally {
            client.close();
        }
    }
}
