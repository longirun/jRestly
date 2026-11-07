package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestApiClient;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.UnexpectedStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for retry execution.
 */
class RetryTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    private static final String RATE_LIMITED_JSON = "{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}";

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
    @DisplayName("off by default: no policy on the module — 503 fails fast, one request on the wire")
    void retryIsOffByDefault() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(503)));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class, () -> controller.getServerError());

        assertEquals(503, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("503 -> 503 -> 200: a flapping server recovers within the budget, 3 requests")
    void flappingServerRecoversWithinBudget() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("flap")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-503"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("flap")
                .whenScenarioStateIs("second-503")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("flap")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(3).build()).getServerError();

        assertEquals(1L, item.id());
        verify(3, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("exhaustion: 503 forever, 3 attempts — the final failure keeps its shape, 3 requests")
    void exhaustionKeepsTheFinalFailureShape() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(503)));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class, () -> with(smallBackoff().maxAttempts(3).build()).getServerError());

        assertEquals(503, exception.getStatusCode());
        verify(3, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("transport fault then success: a connection reset is retried, 2 requests")
    void transportFaultIsRetried() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("transport")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("transport")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(3).build()).getServerError();

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("non-retryable status: a bare 404 is a deterministic client error, one request")
    void nonRetryableStatusStopsOnTheFirstAttempt() {
        stubFor(get(urlEqualTo("/api/items/404"))
                .willReturn(aResponse().withStatus(404)));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class, () -> with(smallBackoff().maxAttempts(3).build()).getItem(404L));

        assertEquals(404, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/items/404")));
    }

    @Test
    @DisplayName("@OnError is a terminal contract by default: a declared 429 is not retried, typed exception")
    void onErrorStatusIsTerminalByDefault() {
        stubFor(get(urlEqualTo("/api/rate-limited"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON)));

        HandledException exception = assertThrows(
                HandledException.class, () -> with(smallBackoff().maxAttempts(3).build()).getRateLimited());

        assertEquals(429, exception.getStatusCode());
        assertFalse(exception instanceof UnexpectedStatusException,
                "the declared status must stay a HandledException, not an UnexpectedStatusException");
        verify(1, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("@OnError opt-in (retryOnErrorStatuses): 429 -> 429 -> 200, 3 requests, final success")
    void onErrorStatusesAreRetriedWhenOptedIn() {
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("opt-in")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON))
                .willSetStateTo("second-429"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("opt-in")
                .whenScenarioStateIs("second-429")
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RATE_LIMITED_JSON))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/rate-limited")).inScenario("opt-in")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(3).retryOnErrorStatuses(true).build()).getRateLimited();

        assertEquals(1L, item.id());
        verify(3, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("idempotency: POST is not retried by default (RFC 9110), one request")
    void postIsNotRetriedByDefault() {
        stubFor(post(urlEqualTo("/api/items"))
                .willReturn(aResponse().withStatus(503)));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class,
                () -> with(smallBackoff().maxAttempts(3).build())
                        .createItem(new TestDto(1L, "x", "y")));

        assertEquals(503, exception.getStatusCode());
        verify(1, postRequestedFor(urlEqualTo("/api/items")));
    }

    @Test
    @DisplayName("idempotency opt-in (retryNonIdempotent): POST 503 -> 503 -> 200, 3 requests")
    void postIsRetriedWhenNonIdempotentIsOn() {
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-flap")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-503"));
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-flap")
                .whenScenarioStateIs("second-503")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(post(urlEqualTo("/api/items")).inScenario("post-flap")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(3).retryNonIdempotent(true).build())
                .createItem(new TestDto(1L, "x", "y"));

        assertEquals(1L, item.id());
        verify(3, postRequestedFor(urlEqualTo("/api/items")));
    }

    @Test
    @DisplayName("a redirect chain re-runs from hop zero on each attempt")
    void redirectChainReRunsPerAttempt() {
        stubFor(get(urlEqualTo("/api/redirect")).inScenario("redirect-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("redirect"));
        stubFor(get(urlEqualTo("/api/redirect")).inScenario("redirect-retry")
                .whenScenarioStateIs("redirect")
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", baseUrl() + "/api/raw"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse().withBody("payload")));

        String body = with(smallBackoff().maxAttempts(2).build()).getWithRedirect();

        assertEquals("payload", body);
        verify(2, getRequestedFor(urlEqualTo("/api/redirect")));
        verify(1, getRequestedFor(urlEqualTo("/api/raw")));
    }

    @Test
    @DisplayName("multipart POST retry: the body is re-sent complete on every attempt, boundary stable per call")
    void multipartBodyIsReSentPerAttemptWithAStableBoundary() throws Exception {
        stubFor(post(urlEqualTo("/api/upload")).inScenario("upload-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(post(urlEqualTo("/api/upload")).inScenario("upload-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withBody("uploaded")));

        Path file = Files.createTempFile("jrestly-retry", ".txt");
        Files.writeString(file, "multipart payload");

        String result = with(smallBackoff().maxAttempts(2).retryNonIdempotent(true).build())
                .uploadFile(file.toAbsolutePath().toString());

        assertEquals("uploaded", result);
        verify(2, postRequestedFor(urlEqualTo("/api/upload")));

        var attempts = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .toList();
        assertEquals(2, attempts.size());
        String boundary = boundaryOf(attempts.get(0).getRequest().getHeader("Content-Type"));
        assertTrue(boundary.startsWith("jrestly-"));
        assertEquals(boundary, boundaryOf(attempts.get(1).getRequest().getHeader("Content-Type")));
        assertArrayEquals(attempts.get(0).getRequest().getBody(), attempts.get(1).getRequest().getBody());
    }

    private static String boundaryOf(String contentType) {
        int index = contentType.indexOf("boundary=");
        if (index < 0) {
            throw new IllegalStateException("No boundary in Content-Type: " + contentType);
        }
        return contentType.substring(index + "boundary=".length());
    }

    @Test
    @DisplayName("Retry-After: the integer-seconds header is a deterministic lower bound on the wait")
    void retryAfterHeaderRaisesTheWaitDeterministically() {
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto item = with(smallBackoff().maxAttempts(2).maxRetryAfter(Duration.ofSeconds(10)).build()).getServerError();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs >= 1_000, "expected >= 1000 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("Retry-After HTTP-date: an IMF-fixdate ~3 s in the future raises the wait")
    void retryAfterHttpDateRaisesTheWaitDeterministically() {
        String httpDate = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after-date")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", httpDate))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after-date")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto item = with(smallBackoff().maxAttempts(2).maxRetryAfter(Duration.ofSeconds(10)).build()).getServerError();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs >= 2_000, "expected >= 2000 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("Retry-After RFC 850 (obsolete form): honored deterministically like the live forms")
    void retryAfterRfc850DateRaisesTheWait() {
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3).withNano(0);
        String rfc850 = future.format(DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after-rfc850")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", rfc850))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/server-error")).inScenario("retry-after-rfc850")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        long start = System.nanoTime();
        TestDto item = with(smallBackoff().maxAttempts(2).maxRetryAfter(Duration.ofSeconds(10)).build()).getServerError();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1L, item.id());
        assertTrue(elapsedMs >= 2_000, "expected >= 2000 ms, got " + elapsedMs + " ms");
        verify(2, getRequestedFor(urlEqualTo("/api/server-error")));
    }
}
