package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RateLimiter;
import ru.jrestly.resilience.RateLimitExceededException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for the client rate limiter (ADR-0043): per-period permits, per-method
 * opt-out, bounded waiting with timeout and the async path.
 */
@DisplayName("Client Rate Limiter WireMock Integration Tests (ADR-0043)")
class RateLimiterWireMockTest extends BaseWireMockTest {

    @RateLimiter(name = "strict-rate", limitForPeriod = 2, limitRefreshPeriodMs = 1000)
    public interface RateLimitedApi {

        @Get("/api/rl/data")
        String getData();

        @Get("/api/rl/unlimited")
        @RateLimiter(enabled = false)
        String getUnlimited();

        @Get("/api/rl/async")
        CompletableFuture<String> getAsync();
    }

    public interface WaitingRateLimitedApi {
        @Get("/api/rl/wait")
        @RateLimiter(name = "waiting-rate", limitForPeriod = 1, limitRefreshPeriodMs = 80, timeoutDurationMs = 250)
        String getWithWait();
    }

    public interface PlainApi {
        @Get("/api/rl/plain")
        String callPlain();
    }

    @Test
    @DisplayName("Declarative rate limiter permits up to configured limit and fast-fails with RateLimitExceededException")
    void declarativeRateLimiterEnforcesThroughput() {
        stubFor(get(urlEqualTo("/api/rl/data"))
                .willReturn(aResponse().withStatus(200).withBody("DATA")));

        RateLimitedApi api = client.get(RateLimitedApi.class);

        // 1st and 2nd calls succeed
        assertEquals("DATA", api.getData());
        assertEquals("DATA", api.getData());

        // 3rd call exceeds limit
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class, api::getData);
        assertEquals("strict-rate", ex.rateLimiterName());

        // Verify only 2 requests reached the remote server
        verify(2, getRequestedFor(urlEqualTo("/api/rl/data")));
    }

    @Test
    @DisplayName("Rate limiter waits for token refill when timeoutDuration is configured")
    void waitsForTokenRefill() {
        stubFor(get(urlEqualTo("/api/rl/wait"))
                .willReturn(aResponse().withStatus(200).withBody("WAIT_OK")));

        WaitingRateLimitedApi api = client.get(WaitingRateLimitedApi.class);

        // 1st call uses the 1 available token
        assertEquals("WAIT_OK", api.getWithWait());

        // 2nd call waits up to 250ms for the 80ms refresh period, acquires token and succeeds
        assertEquals("WAIT_OK", api.getWithWait());

        verify(2, getRequestedFor(urlEqualTo("/api/rl/wait")));
    }

    @Test
    @DisplayName("Method with @RateLimiter(enabled = false) bypasses interface rate limiter")
    void disabledMethodBypassesRateLimiter() {
        stubFor(get(urlEqualTo("/api/rl/data"))
                .willReturn(aResponse().withStatus(200).withBody("DATA")));
        stubFor(get(urlEqualTo("/api/rl/unlimited"))
                .willReturn(aResponse().withStatus(200).withBody("UNLIMITED")));

        RateLimitedApi api = client.get(RateLimitedApi.class);

        assertEquals("DATA", api.getData());
        assertEquals("DATA", api.getData());
        assertThrows(RateLimitExceededException.class, api::getData);

        // Unlimited call is permitted
        assertEquals("UNLIMITED", api.getUnlimited());
    }

    @Test
    @DisplayName("Async call completes exceptionally with RateLimitExceededException when limit exceeded")
    void asyncCallFailsFastOnRateLimitExceeded() {
        stubFor(get(urlEqualTo("/api/rl/data"))
                .willReturn(aResponse().withStatus(200).withBody("DATA")));
        stubFor(get(urlEqualTo("/api/rl/async"))
                .willReturn(aResponse().withStatus(200).withBody("ASYNC")));

        RateLimitedApi api = client.get(RateLimitedApi.class);

        // Exhaust shared "strict-rate" bucket
        assertEquals("DATA", api.getData());
        assertEquals("DATA", api.getData());

        // Async call must fail fast without network execution
        CompletableFuture<String> future = api.getAsync();
        assertTrue(future.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(RateLimitExceededException.class, ex.getCause());

        verify(0, getRequestedFor(urlEqualTo("/api/rl/async")));
    }

    @Test
    @DisplayName("Client-level rate limiter applies to unannotated interfaces")
    void clientLevelRateLimiterApplies() {
        stubFor(get(urlEqualTo("/api/rl/plain"))
                .willReturn(aResponse().withStatus(200).withBody("PLAIN")));

        ru.jrestly.resilience.RateLimiter rl = ru.jrestly.resilience.RateLimiter.of("client-rl", 2, Duration.ofSeconds(1));

        JRestlyClient limitedClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .rateLimiter(rl)
                .build();
        try {
            PlainApi plainApi = limitedClient.get(PlainApi.class);

            assertEquals("PLAIN", plainApi.callPlain());
            assertEquals("PLAIN", plainApi.callPlain());

            RateLimitExceededException ex = assertThrows(RateLimitExceededException.class, plainApi::callPlain);
            assertEquals("client-rl", ex.rateLimiterName());

            verify(2, getRequestedFor(urlEqualTo("/api/rl/plain")));
        } finally {
            limitedClient.close();
        }
    }
}
