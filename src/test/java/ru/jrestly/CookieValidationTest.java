package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of {@code @Cookie} declarations across interface, method and parameter
 * levels (ADR-0032): blank names and CRLF injection in name/value are rejected, valid
 * declarations pass, and an invalid controller fails proxy creation with
 * {@code ControllerValidationException}. No network.
 */
class CookieValidationTest {

    @Cookie(name = "   ", value = "val")
    interface BlankInterfaceCookieApi {
        @Get("/test")
        String test();
    }

    @Cookie(name = "name\r\ninjection", value = "val")
    interface CrlfInterfaceCookieNameApi {
        @Get("/test")
        String test();
    }

    @Cookie(name = "name", value = "val\r\ninjection")
    interface CrlfInterfaceCookieValueApi {
        @Get("/test")
        String test();
    }

    interface MethodCookieProblemsApi {
        @Get("/blank")
        @Cookie(name = "", value = "val")
        String blankName();

        @Get("/crlf-name")
        @Cookie(name = "bad\nname", value = "val")
        String crlfName();

        @Get("/crlf-val")
        @Cookie(name = "good", value = "bad\rval")
        String crlfValue();
    }

    interface ParameterCookieProblemsApi {
        @Get("/param-crlf")
        String crlfParam(@Cookie(name = "param\r\n") String token);

        @Get("/param-conflict")
        String conflictParam(@Cookie(name = "auth") @RequestParam(name = "auth") String token);

        @Get("/param-conflict-header")
        String conflictHeaderParam(@Cookie(name = "auth") @ru.jrestly.annotation.RequestHeader(name = "X-Auth") String token);
    }

    interface ValidCookieApi {
        @Cookie(name = "app", value = "jrestly")
        @Get("/valid")
        @Cookie(name = "feature", value = "active")
        String getValid(@Cookie(name = "sessionId") String sessionId);
    }

    @Test
    @DisplayName("Validator detects blank interface @Cookie name")
    void detectsBlankInterfaceCookieName() {
        List<String> problems = ControllerValidator.validate(BlankInterfaceCookieApi.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie name must not be blank")));
    }

    @Test
    @DisplayName("Validator detects CRLF injection in interface @Cookie name")
    void detectsCrlfInInterfaceCookieName() {
        List<String> problems = ControllerValidator.validate(CrlfInterfaceCookieNameApi.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie name must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validator detects CRLF injection in interface @Cookie value")
    void detectsCrlfInInterfaceCookieValue() {
        List<String> problems = ControllerValidator.validate(CrlfInterfaceCookieValueApi.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie value must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validator detects problems in method-level @Cookie")
    void detectsMethodCookieProblems() {
        List<String> problems = ControllerValidator.validate(MethodCookieProblemsApi.class);
        assertTrue(problems.stream().anyMatch(p -> p.contains("blankName(): @Cookie name must not be blank")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("crlfName(): @Cookie name must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("crlfValue(): @Cookie value must not contain CRLF characters")));
    }

    @Test
    @DisplayName("Validator detects problems in parameter-level @Cookie")
    void detectsParameterCookieProblems() {
        List<String> problems = ControllerValidator.validate(ParameterCookieProblemsApi.class);
        assertTrue(problems.stream().anyMatch(p -> p.contains("crlfParam(): @Cookie parameter name must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("conflictParam(): parameter must not combine @Cookie with other HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("conflictHeaderParam(): parameter must not combine @Cookie with other HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validator passes for valid @Cookie declarations")
    void passesForValidCookieDeclarations() {
        List<String> problems = ControllerValidator.validate(ValidCookieApi.class);
        assertTrue(problems.isEmpty());
    }

    @Test
    @DisplayName("JRestlyClient throws ControllerValidationException for invalid controller")
    void clientThrowsValidationException() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .validateControllers(true)
                .build();

        try {
            assertThrows(ControllerValidationException.class, () ->
                    client.get(BlankInterfaceCookieApi.class));
        } finally {
            client.close();
        }
    }
}
