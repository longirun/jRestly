package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.RetryController;
import ru.jrestly.fixtures.TestApiClient;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.RetryPolicy;

import java.io.UncheckedIOException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The payload-decode retry axis end to end (ADR-0011, Aspect 7, scenarios
 * 1–5): a complete 200 body the codec cannot bind is the third failure-kind.
 * It is retried only under the distinct opt-in {@code retryDecodeFailures},
 * the final exception stays the codec's own unchecked exception (no new
 * type), declared {@code @OnError} statuses stay out of scope, and the
 * auth-capture consumer fires exactly once across the retry ("classified
 * exactly once"). Real backoffs are 10–50 ms so CI stays fast.
 */
class RetryDecodeTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":1,\"name\":\"a\",\"description\":\"b\"}";

    // A complete 200 body that does not bind to TestDto — the gap case:
    // the bytes arrived in full, but they do not bind (ADR-0011 Aspect 5)
    private static final String GARBAGE_BODY = "not-json";

    // Small real backoff (10–50 ms) so the suite stays fast (ADR-0011 Aspect 7)
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

    private RetryController retryController() {
        return client.get(RetryController.class);
    }

    @Test
    @DisplayName("200 garbage -> 200 good: the decode failure is retried under the opt-in, 2 requests")
    void decodeFailureIsRetriedWhenOptedIn() {
        stubFor(get(urlEqualTo("/api/items/1")).inScenario("decode-flap")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GARBAGE_BODY))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/items/1")).inScenario("decode-flap")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        TestDto item = with(smallBackoff().maxAttempts(2).retryDecodeFailures(true).build()).getItem(1L);

        assertEquals(1L, item.id());
        verify(2, getRequestedFor(urlEqualTo("/api/items/1")));
    }

    @Test
    @DisplayName("exhaustion: 200 garbage forever, 2 attempts — the final exception is the codec's own, 2 requests")
    void exhaustionKeepsTheCodecException() {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GARBAGE_BODY)));

        UncheckedIOException exception = assertThrows(
                UncheckedIOException.class,
                () -> with(smallBackoff().maxAttempts(2).retryDecodeFailures(true).build()).getItem(1L));

        assertNotNull(exception.getCause(), "the codec wraps the original IO failure, the shape is unchanged");
        verify(2, getRequestedFor(urlEqualTo("/api/items/1")));
    }

    @Test
    @DisplayName("default off: retry enabled for status, decode flag off — the garbage 200 fails fast, one request")
    void decodeFlagOffIsZeroChangeOnTheSuccessPath() {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GARBAGE_BODY)));

        UncheckedIOException exception = assertThrows(
                UncheckedIOException.class,
                () -> with(smallBackoff().maxAttempts(3).build()).getItem(1L));

        assertNotNull(exception.getCause());
        verify(1, getRequestedFor(urlEqualTo("/api/items/1")));
    }

    @Test
    @DisplayName("@OnError out of scope: a declared 429 with a malformed error body is not decode-retried, one request")
    void onErrorStatusIsOutOfTheDecodeScope() {
        stubFor(get(urlEqualTo("/api/rate-limited"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GARBAGE_BODY)));

        // The declared status stays terminal: the probe returns true for it
        // (no decode retry, one request). The error body itself does not bind,
        // so the @OnError branch of processResponse throws the codec's own
        // exception before the HandledException is even constructed — the
        // documented known gap of ADR-0011, orthogonal to the decode axis
        UncheckedIOException exception = assertThrows(
                UncheckedIOException.class,
                () -> with(smallBackoff().maxAttempts(2).retryDecodeFailures(true).build()).getRateLimited());

        assertNotNull(exception.getCause());
        verify(1, getRequestedFor(urlEqualTo("/api/rate-limited")));
    }

    @Test
    @DisplayName("classified exactly once: the auth-capture consumer fires exactly once across the decode-retry")
    void authCaptureFiresExactlyOnceAcrossTheDecodeRetry() {
        stubFor(get(urlEqualTo("/api/decode-auth")).inScenario("decode-auth")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("access-token", "token-first")
                        .withBody(GARBAGE_BODY))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/decode-auth")).inScenario("decode-auth")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("access-token", "token-final")
                        .withBody(ITEM_JSON)));

        TestDto item = retryController().getDecodeAuth();

        assertEquals(1L, item.id());
        assertEquals(1, moduleInfo.getTestAuthProvider().getCaptureCount(),
                "processResponse (and its auth-capture) must run exactly once, on the truly final response");
        assertEquals(1, moduleInfo.getTestAuthProvider().getAuthHeaders().size());
        assertEquals("token-final", moduleInfo.getTestAuthProvider().getAuthHeaders().get(0).value());
        verify(2, getRequestedFor(urlEqualTo("/api/decode-auth")));
    }
}
