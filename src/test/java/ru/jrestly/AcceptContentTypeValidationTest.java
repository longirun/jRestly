package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation of {@code @Accept}/{@code @ContentType} at interface and method level:
 * empty/blank values and CRLF header-injection sequences are rejected upfront by
 * {@code ControllerValidator}. No network.
 */
class AcceptContentTypeValidationTest {

    @Accept({})
    interface InterfaceEmptyAcceptController {
        @Get(path = "/test")
        String test();
    }

    @Accept({"   "})
    interface InterfaceBlankAcceptController {
        @Get(path = "/test")
        String test();
    }

    @Accept({"application/json\r\nInjected: evil"})
    interface InterfaceCrlfAcceptController {
        @Get(path = "/test")
        String test();
    }

    @ContentType("   ")
    interface InterfaceBlankContentTypeController {
        @Post(path = "/test")
        String test();
    }

    @ContentType("application/json\nInjected: evil")
    interface InterfaceCrlfContentTypeController {
        @Post(path = "/test")
        String test();
    }

    interface MethodEmptyAcceptController {
        @Get(path = "/test")
        @Accept({})
        String test();
    }

    interface MethodBlankAcceptController {
        @Get(path = "/test")
        @Accept({"application/json", ""})
        String test();
    }

    interface MethodCrlfAcceptController {
        @Get(path = "/test")
        @Accept({"text/plain\rInjected: evil"})
        String test();
    }

    interface MethodBlankContentTypeController {
        @Post(path = "/test")
        @ContentType("")
        String test();
    }

    interface MethodCrlfContentTypeController {
        @Post(path = "/test")
        @ContentType("application/xml\r\nInjected: evil")
        String test();
    }

    @Accept(MediaType.APPLICATION_JSON)
    @ContentType(MediaType.APPLICATION_JSON)
    interface ValidAnnotatedController {
        @Get(path = "/items")
        @Accept({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
        String getItems();

        @Post(path = "/items")
        @ContentType(MediaType.APPLICATION_XML)
        String createItem();
    }

    @Test
    @DisplayName("Validation fails when interface @Accept has empty value")
    void failsOnInterfaceEmptyAccept() {
        List<String> problems = ControllerValidator.validate(InterfaceEmptyAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept value must not be empty")));
    }

    @Test
    @DisplayName("Validation fails when interface @Accept has blank media type")
    void failsOnInterfaceBlankAccept() {
        List<String> problems = ControllerValidator.validate(InterfaceBlankAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept media type must not be blank")));
    }

    @Test
    @DisplayName("Validation fails when interface @Accept contains CRLF characters")
    void failsOnInterfaceCrlfAccept() {
        List<String> problems = ControllerValidator.validate(InterfaceCrlfAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept media type must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validation fails when interface @ContentType is blank")
    void failsOnInterfaceBlankContentType() {
        List<String> problems = ControllerValidator.validate(InterfaceBlankContentTypeController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@ContentType must not be blank")));
    }

    @Test
    @DisplayName("Validation fails when interface @ContentType contains CRLF characters")
    void failsOnInterfaceCrlfContentType() {
        List<String> problems = ControllerValidator.validate(InterfaceCrlfContentTypeController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@ContentType must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validation fails when method @Accept has empty value")
    void failsOnMethodEmptyAccept() {
        List<String> problems = ControllerValidator.validate(MethodEmptyAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept value must not be empty")));
    }

    @Test
    @DisplayName("Validation fails when method @Accept contains blank entry")
    void failsOnMethodBlankAccept() {
        List<String> problems = ControllerValidator.validate(MethodBlankAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept media type must not be blank")));
    }

    @Test
    @DisplayName("Validation fails when method @Accept contains CRLF characters")
    void failsOnMethodCrlfAccept() {
        List<String> problems = ControllerValidator.validate(MethodCrlfAcceptController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept media type must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validation fails when method @ContentType is blank")
    void failsOnMethodBlankContentType() {
        List<String> problems = ControllerValidator.validate(MethodBlankContentTypeController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@ContentType must not be blank")));
    }

    @Test
    @DisplayName("Validation fails when method @ContentType contains CRLF characters")
    void failsOnMethodCrlfContentType() {
        List<String> problems = ControllerValidator.validate(MethodCrlfContentTypeController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@ContentType must not contain CRLF characters")));
    }

    @Test
    @DisplayName("JRestlyClient proxy creation throws ControllerValidationException for invalid annotations")
    void proxyCreationThrowsOnValidationError() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        try {
            assertThrows(ControllerValidationException.class, () -> client.get(InterfaceBlankAcceptController.class));
            assertThrows(ControllerValidationException.class, () -> client.get(MethodCrlfContentTypeController.class));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Validation passes cleanly for valid @Accept and @ContentType configurations")
    void validationPassesForValidAnnotations() {
        List<String> problems = ControllerValidator.validate(ValidAnnotatedController.class);
        assertTrue(problems.isEmpty(), "Valid controller must have no validation problems: " + problems);
    }

    @Test
    @DisplayName("Client builder rejects blank default header names")
    void builderRejectsBlankDefaultHeaderName() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(IllegalArgumentException.class, () -> builder.defaultHeader("   ", "value"));
    }

    @Test
    @DisplayName("Client builder rejects CRLF in default header names")
    void builderRejectsCrlfDefaultHeaderName() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(IllegalArgumentException.class,
                () -> builder.defaultHeader("X-Bad\r\nInjected: evil", "value"));
    }

    @Test
    @DisplayName("Client builder rejects null default header values")
    void builderRejectsNullDefaultHeaderValue() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(IllegalArgumentException.class, () -> builder.defaultHeader("X-Api-Key", null));
    }

    @Test
    @DisplayName("Client builder rejects CRLF in default header values")
    void builderRejectsCrlfDefaultHeaderValue() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(IllegalArgumentException.class,
                () -> builder.defaultHeader("Accept", "application/json\r\nInjected: evil"));
    }

    @Test
    @DisplayName("Client builder replaces same-name default headers case-insensitively")
    void builderReplacesSameNameDefaultHeadersCaseInsensitively() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertDoesNotThrow(() -> {
            builder.defaultHeader("X-Tenant", "first");
            builder.defaultHeader("x-tenant", "second");
            builder.defaultHeaders(Map.of("X-Tenant", "third"));
        });
    }
}
