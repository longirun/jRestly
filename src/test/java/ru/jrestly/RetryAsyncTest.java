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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * The async retry circuit end to end (ADR-0008, Aspect 6 + Aspect 7
 * scenario 9): the same loop chained over {@code sendAsync}, the backoff
 * wait on {@code CompletableFuture.delayedExecutor}, and
 * {@code exchangeAsync}'s final exception mapping untouched — a
 * {@code CompletableFuture} method retries with exactly the sync semantics.
 * Real backoffs are 10–50 ms so CI stays fast.
 */
class RetryAsyncTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    // Small real backoff (10–50 ms) so the suite stays fast (ADR-0008 Aspect 7)
    private static RetryPolicy.Builder smallBackoff() {
        return RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50));
    }

    // A fresh client with the given client-level policy (the base client keeps the off default)
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
}
