package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Retry;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.Jitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration WireMock tests for ADR-0040:
 * Jitter strategies (NONE, EQUAL, DECORRELATED), respectRetryAfter = false,
 * and maxRetryAfterMs capping.
 */
class RetryJitterWireMockTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":42,\"name\":\"test-item\",\"description\":\"jitter\"}";

    public interface RetryJitterController {

        @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, jitter = Jitter.NONE, multiplier = 2.0)
        @Get(path = "/api/jitter-none")
        TestDto getJitterNone();

        @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, jitter = Jitter.EQUAL)
        @Get(path = "/api/jitter-equal")
        TestDto getJitterEqual();

        @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, respectRetryAfter = false)
        @Get(path = "/api/ignore-retry-after")
        TestDto getIgnoreRetryAfter();

        @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, maxRetryAfterMs = 40)
        @Get(path = "/api/capped-retry-after")
        TestDto getCappedRetryAfter();

        @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, jitter = Jitter.DECORRELATED)
        @Get(path = "/api/jitter-decorrelated-async")
        CompletableFuture<TestDto> getDecorrelatedAsync();
    }

    private RetryJitterController jitterController() {
        return client.get(RetryJitterController.class);
    }

    @Test
    @DisplayName("Jitter.NONE: deterministic backoff succeeds after 503 -> 503 -> 200")
    void jitterNoneRetriesDeterministically() {
        stubFor(get(urlEqualTo("/api/jitter-none")).inScenario("none")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-2"));
        stubFor(get(urlEqualTo("/api/jitter-none")).inScenario("none")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-3"));
        stubFor(get(urlEqualTo("/api/jitter-none")).inScenario("none")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto result = jitterController().getJitterNone();

        assertEquals(42L, result.id());
        verify(3, getRequestedFor(urlEqualTo("/api/jitter-none")));
    }

    @Test
    @DisplayName("Jitter.EQUAL: equal jitter backoff succeeds after 503 -> 503 -> 200")
    void jitterEqualRetriesSuccessfully() {
        stubFor(get(urlEqualTo("/api/jitter-equal")).inScenario("equal")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-2"));
        stubFor(get(urlEqualTo("/api/jitter-equal")).inScenario("equal")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-3"));
        stubFor(get(urlEqualTo("/api/jitter-equal")).inScenario("equal")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto result = jitterController().getJitterEqual();

        assertEquals(42L, result.id());
        verify(3, getRequestedFor(urlEqualTo("/api/jitter-equal")));
    }

    @Test
    @DisplayName("respectRetryAfter = false: server 429 Retry-After is ignored, retrying immediately under local backoff")
    void ignoreRetryAfterDoesNotWaitServerInterval() {
        stubFor(get(urlEqualTo("/api/ignore-retry-after")).inScenario("ignore-retry-after")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "10"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/ignore-retry-after")).inScenario("ignore-retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto result = jitterController().getIgnoreRetryAfter();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(42L, result.id());
        // Server asked for 10 seconds, but client ignored it and retried in ~10-50 ms
        assertTrue(elapsedMs < 2000, "Expected fast retry under local backoff, took: " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/ignore-retry-after")));
    }

    @Test
    @DisplayName("maxRetryAfterMs cap: restricts excessive server Retry-After (e.g. 1 hour) to configured cap")
    void cappedRetryAfterPreventsLongHang() {
        stubFor(get(urlEqualTo("/api/capped-retry-after")).inScenario("capped-retry-after")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/capped-retry-after")).inScenario("capped-retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto result = jitterController().getCappedRetryAfter();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(42L, result.id());
        // Capped at 40 ms instead of 3600 seconds
        assertTrue(elapsedMs < 2000, "Expected capped wait around ~40 ms, took: " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/capped-retry-after")));
    }

    @Test
    @DisplayName("Jitter.DECORRELATED async: CompletableFuture pipeline succeeds with adaptive backoff")
    void decorrelatedJitterAsyncTwin() throws ExecutionException, InterruptedException {
        stubFor(get(urlEqualTo("/api/jitter-decorrelated-async")).inScenario("async-decorrelated")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-2"));
        stubFor(get(urlEqualTo("/api/jitter-decorrelated-async")).inScenario("async-decorrelated")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-3"));
        stubFor(get(urlEqualTo("/api/jitter-decorrelated-async")).inScenario("async-decorrelated")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto result = jitterController().getDecorrelatedAsync().get();

        assertEquals(42L, result.id());
        verify(3, getRequestedFor(urlEqualTo("/api/jitter-decorrelated-async")));
    }
}
