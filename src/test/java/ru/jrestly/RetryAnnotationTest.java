package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RetryController;
import ru.jrestly.fixtures.TestApiClient;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.Header;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.UnexpectedStatusException;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ru.jrestly.annotation.Retry @Retry} annotation.
 */
class RetryAnnotationTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    private static final String RATE_LIMITED_JSON = "{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}";

    private RetryController retryController() {
        return client.get(RetryController.class);
    }

    @Test
    @DisplayName("bare @Retry is the explicit off-switch: an enabled client policy is overridden, one request")
    void bareRetryOverridesAnEnabledClientPolicy() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(503)));

        moduleInfo.setRetryPolicy(RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .maxAttempts(3)
                .build());

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class, () -> retryController().getWithBareRetry());

        assertEquals(503, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("presence is a self-contained policy: client off, 429 -> 429 -> 200, 3 requests")
    void presenceReplacesTheOffClientPolicy() {
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("self-contained")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second-429"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("self-contained")
                .whenScenarioStateIs("second-429")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("self-contained")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController().getRateLimitedWithRetry();

        assertEquals(1L, item.id());
        verify(3, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("nonIdempotent: POST 503 -> 503 -> 200 under the annotation alone, 3 requests")
    void postIsRetriedWhenNonIdempotentIsDeclared() {
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-self-contained")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-503"));
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-self-contained")
                .whenScenarioStateIs("second-503")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-self-contained")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController()
                .createItemWithNonIdempotentRetry(new TestDto(1L, "x", "y"));

        assertEquals(1L, item.id());
        verify(3, postRequestedFor(urlEqualTo("/api/items")));
    }

    @Test
    @DisplayName("onErrorStatuses: declared 429s are retried, 429 -> 429 -> 200, 3 requests")
    void declaredOnErrorStatusesAreRetriedWhenOptedIn() {
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("annotation-opt-in")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON))
                .willSetStateTo("second-429"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("annotation-opt-in")
                .whenScenarioStateIs("second-429")
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("annotation-opt-in")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController().getRateLimitedWithOnErrorRetry();

        assertEquals(1L, item.id());
        verify(3, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("onErrorStatuses exhaustion: 429 forever — after the attempts the failure stays typed")
    void declaredOnErrorExhaustionStaysTyped() {
        stubFor(get(urlEqualTo("/api/rate-limited"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON)));

        HandledException exception = assertThrows(
                HandledException.class, () -> retryController().getRateLimitedWithOnErrorRetry());

        assertEquals(429, exception.getStatusCode());
        assertFalse(exception instanceof UnexpectedStatusException,
                "the declared status must stay a HandledException, not an UnexpectedStatusException");
        verify(3, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("statuses replaces the default family: 501 (not a default) is retried, 501 -> 200, 2 requests")
    void customStatusesReplaceTheDefaultFamily() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("custom-statuses")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(501))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("custom-statuses")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController().getWithCustomStatuses();

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("respectRetryAfter = false on the annotation: a 1 s header demand is ignored (well under 1 s)")
    void respectRetryAfterFalseIgnoresTheHeaderOnTheWire() {
        stubFor(get(urlEqualTo("/api/rate-limited-ignored")).inScenario("ignored")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/rate-limited-ignored")).inScenario("ignored")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto item = retryController().getWithRetryAfterIgnored();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs < 900, "expected < 900 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/rate-limited-ignored")));
    }

    @Test
    @DisplayName("maxRetryAfterMs on the annotation: a 1 s header demand is capped at ~100 ms")
    void maxRetryAfterMsCapsTheHeaderOnTheWire() {
        stubFor(get(urlEqualTo("/api/rate-limited-capped")).inScenario("capped")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/rate-limited-capped")).inScenario("capped")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto item = retryController().getWithRetryAfterCapped();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs >= 100, "expected >= 100 ms, got " + elapsedMs + " ms");
        assertTrue(elapsedMs < 900, "expected < 900 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/rate-limited-capped")));
    }

    @Test
    @DisplayName("a retry that eventually succeeds runs the auth capture exactly once, on the final response")
    void authCaptureRunsExactlyOnceAcrossARetry() {
        stubFor(get(urlEqualTo("/api/auth-check")).inScenario("auth-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/auth-check")).inScenario("auth-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("access-token", "tok-1")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController().getWithRetryAndAuthDetails();

        assertEquals(1L, item.id());
        List<Header> authHeaders = moduleInfo.getTestAuthProvider().getAuthHeaders();
        assertEquals(1, authHeaders.size());
        assertEquals("access-token", authHeaders.get(0).name());
        assertEquals("tok-1", authHeaders.get(0).value());
        verify(2, getRequestedFor(urlEqualTo("/api/auth-check")));
    }
}
