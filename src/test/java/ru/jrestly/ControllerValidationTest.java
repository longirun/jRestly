package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.BrokenController;
import ru.jrestly.fixtures.TestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ControllerValidationTest {

    @Test
    void throwsOnGetWithFullProblemListByDefault() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        ControllerValidationException e = assertThrows(ControllerValidationException.class,
                () -> client.get(BrokenController.class));

        assertEquals(BrokenController.class, e.getController());
        assertEquals(4, e.getProblems().size());
        assertTrue(e.getMessage().contains("noHttpAnnotation"));
        assertTrue(e.getMessage().contains("multipleHttpAnnotations"));
        assertTrue(e.getMessage().contains("multipartWithoutParams"));
        assertTrue(e.getMessage().contains("formWithoutParams"));
    }

    @Test
    void disabledValidationKeepsPerMethodFailureOnCall() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1")
                .validateControllers(false)
                .build();
        BrokenController broken = client.get(BrokenController.class);

        assertThrows(UnsupportedOperationException.class, broken::noHttpAnnotation);
    }

    @Test
    void validControllerPassesEagerValidation() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        assertNotNull(client.get(TestController.class));
    }

    interface StreamController {
        @ru.jrestly.annotation.Get(path = "/api/stream")
        java.io.InputStream stream();

        @ru.jrestly.annotation.Get(path = "/api/stream/async")
        java.util.concurrent.CompletableFuture<java.io.InputStream> streamAsync();

        @ru.jrestly.annotation.Get(path = "/api/stream/lines")
        java.util.stream.Stream<String> lines();

        @ru.jrestly.annotation.Get(path = "/api/stream/lines/async")
        java.util.concurrent.CompletableFuture<java.util.stream.Stream<String>> linesAsync();

        @ru.jrestly.annotation.Get(path = "/api/stream/events")
        java.util.stream.Stream<ru.jrestly.http.ServerSentEvent> events();

        @ru.jrestly.annotation.Get(path = "/api/stream/events/async")
        java.util.concurrent.CompletableFuture<java.util.stream.Stream<ru.jrestly.http.ServerSentEvent>> eventsAsync();
    }

    @Test
    void streamReturnTypesPassValidation() {
        assertTrue(ControllerValidator.validate(StreamController.class).isEmpty());

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertNotNull(client.get(StreamController.class));
    }

    @ru.jrestly.annotation.RequestHeader(name = "  ", value = "v")
    interface BlankClassHeaderController {
        @ru.jrestly.annotation.Get(path = "/test")
        String call();
    }

    @ru.jrestly.annotation.RequestDefaultParam(name = "", value = "v")
    interface BlankClassDefaultParamController {
        @ru.jrestly.annotation.Get(path = "/test")
        String call();
    }

    interface BlankMethodHeaderAndParamController {
        @ru.jrestly.annotation.Get(path = "/test-header")
        @ru.jrestly.annotation.RequestHeader(name = "", value = "v")
        String blankHeader();

        @ru.jrestly.annotation.Get(path = "/test-param")
        @ru.jrestly.annotation.RequestDefaultParam(name = "   ", value = "v")
        String blankParam();
    }

    @Test
    void rejectsBlankHeaderOrParamNameOnClassAndMethod() {
        var classHeaderProblems = ControllerValidator.validate(BlankClassHeaderController.class);
        assertEquals(1, classHeaderProblems.size());
        assertTrue(classHeaderProblems.getFirst().contains("@RequestHeader name must not be blank"));

        var classParamProblems = ControllerValidator.validate(BlankClassDefaultParamController.class);
        assertEquals(1, classParamProblems.size());
        assertTrue(classParamProblems.getFirst().contains("@RequestDefaultParam name must not be blank"));

        var methodProblems = ControllerValidator.validate(BlankMethodHeaderAndParamController.class);
        assertEquals(2, methodProblems.size());
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("blankHeader(): @RequestHeader name must not be blank")));
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("blankParam(): @RequestDefaultParam name must not be blank")));
    }
}

