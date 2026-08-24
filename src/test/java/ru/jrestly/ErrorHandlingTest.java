package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class ErrorHandlingTest extends BaseWireMockTest {

    @Test
    @DisplayName("@OnError throws HandledException for status from the list")
    void throwsHandledExceptionOnErrorStatus() {
        stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"Invalid request\"}")));

        HandledException exception = assertThrows(HandledException.class, () -> controller.getWithError());

        TestErrorDto error = exception.getDetails();
        assertNotNull(error);
        assertEquals("VALIDATION_ERROR", error.code());
        assertEquals("Invalid request", error.message());

        assertEquals(400, exception.getStatusCode());
        assertTrue(exception.getResponseHeaders().stream()
                .anyMatch(h -> "Content-Type".equals(h.name())
                        && "application/json".equals(h.value())));
    }

    @Test
    @DisplayName("@OnError throws HandledException on 404")
    void throwsHandledExceptionOnNotFound() {
        stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"NOT_FOUND\",\"message\":\"Resource not found\"}")));

        HandledException exception = assertThrows(HandledException.class, () -> controller.getWithError());

        TestErrorDto error = exception.getDetails();
        assertEquals("NOT_FOUND", error.code());

        assertEquals(404, exception.getStatusCode());
        assertTrue(exception.getResponseHeaders().stream()
                .anyMatch(h -> "Content-Type".equals(h.name())));
    }
}
