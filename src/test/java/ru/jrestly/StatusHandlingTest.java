package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class StatusHandlingTest extends BaseWireMockTest {

    @Test
    @DisplayName("@ExpectStatus({202}): matched status — success, body ignored for void")
    void expectStatusMatchedReturnsSuccess() {
        stubFor(post(urlEqualTo("/api/async"))
                .willReturn(aResponse().withStatus(202)));

        controller.createAsync(new TestDto(1L, "x", "y"));

        verify(postRequestedFor(urlEqualTo("/api/async")));
    }

    @Test
    @DisplayName("@ExpectStatus({202}): got 200 — UnexpectedStatusException, rawBody available")
    void expectStatusMismatchThrowsUnexpected() {
        stubFor(post(urlEqualTo("/api/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"already\",\"description\":\"created\"}")));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class,
                () -> controller.createAsync(new TestDto(1L, "x", "y"))
        );

        assertEquals(200, exception.getStatusCode());
        assertTrue(exception.getRawBody().contains("already"));
        assertTrue(exception.getResponseHeaders().stream()
                .anyMatch(h -> "Content-Type".equalsIgnoreCase(h.name())));
    }

    @Test
    @DisplayName("@ExpectStatus({204}): 204 for void — success")
    void expectStatus204ForVoidIsSuccess() {
        stubFor(delete(urlEqualTo("/api/items/42/silent"))
                .willReturn(aResponse().withStatus(204)));

        controller.deleteSilent(42L);
    }

    @Test
    @DisplayName("Without @ExpectStatus and @OnError: 500 — UnexpectedStatusException (default strict)")
    void unexpectedServerErrorThrowsByDefault() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>Internal Server Error</html>")));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class,
                () -> controller.getServerError()
        );

        assertEquals(500, exception.getStatusCode());
        assertTrue(exception.getRawBody().contains("Internal Server Error"));
        assertTrue(exception.getMessage().contains("500"));
    }

    @Test
    @DisplayName("Without @ExpectStatus and @OnError: 500 with empty body — UnexpectedStatusException, rawBody empty")
    void unexpectedServerErrorEmptyBody() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(502)));

        UnexpectedStatusException exception = assertThrows(
                UnexpectedStatusException.class,
                () -> controller.getServerError()
        );

        assertEquals(502, exception.getStatusCode());
        assertEquals("", exception.getRawBody());
    }

    @Test
    @DisplayName("@OnError takes precedence over UnexpectedStatusException")
    void onErrorTakesPrecedenceOverUnexpected() {
        stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"BAD\",\"message\":\"x\"}")));

        // getWithError is declared with @OnError({400,404}) — HandledException is expected,
        // not UnexpectedStatusException, even though 400 is not in the 2xx range.
        HandledException exception = assertThrows(HandledException.class, () -> controller.getWithError());

        assertEquals(400, exception.getStatusCode());
        assertFalse(exception instanceof UnexpectedStatusException);
    }

    @Test
    @DisplayName("UnexpectedStatusException is a subclass of HandledException, caught as parent")
    void unexpectedIsInstanceOfHandled() {
        stubFor(get(urlEqualTo("/api/server-error"))
                .willReturn(aResponse().withStatus(500)));

        HandledException caughtAsParent = assertThrows(HandledException.class, () -> controller.getServerError());
        assertInstanceOf(UnexpectedStatusException.class, caughtAsParent);
    }
}
