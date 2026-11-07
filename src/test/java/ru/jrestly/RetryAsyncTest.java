package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RetryController;
import ru.jrestly.fixtures.TestApiClient;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.UnexpectedStatusException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * Tests for asynchronous retry execution.
 */
class RetryAsyncTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    private static RetryPolicy.Builder smallBackoff() {
        return RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50));
    }

    private TestController with(RetryPolicy retryPolicy) {
        moduleInfo.setRetryPolicy(retryPolicy);
        return new TestApiClient(moduleInfo).get(TestController.class);
    }

    @Test
    @DisplayName("503 -> 200: the CompletableFuture method retries on the wire, join() returns the value, 2 requests")
    void flappingServerRecoversAsynchronously() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-flap")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-flap")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(2).build()).getServerErrorAsync().join();

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("transport fault then success: a connection reset is retried on the async circuit, 2 requests")
    void transportFaultIsRetriedAsynchronously() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-transport")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-transport")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(3).build()).getServerErrorFaultAsync().join();

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("exhaustion: 503 forever, 3 attempts — the future carries the sync twin's failure shape, 3 requests")
    void exhaustionKeepsTheFinalFailureShapeAsynchronously() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(503)));

        CompletableFuture<TestDto> future = with(smallBackoff().maxAttempts(3).build()).getServerErrorAsync();

        UnexpectedStatusException exception = causeOf(future::join);
        assertEquals(503, exception.getStatusCode());
        verify(3, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("non-retryable status: a bare 404 is a deterministic client error on the async circuit, one request")
    void nonRetryableStatusStopsOnTheFirstAttemptAsynchronously() {
        stubFor(get(urlEqualTo("/api/items/404"))
                .willReturn(aResponse().withStatus(404)));

        CompletableFuture<TestDto> future = with(smallBackoff().maxAttempts(3).build()).getItemAsync(404L);

        UnexpectedStatusException exception = causeOf(future::join);
        assertEquals(404, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/items/404")));
    }

    @Test
    @DisplayName("bare @Retry is the off-switch on the async circuit too: an enabled client policy is overridden, one request")
    void bareAnnotationIsAnOffSwitchOnTheAsyncCircuit() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(503)));

        moduleInfo.setRetryPolicy(RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .maxAttempts(3)
                .build());

        CompletableFuture<TestDto> future = client.get(RetryController.class).getWithBareRetryAsync();

        UnexpectedStatusException exception = causeOf(future::join);
        assertEquals(503, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("Retry-After raises the async wait too: a 1 s header demand is honored on the delayedExecutor circuit")
    void retryAfterRaisesTheWaitOnTheAsyncCircuit() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-retry-after")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("async-retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"a\",\"description\":\"b\"}")));

        long start = System.nanoTime();
        TestDto item = with(smallBackoff().maxAttempts(2).maxRetryAfter(Duration.ofSeconds(10)).build())
                .getServerErrorAsync().join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs >= 1_000, "expected >= 1000 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }
}
