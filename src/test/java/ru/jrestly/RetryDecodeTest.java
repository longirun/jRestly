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

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.jrestly.fixtures.TestFutures.causeOf;

/**
 * Tests for payload decode retry behavior.
 */
class RetryDecodeTest extends BaseWireMockTest {

    private static final String GOOD_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    private static final String BAD_JSON = "{not-json";

    private static RetryPolicy.Builder smallBackoff() {
        return RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("200 malformed -> 200 good under the flag: retried, join()-able value, 2 requests")
    void decodeFailureIsRetriedWhenOptedIn() {
        stubFor(get(urlEqualTo("/api/decode")).inScenario("decode")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/decode")).inScenario("decode")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GOOD_JSON)));

        TestDto item = client.get(RetryController.class).getWithDecodeRetry();

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/decode")));
    }

    @Test
    @DisplayName("exhaustion on an unbindable body: the final failure is the codec's own exception")
    void exhaustionKeepsTheCodecException() {
        stubFor(get(urlEqualTo("/api/decode"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON)));

        Throwable failure = assertThrows(RuntimeException.class,
                () -> client.get(RetryController.class).getWithDecodeRetry());

        assertInstanceOf(UncheckedIOException.class, failure);
        assertFalse(failure instanceof UnexpectedStatusException);
        verify(2, getRequestedFor(urlEqualTo("/api/decode")));
    }

    @Test
    @DisplayName("default off: retry enabled for status but the decode flag off — one request, untouched success path")
    void decodeRetryIsOffByDefaultEvenWhenRetryIsOn() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON)));

        moduleInfo.setRetryPolicy(smallBackoff().maxAttempts(3).build());

        Throwable failure = assertThrows(RuntimeException.class,
                () -> new TestApiClient(moduleInfo).get(TestController.class)
                        .getServerError());
        assertInstanceOf(UncheckedIOException.class, failure);
        verify(1, getRequestedFor(urlEqualTo("/api/server-error")));
    }

    @Test
    @DisplayName("@OnError is out of the decode axis: a declared 429 is not decode-retried, typed failure")
    void declaredOnErrorStatusIsNotDecodeRetried() {
        stubFor(get(urlEqualTo("/api/rate-limited"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}")));

        moduleInfo.setRetryPolicy(smallBackoff().maxAttempts(3).retryDecodeFailures(true).build());

        HandledException exception = assertThrows(HandledException.class,
                () -> new TestApiClient(moduleInfo).get(TestController.class).getRateLimited());

        assertEquals(429, exception.getStatusCode());
        verify(1, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("a decode-retry that succeeds runs the auth capture exactly once, on the final response")
    void authCaptureRunsExactlyOnceAcrossADecodeRetry() {
        stubFor(get(urlEqualTo("/api/decode-auth")).inScenario("decode-auth")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/decode-auth")).inScenario("decode-auth")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("access-token", "tok-2")
                        .withBody(GOOD_JSON)));

        TestDto item = client.get(RetryController.class).getDecodeAuth();

        assertEquals(1L, item.id());
        List<Header> authHeaders = moduleInfo.getTestAuthProvider().getAuthHeaders();
        assertEquals(1, authHeaders.size());
        assertEquals("tok-2", authHeaders.get(0).value());
        verify(2, getRequestedFor(urlEqualTo("/api/decode-auth")));
    }

    @Test
    @DisplayName("async twin: 200 malformed -> 200 good, join() returns the value, 2 requests")
    void decodeFailureIsRetriedAsynchronously() {
        stubFor(get(urlEqualTo("/api/decode")).inScenario("decode-async")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/decode")).inScenario("decode-async")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GOOD_JSON)));

        CompletableFuture<TestDto> future = client.get(RetryController.class).getWithDecodeRetryAsync();

        assertEquals(1L, future.join().id());
        verify(2, getRequestedFor(urlEqualTo("/api/decode")));
    }

    @Test
    @DisplayName("async exhaustion on an unbindable body carries the codec exception through join()")
    void asyncExhaustionKeepsTheCodecException() {
        stubFor(get(urlEqualTo("/api/decode"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON)));

        CompletableFuture<TestDto> future = client.get(RetryController.class).getWithDecodeRetryAsync();

        Throwable failure = causeOf(future::join);
        assertInstanceOf(UncheckedIOException.class, failure);
        verify(2, getRequestedFor(urlEqualTo("/api/decode")));
    }

    @Test
    @DisplayName("a known gap, pinned: a declared @OnError status with a malformed error body is not retried")
    void malformedErrorBodyOnDeclaredStatusIsTerminal() {
        stubFor(get(urlEqualTo("/api/rate-limited"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BAD_JSON)));

        moduleInfo.setRetryPolicy(smallBackoff().maxAttempts(3).retryDecodeFailures(true).build());

        assertThrows(RuntimeException.class,
                () -> new TestApiClient(moduleInfo).get(TestController.class).getRateLimited());
        verify(1, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }
}
