package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract for controller methods returning {@code CompletableFuture<T>}:
 * the call dispatches non-blockingly on the client executor, the future
 * completes with exactly what the synchronous twin would return, and
 * failures carry the same exception types the synchronous twin would throw
 * (per CompletableFuture semantics, as the cause of the join()/get() wrapper).
 */
class CompletableFutureReturnTest extends BaseWireMockTest {

    @Test
    @DisplayName("CompletableFuture<T> completes with the decoded body")
    void getCompletesWithDecodedBody() throws Exception {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Widget\",\"description\":\"A thing\"}")));

        CompletableFuture<TestDto> future = controller.getItemAsync(1L);

        TestDto dto = future.get(2, TimeUnit.SECONDS);
        assertEquals(1L, dto.id());
        assertEquals("Widget", dto.name());
        assertEquals("A thing", dto.description());
    }

    @Test
    @DisplayName("CompletableFuture<T> completes with a decoded collection")
    void getCompletesWithDecodedList() throws Exception {
        stubFor(get(urlPathEqualTo("/api/items"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"A\",\"description\":\"\"},{\"id\":2,\"name\":\"B\",\"description\":\"\"}]")));

        CompletableFuture<List<TestDto>> future = controller.getItemsAsync(1);

        List<TestDto> items = future.get(2, TimeUnit.SECONDS);
        assertEquals(2, items.size());
        assertEquals(1L, items.get(0).id());
        assertEquals("B", items.get(1).name());
    }

    @Test
    @DisplayName("CompletableFuture<T> completes with the echoed POST body")
    void postCompletesWithDecodedBody() throws Exception {
        TestDto incoming = new TestDto(7L, "Gadget", "POSTed");
        stubFor(post(urlEqualTo("/api/items"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"name\":\"Gadget\",\"description\":\"POSTed\"}")));

        CompletableFuture<TestDto> future = controller.createItemAsync(incoming);

        TestDto created = future.get(2, TimeUnit.SECONDS);
        assertEquals(7L, created.id());
        assertEquals("Gadget", created.name());
    }

    @Test
    @DisplayName("CompletableFuture<String> completes with the raw payload")
    void rawStringCompletesWithPayload() throws Exception {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse().withStatus(200).withBody("plain text, not json")));

        CompletableFuture<String> future = controller.getRawAsync();

        assertEquals("plain text, not json", future.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("CompletableFuture<Void> completes with null on an empty body")
    void voidCompletesWithNull() throws Exception {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse().withStatus(204)));

        CompletableFuture<Void> future = controller.getNothingAsync();

        assertNull(future.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("CompletableFuture<Void> completes with null on a non-empty body (async twin of the void pin)")
    void voidCompletesWithNullOnNonEmptyBody() throws Exception {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"a body a void method never decodes\"}")));

        CompletableFuture<Void> future = controller.getNothingAsync();

        assertNull(future.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("The call returns before the response arrives and the future is not yet complete")
    void callIsNonBlocking() throws Exception {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Slow\",\"description\":\"\"}")
                        .withFixedDelay(750)));

        long startedAt = System.nanoTime();
        CompletableFuture<TestDto> future = controller.getItemAsync(1L);
        long callElapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // a synchronous implementation would take >= 750 ms here
        assertTrue(callElapsedMs < 100, "the call blocked for " + callElapsedMs + " ms");
        assertFalse(future.isDone(), "the future was already complete right after dispatch");

        TestDto dto = future.get(5, TimeUnit.SECONDS);
        assertEquals(1L, dto.id());
    }

    @Test
    @DisplayName("A status outside @OnError carries UnexpectedStatusException as the join failure cause")
    void unexpectedStatusSurfacesAsException() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"boom\"}")));

        CompletableFuture<TestDto> future = controller.getServerErrorAsync();

        UnexpectedStatusException exception = causeOf(future::join);
        assertInstanceOf(UnexpectedStatusException.class, exception);
        assertEquals(500, exception.getStatusCode());
        assertEquals("{\"message\":\"boom\"}", exception.getRawBody());
    }

    @Test
    @DisplayName("@OnError carries HandledException with the decoded error as the join failure cause")
    void onErrorSurfacesHandledException() {
        stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"Invalid request\"}")));

        CompletableFuture<TestDto> future = controller.getWithErrorAsync();

        HandledException exception = causeOf(future::join);
        assertInstanceOf(HandledException.class, exception);
        assertEquals(400, exception.getStatusCode());

        TestErrorDto error = exception.getDetails();
        assertEquals("VALIDATION_ERROR", error.code());
        assertEquals("Invalid request", error.message());
    }

    @Test
    @DisplayName("A transport failure carries a RuntimeException with the IO cause as the join failure cause")
    void transportFailureSurfacesAsRuntimeException() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        CompletableFuture<TestDto> future = controller.getServerErrorFaultAsync();

        RuntimeException exception = causeOf(future::join);
        // the raw transport failure, not a nested wrapper
        assertEquals(RuntimeException.class, exception.getClass());
        assertNotNull(exception.getCause(), "the transport failure must be kept as the cause");
    }

    // CompletableFuture semantics: join() wraps the stored failure in a
    // CompletionException; the contract under test is the carried exception
    private static <T extends Throwable> T causeOf(Runnable joining) {
        CompletionException exception = assertThrows(CompletionException.class, joining::run);
        return (T) exception.getCause();
    }

    @Test
    @DisplayName("Redirects are followed asynchronously with method and body semantics preserved")
    void redirectFollowedAsynchronously() throws Exception {
        stubFor(get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/api/items/1")));
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("redirected")));

        CompletableFuture<String> future = controller.getWithRedirectAsync();

        assertEquals("redirected", future.get(2, TimeUnit.SECONDS));
        verify(getRequestedFor(urlEqualTo("/api/items/1")));
    }
}
