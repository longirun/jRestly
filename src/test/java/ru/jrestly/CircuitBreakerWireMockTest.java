package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.CircuitBreaker;
import ru.jrestly.annotation.Get;
import ru.jrestly.http.ClientErrorException;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.resilience.CircuitBreakerOpenException;

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
 * End-to-end for the client circuit breaker (ADR-0043): opening on remote failures,
 * fast-fail without network calls, per-method {@code enabled = false} opt-out and the
 * async path.
 */
@DisplayName("Circuit Breaker WireMock Integration Tests (ADR-0043)")
class CircuitBreakerWireMockTest extends BaseWireMockTest {

    @CircuitBreaker(name = "flaky-service", minimumNumberOfCalls = 2, slidingWindowSize = 2, failureRateThreshold = 50.0f, waitDurationMs = 80)
    public interface FlakyApi {

        @Get(path = "/api/cb/flaky")
        String callFlaky();

        @Get(path = "/api/cb/safe")
        @CircuitBreaker(enabled = false)
        String callSafe();

        @Get(path = "/api/cb/async")
        CompletableFuture<String> callFlakyAsync();
    }

    public interface ClientErrorApi {
        @CircuitBreaker(name = "client-error-service", minimumNumberOfCalls = 2, slidingWindowSize = 2, failureRateThreshold = 50.0f)
        @Get(path = "/api/cb/missing")
        String callMissing();
    }

    public interface PlainApi {
        @Get(path = "/api/cb/plain")
        String callPlain();
    }

    @Test
    @DisplayName("Trips circuit to OPEN on remote server failures and fast-fails without network calls")
    void tripsCircuitOnServerFailures() {
        stubFor(get(urlEqualTo("/api/cb/flaky"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Error")));

        FlakyApi api = client.get(FlakyApi.class);

        // 1st failure (500)
        assertThrows(ServerErrorException.class, api::callFlaky);
        // 2nd failure (500) -> trips circuit to OPEN
        assertThrows(ServerErrorException.class, api::callFlaky);

        // 3rd call must immediately fail with CircuitBreakerOpenException without touching the network!
        CircuitBreakerOpenException ex = assertThrows(CircuitBreakerOpenException.class, api::callFlaky);
        assertEquals("flaky-service", ex.circuitBreakerName());

        // Verify only 2 requests reached the remote server
        verify(2, getRequestedFor(urlEqualTo("/api/cb/flaky")));
    }

    @Test
    @DisplayName("Client errors (HTTP 4xx) do not trip the circuit breaker")
    void clientErrorsDoNotTripCircuitBreaker() {
        stubFor(get(urlEqualTo("/api/cb/missing"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        ClientErrorApi api = client.get(ClientErrorApi.class);

        // 404 errors are client errors, not remote server crashes
        assertThrows(ClientErrorException.class, api::callMissing);
        assertThrows(ClientErrorException.class, api::callMissing);
        assertThrows(ClientErrorException.class, api::callMissing);

        // All 3 reached the network, circuit never opened
        verify(3, getRequestedFor(urlEqualTo("/api/cb/missing")));
    }

    @Test
    @DisplayName("Recovers from OPEN to HALF_OPEN and resets to CLOSED on successful calls")
    void recoversToClosedAfterWaitDuration() throws InterruptedException {
        stubFor(get(urlEqualTo("/api/cb/flaky"))
                .willReturn(aResponse().withStatus(503).withBody("Unavailable")));

        FlakyApi api = client.get(FlakyApi.class);

        // Trip to OPEN
        assertThrows(ServerErrorException.class, api::callFlaky);
        assertThrows(ServerErrorException.class, api::callFlaky);
        assertThrows(CircuitBreakerOpenException.class, api::callFlaky);

        // Reconfigure server to return 200 OK
        stubFor(get(urlEqualTo("/api/cb/flaky"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        // Wait for waitDurationMs (80ms)
        Thread.sleep(120);

        // Next calls probe and reset the circuit to CLOSED
        assertEquals("OK", api.callFlaky());
        assertEquals("OK", api.callFlaky());

        // Now circuit is fully CLOSED and healthy
        assertEquals("OK", api.callFlaky());
    }

    @Test
    @DisplayName("Method with @CircuitBreaker(enabled = false) bypasses interface-level circuit breaker")
    void disabledMethodBypassesCircuitBreaker() {
        stubFor(get(urlEqualTo("/api/cb/flaky"))
                .willReturn(aResponse().withStatus(500)));
        stubFor(get(urlEqualTo("/api/cb/safe"))
                .willReturn(aResponse().withStatus(200).withBody("SAFE_OK")));

        FlakyApi api = client.get(FlakyApi.class);

        // Trip flaky calls
        assertThrows(ServerErrorException.class, api::callFlaky);
        assertThrows(ServerErrorException.class, api::callFlaky);
        assertThrows(CircuitBreakerOpenException.class, api::callFlaky);

        // Safe call has enabled = false -> succeeds normally
        assertEquals("SAFE_OK", api.callSafe());
    }

    @Test
    @DisplayName("Async CompletableFuture call completes exceptionally with CircuitBreakerOpenException")
    void asyncCallFailsFastWhenOpen() {
        stubFor(get(urlEqualTo("/api/cb/flaky"))
                .willReturn(aResponse().withStatus(500)));
        stubFor(get(urlEqualTo("/api/cb/async"))
                .willReturn(aResponse().withStatus(200).withBody("ASYNC_OK")));

        FlakyApi api = client.get(FlakyApi.class);

        // Trip the shared "flaky-service" circuit breaker
        assertThrows(ServerErrorException.class, api::callFlaky);
        assertThrows(ServerErrorException.class, api::callFlaky);

        // Async call must complete exceptionally
        CompletableFuture<String> future = api.callFlakyAsync();
        assertTrue(future.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(CircuitBreakerOpenException.class, ex.getCause());

        // Network call was not made
        verify(0, getRequestedFor(urlEqualTo("/api/cb/async")));
    }

    @Test
    @DisplayName("Client-level circuit breaker protects unannotated interfaces")
    void clientLevelCircuitBreakerProtectsUnannotated() {
        stubFor(get(urlEqualTo("/api/cb/plain"))
                .willReturn(aResponse().withStatus(500)));

        ru.jrestly.resilience.CircuitBreaker cb = ru.jrestly.resilience.CircuitBreaker.of("client-level-cb",
                ru.jrestly.resilience.CircuitBreakerConfig.builder()
                        .minimumNumberOfCalls(2)
                        .slidingWindowSize(2)
                        .failureRateThreshold(50.0f)
                        .build());

        JRestlyClient resilientClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .circuitBreaker(cb)
                .build();
        try {
            PlainApi plainApi = resilientClient.get(PlainApi.class);

            assertThrows(ServerErrorException.class, plainApi::callPlain);
            assertThrows(ServerErrorException.class, plainApi::callPlain);

            CircuitBreakerOpenException ex = assertThrows(CircuitBreakerOpenException.class, plainApi::callPlain);
            assertEquals("client-level-cb", ex.circuitBreakerName());

            verify(2, getRequestedFor(urlEqualTo("/api/cb/plain")));
        } finally {
            resilientClient.close();
        }
    }
}
